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
package org.apache.kafka.common.requests;

import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AccessControlEntryFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.protocol.types.Struct;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.resource.Resource;
import org.apache.kafka.common.resource.ResourceFilter;
import org.apache.kafka.common.resource.ResourceType;

import java.util.Optional;

import static org.apache.kafka.common.protocol.CommonFields.*;

class RequestUtils {

    static Resource resourceFromStructFields(Struct struct) {
        byte resourceType = struct.get(RESOURCE_TYPE);
        String name = struct.get(RESOURCE_NAME);
        return new Resource(ResourceType.fromCode(resourceType), name);
    }

    static void resourceSetStructFields(Resource resource, Struct struct) {
        struct.set(RESOURCE_TYPE, resource.resourceType().code());
        struct.set(RESOURCE_NAME, resource.name());
    }

    static ResourceFilter resourceFilterFromStructFields(Struct struct) {
        byte resourceType = struct.get(RESOURCE_TYPE);
        String name = struct.get(RESOURCE_NAME_FILTER);
        return new ResourceFilter(ResourceType.fromCode(resourceType), name);
    }

    static void resourceFilterSetStructFields(ResourceFilter resourceFilter, Struct struct) {
        struct.set(RESOURCE_TYPE, resourceFilter.resourceType().code());
        struct.set(RESOURCE_NAME_FILTER, resourceFilter.name());
    }

    static AccessControlEntry aceFromStructFields(Struct struct) {
        String principal = struct.get(PRINCIPAL);
        String host = struct.get(HOST);
        byte operation = struct.get(OPERATION);
        byte permissionType = struct.get(PERMISSION_TYPE);
        return new AccessControlEntry(principal, host, AclOperation.fromCode(operation),
            AclPermissionType.fromCode(permissionType));
    }

    static void aceSetStructFields(AccessControlEntry data, Struct struct) {
        struct.set(PRINCIPAL, data.principal());
        struct.set(HOST, data.host());
        struct.set(OPERATION, data.operation().code());
        struct.set(PERMISSION_TYPE, data.permissionType().code());
    }

    static AccessControlEntryFilter aceFilterFromStructFields(Struct struct) {
        String principal = struct.get(PRINCIPAL_FILTER);
        String host = struct.get(HOST_FILTER);
        byte operation = struct.get(OPERATION);
        byte permissionType = struct.get(PERMISSION_TYPE);
        return new AccessControlEntryFilter(principal, host, AclOperation.fromCode(operation),
            AclPermissionType.fromCode(permissionType));
    }

    static void aceFilterSetStructFields(AccessControlEntryFilter filter, Struct struct) {
        struct.set(PRINCIPAL_FILTER, filter.principal());
        struct.set(HOST_FILTER, filter.host());
        struct.set(OPERATION, filter.operation().code());
        struct.set(PERMISSION_TYPE, filter.permissionType().code());
    }

    static Optional<Integer> getLeaderEpoch(int leaderEpoch) {
        Optional<Integer> leaderEpochOpt = leaderEpoch == RecordBatch.NO_PARTITION_LEADER_EPOCH ?
                Optional.empty() : Optional.of(leaderEpoch);
        return leaderEpochOpt;
    }
}
