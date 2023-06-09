/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.common.security.authenticator;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.NetworkClient;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.errors.IllegalSaslStateException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.UnsupportedSaslMechanismException;
import org.apache.kafka.common.network.*;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.protocol.types.SchemaException;
import org.apache.kafka.common.requests.*;
import org.apache.kafka.common.requests.ApiVersionsResponse.ApiVersion;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class SaslClientAuthenticator implements Authenticator {

    public enum SaslState {
        SEND_APIVERSIONS_REQUEST,     // Initial state: client sends ApiVersionsRequest in this state
        RECEIVE_APIVERSIONS_RESPONSE, // Awaiting ApiVersionsResponse from server
        SEND_HANDSHAKE_REQUEST,       // Received ApiVersionsResponse, send SaslHandshake request
        RECEIVE_HANDSHAKE_RESPONSE,   // Awaiting SaslHandshake request from server
        INITIAL,                      // Initial state starting SASL token exchange for configured mechanism, send first token
        INTERMEDIATE,                 // Intermediate state during SASL token exchange, process challenges and send responses
        CLIENT_COMPLETE,              // Sent response to last challenge. If using SaslAuthenticate, wait for authentication status from server, else COMPLETE
        COMPLETE,                     // Authentication sequence complete. If using SaslAuthenticate, this state implies successful authentication.
        FAILED                        // Failed authentication due to an error at some stage
    }

    private static final Logger LOG = LoggerFactory.getLogger(SaslClientAuthenticator.class);
    private static final short DISABLE_KAFKA_SASL_AUTHENTICATE_HEADER = -1;

    private final Subject subject;
    private final String servicePrincipal;
    private final String host;
    private final String node;
    private final String mechanism;
    private final TransportLayer transportLayer;
    private final SaslClient saslClient;
    private final Map<String, ?> configs;
    private final String clientPrincipalName;
    private final AuthCallbackHandler callbackHandler;

    // buffers used in `authenticate`
    private NetworkReceive netInBuffer;
    private Send netOutBuffer;

    // Current SASL state
    private SaslState saslState;
    // Next SASL state to be set when outgoing writes associated with the current SASL state complete
    private SaslState pendingSaslState;
    // Correlation ID for the next request
    private int correlationId;
    // Request header for which response from the server is pending
    private RequestHeader currentRequestHeader;
    // Version of SaslAuthenticate request/responses
    private short saslAuthenticateVersion;

    public SaslClientAuthenticator(Map<String, ?> configs,
                                   String node,
                                   Subject subject,
                                   String servicePrincipal,
                                   String host,
                                   String mechanism,
                                   boolean handshakeRequestEnable,
                                   TransportLayer transportLayer) throws IOException {
        this.node = node;
        this.subject = subject;
        this.host = host;
        this.servicePrincipal = servicePrincipal;
        this.mechanism = mechanism;
        this.correlationId = -1;
        this.transportLayer = transportLayer;
        this.configs = configs;
        this.saslAuthenticateVersion = DISABLE_KAFKA_SASL_AUTHENTICATE_HEADER;

        try {
            setSaslState(handshakeRequestEnable ? SaslState.SEND_APIVERSIONS_REQUEST : SaslState.INITIAL);

            // determine client principal from subject for Kerberos to use as authorization id for the SaslClient.
            // For other mechanisms, the authenticated principal (username for PLAIN and SCRAM) is used as
            // authorization id. Hence the principal is not specified for creating the SaslClient.
            if (mechanism.equals(SaslConfigs.GSSAPI_MECHANISM))
                this.clientPrincipalName = firstPrincipal(subject);
            else
                this.clientPrincipalName = null;

            callbackHandler = new SaslClientCallbackHandler();
            callbackHandler.configure(configs, Mode.CLIENT, subject, mechanism);

            saslClient = createSaslClient();
        } catch (Exception e) {
            throw new SaslAuthenticationException("Failed to configure SaslClientAuthenticator", e);
        }
    }

    private SaslClient createSaslClient() {
        try {
            return Subject.doAs(subject, new PrivilegedExceptionAction<SaslClient>() {
                public SaslClient run() throws SaslException {
                    String[] mechs = {mechanism};
                    LOG.debug("Creating SaslClient: client={};service={};serviceHostname={};mechs={}",
                        clientPrincipalName, servicePrincipal, host, Arrays.toString(mechs));
                    return Sasl.createSaslClient(mechs, clientPrincipalName, servicePrincipal, host, configs, callbackHandler);
                }
            });
        } catch (PrivilegedActionException e) {
            throw new SaslAuthenticationException("Failed to create SaslClient with mechanism " + mechanism, e.getCause());
        }
    }

    /**
     * Sends an empty message to the server to initiate the authentication process. It then evaluates server challenges
     * via `SaslClient.evaluateChallenge` and returns client responses until authentication succeeds or fails.
     *
     * The messages are sent and received as size delimited bytes that consists of a 4 byte network-ordered size N
     * followed by N bytes representing the opaque payload.
     */
    public void authenticate() throws IOException {
        short saslHandshakeVersion = 0;
        if (netOutBuffer != null && !flushNetOutBufferAndUpdateInterestOps())
            return;

        switch (saslState) {
            case SEND_APIVERSIONS_REQUEST:
                // Always use version 0 request since brokers treat requests with schema exceptions as GSSAPI tokens
                ApiVersionsRequest apiVersionsRequest = new ApiVersionsRequest.Builder().build((short) 0);
                send(apiVersionsRequest.toSend(node, nextRequestHeader(ApiKeys.API_VERSIONS, apiVersionsRequest.version())));
                setSaslState(SaslState.RECEIVE_APIVERSIONS_RESPONSE);
                break;
            case RECEIVE_APIVERSIONS_RESPONSE:
                ApiVersionsResponse apiVersionsResponse = (ApiVersionsResponse) receiveKafkaResponse();
                if (apiVersionsResponse == null)
                    break;
                else {
                    saslHandshakeVersion = apiVersionsResponse.apiVersion(ApiKeys.SASL_HANDSHAKE.id).maxVersion;
                    ApiVersion authenticateVersion = apiVersionsResponse.apiVersion(ApiKeys.SASL_AUTHENTICATE.id);
                    if (authenticateVersion != null)
                        saslAuthenticateVersion((short) Math.min(authenticateVersion.maxVersion, ApiKeys.SASL_AUTHENTICATE.latestVersion()));
                    setSaslState(SaslState.SEND_HANDSHAKE_REQUEST);
                    // Fall through to send send handshake request with the latest supported version
                }
            case SEND_HANDSHAKE_REQUEST:
                SaslHandshakeRequest handshakeRequest = createSaslHandshakeRequest(saslHandshakeVersion);
                send(handshakeRequest.toSend(node, nextRequestHeader(ApiKeys.SASL_HANDSHAKE, handshakeRequest.version())));
                setSaslState(SaslState.RECEIVE_HANDSHAKE_RESPONSE);
                break;
            case RECEIVE_HANDSHAKE_RESPONSE:
                SaslHandshakeResponse handshakeResponse = (SaslHandshakeResponse) receiveKafkaResponse();
                if (handshakeResponse == null)
                    break;
                else {
                    handleSaslHandshakeResponse(handshakeResponse);
                    setSaslState(SaslState.INITIAL);
                    // Fall through and start SASL authentication using the configured client mechanism
                }
            case INITIAL:
                sendSaslClientToken(new byte[0], true);
                setSaslState(SaslState.INTERMEDIATE);
                break;
            case INTERMEDIATE:
                byte[] serverToken = receiveToken();
                boolean noResponsesPending = serverToken != null && !sendSaslClientToken(serverToken, false);
                // For versions without SASL_AUTHENTICATE header, SASL exchange may be complete after a token is sent to server.
                // For versions with SASL_AUTHENTICATE header, server always sends a response to each SASL_AUTHENTICATE request.
                if (saslClient.isComplete()) {
                    if (saslAuthenticateVersion == DISABLE_KAFKA_SASL_AUTHENTICATE_HEADER || noResponsesPending)
                        setSaslState(SaslState.COMPLETE);
                    else
                        setSaslState(SaslState.CLIENT_COMPLETE);
                }
                break;
            case CLIENT_COMPLETE:
                byte[] serverResponse = receiveToken();
                if (serverResponse != null)
                    setSaslState(SaslState.COMPLETE);
                break;
            case COMPLETE:
                break;
            case FAILED:
                // Should never get here since exception would have been propagated earlier
                throw new IllegalStateException("SASL handshake has already failed");
        }
    }

    private RequestHeader nextRequestHeader(ApiKeys apiKey, short version) {
        String clientId = (String) configs.get(CommonClientConfigs.CLIENT_ID_CONFIG);
        currentRequestHeader = new RequestHeader(apiKey, version, clientId, correlationId++);
        return currentRequestHeader;
    }

    // Visible to override for testing
    protected SaslHandshakeRequest createSaslHandshakeRequest(short version) {
        return new SaslHandshakeRequest.Builder(mechanism).build(version);
    }

    // Visible to override for testing
    protected void saslAuthenticateVersion(short version) {
        this.saslAuthenticateVersion = version;
    }

    private void setSaslState(SaslState saslState) {
        if (netOutBuffer != null && !netOutBuffer.completed())
            pendingSaslState = saslState;
        else {
            this.pendingSaslState = null;
            this.saslState = saslState;
            LOG.debug("Set SASL client state to {}", saslState);
            if (saslState == SaslState.COMPLETE)
                transportLayer.removeInterestOps(SelectionKey.OP_WRITE);
        }
    }

    /**
     * Sends a SASL client token to server if required. This may be an initial token to start
     * SASL token exchange or response to a challenge from the server.
     * @return true if a token was sent to the server
     */
    private boolean sendSaslClientToken(byte[] serverToken, boolean isInitial) throws IOException {
        if (!saslClient.isComplete()) {
            byte[] saslToken = createSaslToken(serverToken, isInitial);
            if (saslToken != null) {
                ByteBuffer tokenBuf = ByteBuffer.wrap(saslToken);
                if (saslAuthenticateVersion != DISABLE_KAFKA_SASL_AUTHENTICATE_HEADER) {
                    SaslAuthenticateRequest request = new SaslAuthenticateRequest.Builder(tokenBuf).build(saslAuthenticateVersion);
                    tokenBuf = request.serialize(nextRequestHeader(ApiKeys.SASL_AUTHENTICATE, saslAuthenticateVersion));
                }
                send(new NetworkSend(node, tokenBuf));
                return true;
            }
        }
        return false;
    }

    private void send(Send send) throws IOException {
        try {
            netOutBuffer = send;
            flushNetOutBufferAndUpdateInterestOps();
        } catch (IOException e) {
            setSaslState(SaslState.FAILED);
            throw e;
        }
    }

    private boolean flushNetOutBufferAndUpdateInterestOps() throws IOException {
        boolean flushedCompletely = flushNetOutBuffer();
        if (flushedCompletely) {
            transportLayer.removeInterestOps(SelectionKey.OP_WRITE);
            if (pendingSaslState != null)
                setSaslState(pendingSaslState);
        } else
            transportLayer.addInterestOps(SelectionKey.OP_WRITE);
        return flushedCompletely;
    }

    private byte[] receiveResponseOrToken() throws IOException {
        if (netInBuffer == null) netInBuffer = new NetworkReceive(node);
        netInBuffer.readFrom(transportLayer);
        byte[] serverPacket = null;
        if (netInBuffer.complete()) {
            netInBuffer.payload().rewind();
            serverPacket = new byte[netInBuffer.payload().remaining()];
            netInBuffer.payload().get(serverPacket, 0, serverPacket.length);
            netInBuffer = null; // reset the networkReceive as we read all the data.
        }
        return serverPacket;
    }

    public KafkaPrincipal principal() {
        return new KafkaPrincipal(KafkaPrincipal.USER_TYPE, clientPrincipalName);
    }

    public boolean complete() {
        return saslState == SaslState.COMPLETE;
    }

    public void close() throws IOException {
        if (saslClient != null)
            saslClient.dispose();
        if (callbackHandler != null)
            callbackHandler.close();
    }

    private byte[] receiveToken() throws IOException {
        if (saslAuthenticateVersion == DISABLE_KAFKA_SASL_AUTHENTICATE_HEADER) {
            return receiveResponseOrToken();
        } else {
            SaslAuthenticateResponse response = (SaslAuthenticateResponse) receiveKafkaResponse();
            if (response != null) {
                Errors error = response.error();
                if (error != Errors.NONE) {
                    setSaslState(SaslState.FAILED);
                    String errMsg = response.errorMessage();
                    throw errMsg == null ? error.exception() : error.exception(errMsg);
                }
                return Utils.readBytes(response.saslAuthBytes());
            } else
                return null;
        }
    }


    private byte[] createSaslToken(final byte[] saslToken, boolean isInitial) throws SaslException {
        if (saslToken == null)
            throw new IllegalSaslStateException("Error authenticating with the Kafka Broker: received a `null` saslToken.");

        try {
            if (isInitial && !saslClient.hasInitialResponse())
                return saslToken;
            else
                return Subject.doAs(subject, new PrivilegedExceptionAction<byte[]>() {
                    public byte[] run() throws SaslException {
                        return saslClient.evaluateChallenge(saslToken);
                    }
                });
        } catch (PrivilegedActionException e) {
            String error = "An error: (" + e + ") occurred when evaluating SASL token received from the Kafka Broker.";
            // Try to provide hints to use about what went wrong so they can fix their configuration.
            // TODO: introspect about e: look for GSS information.
            final String unknownServerErrorText =
                "(Mechanism level: Server not found in Kerberos database (7) - UNKNOWN_SERVER)";
            if (e.toString().contains(unknownServerErrorText)) {
                error += " This may be caused by Java's being unable to resolve the Kafka Broker's" +
                    " hostname correctly. You may want to try to adding" +
                    " '-Dsun.net.spi.nameservice.provider.1=dns,sun' to your client's JVMFLAGS environment." +
                    " Users must configure FQDN of kafka brokers when authenticating using SASL and" +
                    " `socketChannel.socket().getInetAddress().getHostName()` must match the hostname in `principal/hostname@realm`";
            }
            error += " Kafka Client will go to AUTHENTICATION_FAILED state.";
            //Unwrap the SaslException inside `PrivilegedActionException`
            throw new SaslAuthenticationException(error, e.getCause());
        }
    }

    private boolean flushNetOutBuffer() throws IOException {
        if (!netOutBuffer.completed()) {
            netOutBuffer.writeTo(transportLayer);
        }
        return netOutBuffer.completed();
    }

    private AbstractResponse receiveKafkaResponse() throws IOException {
        try {
            byte[] responseBytes = receiveResponseOrToken();
            if (responseBytes == null)
                return null;
            else {
                AbstractResponse response = NetworkClient.parseResponse(ByteBuffer.wrap(responseBytes), currentRequestHeader);
                currentRequestHeader = null;
                return response;
            }
        } catch (SchemaException | IllegalArgumentException e) {
            LOG.debug("Invalid SASL mechanism response, server may be expecting only GSSAPI tokens");
            setSaslState(SaslState.FAILED);
            LOG.error("Sasl client receiveKafkaResponse failed", e);
            throw new IllegalSaslStateException("Invalid SASL mechanism response, server may be expecting a different protocol", e);
        }
    }

    private void handleSaslHandshakeResponse(SaslHandshakeResponse response) {
        Errors error = response.error();
        if (error != Errors.NONE)
            setSaslState(SaslState.FAILED);
        switch (error) {
            case NONE:
                break;
            case UNSUPPORTED_SASL_MECHANISM:
                throw new UnsupportedSaslMechanismException(String.format("Client SASL mechanism '%s' not enabled in the server, enabled mechanisms are %s",
                    mechanism, response.enabledMechanisms()));
            case ILLEGAL_SASL_STATE:
                throw new IllegalSaslStateException(String.format("Unexpected handshake request with client mechanism %s, enabled mechanisms are %s",
                    mechanism, response.enabledMechanisms()));
            default:
                throw new IllegalSaslStateException(String.format("Unknown error code %s, client mechanism is %s, enabled mechanisms are %s",
                    response.error(), mechanism, response.enabledMechanisms()));
        }
    }

    /**
     * Returns the first Principal from Subject.
     * @throws KafkaException if there are no Principals in the Subject.
     *     During Kerberos re-login, principal is reset on Subject. An exception is
     *     thrown so that the connection is retried after any configured backoff.
     */
    static final String firstPrincipal(Subject subject) {
        Set<Principal> principals = subject.getPrincipals();
        synchronized (principals) {
            Iterator<Principal> iterator = principals.iterator();
            if (iterator.hasNext())
                return iterator.next().getName();
            else
                throw new KafkaException("Principal could not be determined from Subject, this may be a transient failure due to Kerberos re-login");
        }
    }
}
