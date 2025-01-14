/**
 * Copyright © 2016-2021 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.device;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.SneakyThrows;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceProfileProvisionType;
import org.thingsboard.server.common.data.DeviceProfileType;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.device.credentials.BasicMqttCredentials;
import org.thingsboard.server.common.data.device.credentials.lwm2m.LwM2MClientCredentials;
import org.thingsboard.server.common.data.device.credentials.lwm2m.LwM2MSecurityMode;
import org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileConfiguration;
import org.thingsboard.server.common.data.device.profile.DeviceProfileData;
import org.thingsboard.server.common.data.device.profile.DeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.device.profile.DisabledDeviceProfileProvisionConfiguration;
import org.thingsboard.server.common.data.device.profile.Lwm2mDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.dao.device.DeviceCredentialsService;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.exception.DeviceCredentialsValidationException;
import org.thingsboard.server.dao.tenant.TbTenantProfileCache;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.action.EntityActionService;
import org.thingsboard.server.service.importing.AbstractBulkImportService;
import org.thingsboard.server.service.importing.BulkImportColumnType;
import org.thingsboard.server.service.importing.BulkImportRequest;
import org.thingsboard.server.service.importing.ImportedEntityInfo;
import org.thingsboard.server.service.security.AccessValidator;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.AccessControlService;
import org.thingsboard.server.service.telemetry.TelemetrySubscriptionService;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Service
@TbCoreComponent
public class DeviceBulkImportService extends AbstractBulkImportService<Device> {
    protected final DeviceService deviceService;
    protected final DeviceCredentialsService deviceCredentialsService;
    protected final DeviceProfileService deviceProfileService;

    private final Lock findOrCreateDeviceProfileLock = new ReentrantLock();

    public DeviceBulkImportService(TelemetrySubscriptionService tsSubscriptionService, TbTenantProfileCache tenantProfileCache,
                                   AccessControlService accessControlService, AccessValidator accessValidator,
                                   EntityActionService entityActionService, TbClusterService clusterService,
                                   DeviceService deviceService, DeviceCredentialsService deviceCredentialsService,
                                   DeviceProfileService deviceProfileService) {
        super(tsSubscriptionService, tenantProfileCache, accessControlService, accessValidator, entityActionService, clusterService);
        this.deviceService = deviceService;
        this.deviceCredentialsService = deviceCredentialsService;
        this.deviceProfileService = deviceProfileService;
    }

    @Override
    protected ImportedEntityInfo<Device> saveEntity(BulkImportRequest importRequest, Map<BulkImportColumnType, String> fields, SecurityUser user) {
        ImportedEntityInfo<Device> importedEntityInfo = new ImportedEntityInfo<>();

        Device device = new Device();
        device.setTenantId(user.getTenantId());
        setDeviceFields(device, fields);

        Device existingDevice = deviceService.findDeviceByTenantIdAndName(user.getTenantId(), device.getName());
        if (existingDevice != null && importRequest.getMapping().getUpdate()) {
            importedEntityInfo.setOldEntity(new Device(existingDevice));
            importedEntityInfo.setUpdated(true);
            existingDevice.updateDevice(device);
            device = existingDevice;
        }

        DeviceCredentials deviceCredentials;
        try {
            deviceCredentials = createDeviceCredentials(fields);
            deviceCredentialsService.formatCredentials(deviceCredentials);
        } catch (Exception e) {
            throw new DeviceCredentialsValidationException("Invalid device credentials: " + e.getMessage());
        }

        DeviceProfile deviceProfile;
        if (deviceCredentials.getCredentialsType() == DeviceCredentialsType.LWM2M_CREDENTIALS) {
            deviceProfile = setUpLwM2mDeviceProfile(user.getTenantId(), device);
        } else if (StringUtils.isNotEmpty(device.getType())) {
            deviceProfile = deviceProfileService.findOrCreateDeviceProfile(user.getTenantId(), device.getType());
        } else {
            deviceProfile = deviceProfileService.findDefaultDeviceProfile(user.getTenantId());
        }
        device.setDeviceProfileId(deviceProfile.getId());

        device = deviceService.saveDeviceWithCredentials(device, deviceCredentials);

        importedEntityInfo.setEntity(device);
        return importedEntityInfo;
    }

    private void setDeviceFields(Device device, Map<BulkImportColumnType, String> fields) {
        ObjectNode additionalInfo = (ObjectNode) Optional.ofNullable(device.getAdditionalInfo()).orElseGet(JacksonUtil::newObjectNode);
        fields.forEach((columnType, value) -> {
            switch (columnType) {
                case NAME:
                    device.setName(value);
                    break;
                case TYPE:
                    device.setType(value);
                    break;
                case LABEL:
                    device.setLabel(value);
                    break;
                case DESCRIPTION:
                    additionalInfo.set("description", new TextNode(value));
                    break;
                case IS_GATEWAY:
                    additionalInfo.set("gateway", BooleanNode.valueOf(Boolean.parseBoolean(value)));
                    break;
            }
            device.setAdditionalInfo(additionalInfo);
        });
    }

    @SneakyThrows
    private DeviceCredentials createDeviceCredentials(Map<BulkImportColumnType, String> fields) {
        DeviceCredentials credentials = new DeviceCredentials();
        if (fields.containsKey(BulkImportColumnType.LWM2M_CLIENT_ENDPOINT)) {
            credentials.setCredentialsType(DeviceCredentialsType.LWM2M_CREDENTIALS);
            setUpLwm2mCredentials(fields, credentials);
        } else if (fields.containsKey(BulkImportColumnType.X509)) {
            credentials.setCredentialsType(DeviceCredentialsType.X509_CERTIFICATE);
            setUpX509CertificateCredentials(fields, credentials);
        } else if (CollectionUtils.containsAny(fields.keySet(), EnumSet.of(BulkImportColumnType.MQTT_CLIENT_ID, BulkImportColumnType.MQTT_USER_NAME, BulkImportColumnType.MQTT_PASSWORD))) {
            credentials.setCredentialsType(DeviceCredentialsType.MQTT_BASIC);
            setUpBasicMqttCredentials(fields, credentials);
        } else {
            credentials.setCredentialsType(DeviceCredentialsType.ACCESS_TOKEN);
            setUpAccessTokenCredentials(fields, credentials);
        }
        return credentials;
    }

    private void setUpAccessTokenCredentials(Map<BulkImportColumnType, String> fields, DeviceCredentials credentials) {
        credentials.setCredentialsId(Optional.ofNullable(fields.get(BulkImportColumnType.ACCESS_TOKEN))
                .orElseGet(() -> RandomStringUtils.randomAlphanumeric(20)));
    }

    private void setUpBasicMqttCredentials(Map<BulkImportColumnType, String> fields, DeviceCredentials credentials) {
        BasicMqttCredentials basicMqttCredentials = new BasicMqttCredentials();
        basicMqttCredentials.setClientId(fields.get(BulkImportColumnType.MQTT_CLIENT_ID));
        basicMqttCredentials.setUserName(fields.get(BulkImportColumnType.MQTT_USER_NAME));
        basicMqttCredentials.setPassword(fields.get(BulkImportColumnType.MQTT_PASSWORD));
        credentials.setCredentialsValue(JacksonUtil.toString(basicMqttCredentials));
    }

    private void setUpX509CertificateCredentials(Map<BulkImportColumnType, String> fields, DeviceCredentials credentials) {
        credentials.setCredentialsValue(fields.get(BulkImportColumnType.X509));
    }

    private void setUpLwm2mCredentials(Map<BulkImportColumnType, String> fields, DeviceCredentials credentials) throws com.fasterxml.jackson.core.JsonProcessingException {
        ObjectNode lwm2mCredentials = JacksonUtil.newObjectNode();

        Set.of(BulkImportColumnType.LWM2M_CLIENT_SECURITY_CONFIG_MODE, BulkImportColumnType.LWM2M_BOOTSTRAP_SERVER_SECURITY_MODE,
                BulkImportColumnType.LWM2M_SERVER_SECURITY_MODE).stream()
                .map(fields::get)
                .filter(Objects::nonNull)
                .forEach(securityMode -> {
                    try {
                        LwM2MSecurityMode.valueOf(securityMode.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new DeviceCredentialsValidationException("Unknown LwM2M security mode: " + securityMode + ", (the mode should be: NO_SEC, PSK, RPK, X509)!");
                    }
                });

        ObjectNode client = JacksonUtil.newObjectNode();
        setValues(client, fields, Set.of(BulkImportColumnType.LWM2M_CLIENT_SECURITY_CONFIG_MODE,
                BulkImportColumnType.LWM2M_CLIENT_ENDPOINT, BulkImportColumnType.LWM2M_CLIENT_IDENTITY,
                BulkImportColumnType.LWM2M_CLIENT_KEY, BulkImportColumnType.LWM2M_CLIENT_CERT));
        LwM2MClientCredentials lwM2MClientCredentials = JacksonUtil.treeToValue(client, LwM2MClientCredentials.class);
        // so that only fields needed for specific type of lwM2MClientCredentials were saved in json
        lwm2mCredentials.set("client", JacksonUtil.valueToTree(lwM2MClientCredentials));

        ObjectNode bootstrapServer = JacksonUtil.newObjectNode();
        setValues(bootstrapServer, fields, Set.of(BulkImportColumnType.LWM2M_BOOTSTRAP_SERVER_SECURITY_MODE,
                BulkImportColumnType.LWM2M_BOOTSTRAP_SERVER_PUBLIC_KEY_OR_ID, BulkImportColumnType.LWM2M_BOOTSTRAP_SERVER_SECRET_KEY));

        ObjectNode lwm2mServer = JacksonUtil.newObjectNode();
        setValues(lwm2mServer, fields, Set.of(BulkImportColumnType.LWM2M_SERVER_SECURITY_MODE,
                BulkImportColumnType.LWM2M_SERVER_CLIENT_PUBLIC_KEY_OR_ID, BulkImportColumnType.LWM2M_SERVER_CLIENT_SECRET_KEY));

        ObjectNode bootstrap = JacksonUtil.newObjectNode();
        bootstrap.set("bootstrapServer", bootstrapServer);
        bootstrap.set("lwm2mServer", lwm2mServer);
        lwm2mCredentials.set("bootstrap", bootstrap);

        credentials.setCredentialsValue(lwm2mCredentials.toString());
    }

    private DeviceProfile setUpLwM2mDeviceProfile(TenantId tenantId, Device device) {
        DeviceProfile deviceProfile = deviceProfileService.findDeviceProfileByName(tenantId, device.getType());
        if (deviceProfile != null) {
            if (deviceProfile.getTransportType() != DeviceTransportType.LWM2M) {
                deviceProfile.setTransportType(DeviceTransportType.LWM2M);
                deviceProfile.getProfileData().setTransportConfiguration(new Lwm2mDeviceProfileTransportConfiguration());
                deviceProfile = deviceProfileService.saveDeviceProfile(deviceProfile);
            }
        } else {
            findOrCreateDeviceProfileLock.lock();
            try {
                deviceProfile = deviceProfileService.findDeviceProfileByName(tenantId, device.getType());
                if (deviceProfile == null) {
                    deviceProfile = new DeviceProfile();
                    deviceProfile.setTenantId(tenantId);
                    deviceProfile.setType(DeviceProfileType.DEFAULT);
                    deviceProfile.setName(device.getType());
                    deviceProfile.setTransportType(DeviceTransportType.LWM2M);
                    deviceProfile.setProvisionType(DeviceProfileProvisionType.DISABLED);

                    DeviceProfileData deviceProfileData = new DeviceProfileData();
                    DefaultDeviceProfileConfiguration configuration = new DefaultDeviceProfileConfiguration();
                    DeviceProfileTransportConfiguration transportConfiguration = new Lwm2mDeviceProfileTransportConfiguration();
                    DisabledDeviceProfileProvisionConfiguration provisionConfiguration = new DisabledDeviceProfileProvisionConfiguration(null);

                    deviceProfileData.setConfiguration(configuration);
                    deviceProfileData.setTransportConfiguration(transportConfiguration);
                    deviceProfileData.setProvisionConfiguration(provisionConfiguration);
                    deviceProfile.setProfileData(deviceProfileData);

                    deviceProfile = deviceProfileService.saveDeviceProfile(deviceProfile);
                }
            } finally {
                findOrCreateDeviceProfileLock.unlock();
            }
        }
        return deviceProfile;
    }

    private void setValues(ObjectNode objectNode, Map<BulkImportColumnType, String> data, Collection<BulkImportColumnType> columns) {
        for (BulkImportColumnType column : columns) {
            String value = StringUtils.defaultString(data.get(column), column.getDefaultValue());
            if (value != null && column.getKey() != null) {
                objectNode.set(column.getKey(), new TextNode(value));
            }
        }
    }

}
