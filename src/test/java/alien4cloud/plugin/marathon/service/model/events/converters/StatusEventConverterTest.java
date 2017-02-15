package alien4cloud.plugin.marathon.service.model.events.converters;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

import java.util.Optional;

import alien4cloud.plugin.marathon.service.model.events.status.AbstractStatusEvent;
import org.junit.Before;
import org.junit.Test;

import alien4cloud.paas.model.InstanceStatus;
import alien4cloud.paas.model.PaaSInstanceStateMonitorEvent;
import alien4cloud.plugin.marathon.service.MarathonMappingService;
import alien4cloud.plugin.marathon.service.model.events.status.HealthStatusChangedEvent;
import alien4cloud.plugin.marathon.service.model.events.status.StatusUpdateEvent;
import alien4cloud.plugin.marathon.service.model.mapping.MarathonAppsMapping;
import mesosphere.marathon.client.utils.ModelUtils;

/**
 * @author Adrian Fraisse
 */
public class StatusEventConverterTest {

    private StatusEventConverter eventConverter;
    private MarathonMappingService mockMappingService;
    private MarathonAppsMapping mockAppMapping;

    @Before
    public void setUp() throws Exception {
        mockMappingService = mock(MarathonMappingService.class);
        eventConverter = new StatusEventConverter(mockMappingService);
        mockAppMapping = new MarathonAppsMapping("alien-deployment-id");
        mockAppMapping.addAppToNodeTemplateMapping("my-app", "my-node-template");
        when(mockMappingService.getMarathonAppMapping("my-group")).thenReturn(Optional.of(mockAppMapping));
    }

    private final String statusUpdateEventRunning = "{\n"+
            "  \"eventType\": \"status_update_event\",\n"+
            "  \"timestamp\": \"2014-03-01T23:29:30.158Z\",\n"+
            "  \"slaveId\": \"20140909-054127-177048842-5050-1494-0\",\n"+
            "  \"taskId\": \"my-app_0-1396592784349\",\n"+
            "  \"taskStatus\": \"TASK_RUNNING\",\n"+
            "  \"appId\": \"/my-group/my-app\",\n"+
            "  \"host\": \"slave-1234.acme.org\",\n"+
            "  \"ports\": [31372],\n"+
            "  \"version\": \"2014-04-04T06:26:23.051Z\"\n"+
            "}";

    private final String statusUpdateEventStaging = "{\n"+
            "  \"eventType\": \"status_update_event\",\n"+
            "  \"timestamp\": \"2014-03-01T23:29:30.158Z\",\n"+
            "  \"slaveId\": \"20140909-054127-177048842-5050-1494-0\",\n"+
            "  \"taskId\": \"my-app_0-1396592784349\",\n"+
            "  \"taskStatus\": \"TASK_STAGING\",\n"+
            "  \"appId\": \"/my-group/my-app\",\n"+
            "  \"host\": \"slave-1234.acme.org\",\n"+
            "  \"ports\": [31372],\n"+
            "  \"version\": \"2014-04-04T06:26:23.051Z\"\n"+
            "}";

    private final String statusUpdateEventLost = "{\n"+
            "  \"eventType\": \"status_update_event\",\n"+
            "  \"timestamp\": \"2014-03-01T23:29:30.158Z\",\n"+
            "  \"slaveId\": \"20140909-054127-177048842-5050-1494-0\",\n"+
            "  \"taskId\": \"my-app_0-1396592784349\",\n"+
            "  \"taskStatus\": \"TASK_LOST\",\n"+
            "  \"appId\": \"/my-group/my-app\",\n"+
            "  \"host\": \"slave-1234.acme.org\",\n"+
            "  \"ports\": [31372],\n"+
            "  \"version\": \"2014-04-04T06:26:23.051Z\"\n"+
            "}";

    private final String healthStatusChangedEventAlive = "{\n" +
            "  \"eventType\": \"health_status_changed_event\",\n" +
            "  \"timestamp\": \"2014-03-01T23:29:30.158Z\",\n" +
            "  \"appId\": \"/my-group/my-app\",\n" +
            "  \"taskId\": \"my-app_0-1396592784349\",\n" +
            "  \"version\": \"2014-04-04T06:26:23.051Z\",\n" +
            "  \"alive\": true\n" +
            "}";

    private final String healthStatusChangedEventDown = "{\n" +
            "  \"eventType\": \"health_status_changed_event\",\n" +
            "  \"timestamp\": \"2014-03-01T23:29:30.158Z\",\n" +
            "  \"appId\": \"/my-group/my-app\",\n" +
            "  \"taskId\": \"my-app_0-1396592784349\",\n" +
            "  \"version\": \"2014-04-04T06:26:23.051Z\",\n" +
            "  \"alive\": false\n" +
            "}";


    @Test
    public void fromStatusUpdateEventRunning() throws Exception {
        StatusUpdateEvent event = ModelUtils.GSON.fromJson(statusUpdateEventRunning, StatusUpdateEvent.class);

        PaaSInstanceStateMonitorEvent monitorEvent = eventConverter.fromStatusUpdateEvent(event);
        assertEquals(InstanceStatus.SUCCESS, monitorEvent.getInstanceStatus());
        assertEquals("started", monitorEvent.getInstanceState());
        verify(mockMappingService).getMarathonAppMapping("my-group");
    }

    @Test
    public void fromStatusUpdateEventStaging() throws Exception {
        StatusUpdateEvent event = ModelUtils.GSON.fromJson(statusUpdateEventStaging, StatusUpdateEvent.class);

        PaaSInstanceStateMonitorEvent monitorEvent = eventConverter.fromStatusUpdateEvent(event);
        assertEquals(InstanceStatus.PROCESSING, monitorEvent.getInstanceStatus());
        assertEquals("creating", monitorEvent.getInstanceState());
        verify(mockMappingService).getMarathonAppMapping("my-group");
    }

    @Test
    public void fromStatusUpdateEventLost() throws Exception {
        StatusUpdateEvent event = ModelUtils.GSON.fromJson(statusUpdateEventLost, StatusUpdateEvent.class);

        PaaSInstanceStateMonitorEvent monitorEvent = eventConverter.fromStatusUpdateEvent(event);
        assertEquals(InstanceStatus.FAILURE, monitorEvent.getInstanceStatus());
        assertEquals("stopped", monitorEvent.getInstanceState());
        verify(mockMappingService).getMarathonAppMapping("my-group");
    }

    @Test
    public void fromHealthStatusChangedEvent() throws Exception {
        HealthStatusChangedEvent eventAlive = ModelUtils.GSON.fromJson(healthStatusChangedEventAlive, HealthStatusChangedEvent.class);

        PaaSInstanceStateMonitorEvent monitorEventAlive = eventConverter.fromHealthStatusChangedEvent(eventAlive);
        assertEquals(InstanceStatus.SUCCESS, monitorEventAlive.getInstanceStatus());
        HealthStatusChangedEvent event = ModelUtils.GSON.fromJson(healthStatusChangedEventAlive, HealthStatusChangedEvent.class);

        HealthStatusChangedEvent eventDown = ModelUtils.GSON.fromJson(healthStatusChangedEventDown, HealthStatusChangedEvent.class);

        PaaSInstanceStateMonitorEvent monitorEventDown = eventConverter.fromHealthStatusChangedEvent(eventDown);
        assertEquals(InstanceStatus.FAILURE, monitorEventDown.getInstanceStatus());

        verify(mockMappingService, times(2)).getMarathonAppMapping("my-group");
    }

    @Test
    public void createMonitorEvent() throws Exception {
        AbstractStatusEvent event = ModelUtils.GSON.fromJson(statusUpdateEventStaging, StatusUpdateEvent.class);

        PaaSInstanceStateMonitorEvent monitorEvent = eventConverter.fromMarathonEvent(event);
        assertEquals("my-app_0-1396592784349", monitorEvent.getInstanceId());
        assertEquals("my-node-template", monitorEvent.getNodeTemplateId());
        assertEquals("alien-deployment-id", monitorEvent.getDeploymentId());
        assertEquals(1393716570158L, monitorEvent.getDate()); // 01/03/2014 - 23:29:30+158 GMT
        verify(mockMappingService).getMarathonAppMapping("my-group");
    }

}