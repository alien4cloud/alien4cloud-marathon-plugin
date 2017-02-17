package alien4cloud.plugin.marathon.service.model.events.converters;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

import java.util.Optional;

import alien4cloud.plugin.marathon.service.model.events.deployments.AbstractDeploymentEvent;
import org.junit.Before;
import org.junit.Test;

import alien4cloud.paas.model.DeploymentStatus;
import alien4cloud.paas.model.PaaSDeploymentStatusMonitorEvent;
import alien4cloud.plugin.marathon.service.MappingService;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentFailedEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentInfoEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentSuccessEvent;
import alien4cloud.plugin.marathon.service.model.mapping.AlienDeploymentMapping;
import mesosphere.marathon.client.utils.ModelUtils;

/**
 * @author Adrian Fraisse
 */
public class DeploymentEventConverterTest {

    private DeploymentEventConverter eventConverter;
    private MappingService mockMappingService;

    // Events from Marathon docs
    private final String deploymentInfoEvent = "{\n" +
            "  \"eventType\": \"deployment_info\",\n" +
            "  \"timestamp\": \"2014-03-01T23:29:30.158Z\",\n" +
            "  \"plan\": {\n" +
            "    \"id\": \"867ed450-f6a8-4d33-9b0e-e11c5513990b\",\n" +
            "    \"original\": {\n" +
            "      \"apps\": [],\n" +
            "      \"dependencies\": [],\n" +
            "      \"groups\": [],\n" +
            "      \"id\": \"/\",\n" +
            "      \"version\": \"2014-09-09T06:30:49.667Z\"\n" +
            "    },\n" +
            "    \"target\": {\n" +
            "      \"apps\": [\n" +
            "        {\n" +
            "          \"args\": [],\n" +
            "          \"backoffFactor\": 1.15,\n" +
            "          \"backoffSeconds\": 1,\n" +
            "          \"cmd\": \"sleep 30\",\n" +
            "          \"constraints\": [],\n" +
            "          \"container\": null,\n" +
            "          \"cpus\": 0.2,\n" +
            "          \"dependencies\": [],\n" +
            "          \"disk\": 0.0,\n" +
            "          \"env\": {},\n" +
            "          \"executor\": \"\",\n" +
            "          \"healthChecks\": [],\n" +
            "          \"id\": \"/my-app\",\n" +
            "          \"instances\": 2,\n" +
            "          \"mem\": 32.0,\n" +
            "          \"ports\": [10001],\n" +
            "          \"requirePorts\": false,\n" +
            "          \"storeUrls\": [],\n" +
            "          \"upgradeStrategy\": {\n" +
            "              \"minimumHealthCapacity\": 1.0\n" +
            "          },\n" +
            "          \"uris\": [],\n" +
            "          \"user\": null,\n" +
            "          \"version\": \"2014-09-09T05:57:50.866Z\"\n" +
            "        }\n" +
            "      ],\n" +
            "      \"dependencies\": [],\n" +
            "      \"groups\": [],\n" +
            "      \"id\": \"/\",\n" +
            "      \"version\": \"2014-09-09T05:57:50.866Z\"\n" +
            "    },\n" +
            "    \"steps\": [\n" +
            "      {\n" +
            "        \"action\": \"ScaleApplication\",\n" +
            "        \"app\": \"/my-app\"\n" +
            "      }\n" +
            "    ],\n" +
            "    \"version\": \"2014-03-01T23:24:14.846Z\"\n" +
            "  },\n" +
            "  \"currentStep\": {\n" +
            "    \"actions\": [\n" +
            "      {\n" +
            "        \"type\": \"ScaleApplication\",\n" +
            "        \"app\": \"/my-app\"\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}";

    private final String deploymentFailedEvent = "{\n" +
            "  \"eventType\": \"deployment_failed\",\n" +
            "  \"timestamp\": \"2014-03-01T23:29:30.158Z\",\n" +
            "  \"id\": \"867ed450-f6a8-4d33-9b0e-e11c5513990b\"\n" +
            "}";

    private final String deploymentSuccessEvent = "{\n" +
            "  \"eventType\": \"deployment_success\",\n" +
            "  \"timestamp\": \"2014-03-01T23:29:30.158Z\",\n" +
            "  \"id\": \"867ed450-f6a8-4d33-9b0e-e11c5513990b\"\n" +
            "}";

    @Before
    public void setUp() {
        mockMappingService = mock(MappingService.class);
        eventConverter = new DeploymentEventConverter(mockMappingService);
    }


    @Test
    public void fromDeploymentInfoEvent() throws Exception {
        DeploymentInfoEvent event = ModelUtils.GSON.fromJson(deploymentInfoEvent, DeploymentInfoEvent.class);
        when(mockMappingService.getAlienDeploymentInfo("867ed450-f6a8-4d33-9b0e-e11c5513990b"))
                .thenReturn(Optional.of(new AlienDeploymentMapping("mock-alien-id", DeploymentStatus.UNDEPLOYMENT_IN_PROGRESS)));

        final PaaSDeploymentStatusMonitorEvent paaSDeploymentStatusMonitorEvent = eventConverter.fromDeploymentInfoEvent(event);
        assertEquals(DeploymentStatus.UNDEPLOYMENT_IN_PROGRESS, paaSDeploymentStatusMonitorEvent.getDeploymentStatus());
        assertEquals("mock-alien-id", paaSDeploymentStatusMonitorEvent.getDeploymentId());
        verify(mockMappingService).getAlienDeploymentInfo("867ed450-f6a8-4d33-9b0e-e11c5513990b");
    }

    @Test
    public void fromDeploymentFailedEvent() throws Exception {
        DeploymentFailedEvent event = ModelUtils.GSON.fromJson(deploymentFailedEvent, DeploymentFailedEvent.class);

        when(mockMappingService.getAlienDeploymentInfo("867ed450-f6a8-4d33-9b0e-e11c5513990b"))
                .thenReturn(Optional.of(new AlienDeploymentMapping("mock-alien-id", DeploymentStatus.DEPLOYMENT_IN_PROGRESS)));

        final PaaSDeploymentStatusMonitorEvent paaSDeploymentStatusMonitorEvent = eventConverter.fromDeploymentFailedEvent(event);
        assertEquals(DeploymentStatus.FAILURE, paaSDeploymentStatusMonitorEvent.getDeploymentStatus());
        assertEquals("mock-alien-id", paaSDeploymentStatusMonitorEvent.getDeploymentId());
        verify(mockMappingService).getAlienDeploymentInfo("867ed450-f6a8-4d33-9b0e-e11c5513990b");
        verify(mockMappingService).removeAlienDeploymentInfo("867ed450-f6a8-4d33-9b0e-e11c5513990b");
    }

    @Test
    public void fromDeploymentSuccessEventAfterDeployment() throws Exception {
        DeploymentSuccessEvent event = ModelUtils.GSON.fromJson(deploymentSuccessEvent, DeploymentSuccessEvent.class);

        when(mockMappingService.getAlienDeploymentInfo("867ed450-f6a8-4d33-9b0e-e11c5513990b"))
                .thenReturn(Optional.of(new AlienDeploymentMapping("mock-alien-id", DeploymentStatus.DEPLOYMENT_IN_PROGRESS)));

        final PaaSDeploymentStatusMonitorEvent deployedEvent = eventConverter.fromDeploymentSuccessEvent(event);
        assertEquals(DeploymentStatus.DEPLOYED, deployedEvent.getDeploymentStatus());
        assertEquals("mock-alien-id", deployedEvent.getDeploymentId());
        verify(mockMappingService).getAlienDeploymentInfo("867ed450-f6a8-4d33-9b0e-e11c5513990b");
        verify(mockMappingService).removeAlienDeploymentInfo("867ed450-f6a8-4d33-9b0e-e11c5513990b");
    }

    @Test
    public void fromDeploymentSuccessEventAfterUndeployment() throws Exception {
        DeploymentSuccessEvent event = ModelUtils.GSON.fromJson(deploymentSuccessEvent, DeploymentSuccessEvent.class);

        when(mockMappingService.getAlienDeploymentInfo("867ed450-f6a8-4d33-9b0e-e11c5513990b"))
                .thenReturn(Optional.of(new AlienDeploymentMapping("mock-alien-id", DeploymentStatus.UNDEPLOYMENT_IN_PROGRESS)));

        final PaaSDeploymentStatusMonitorEvent undeployedEvent = eventConverter.fromDeploymentSuccessEvent(event);
        assertEquals(DeploymentStatus.UNDEPLOYED, undeployedEvent.getDeploymentStatus());
        assertEquals("mock-alien-id", undeployedEvent.getDeploymentId());
        verify(mockMappingService).getAlienDeploymentInfo("867ed450-f6a8-4d33-9b0e-e11c5513990b");
        verify(mockMappingService).removeAlienDeploymentInfo("867ed450-f6a8-4d33-9b0e-e11c5513990b");
    }

    @Test
    public void createMonitorEvent() throws Exception {
        AbstractDeploymentEvent event = ModelUtils.GSON.fromJson(deploymentInfoEvent, DeploymentInfoEvent.class);

        when(mockMappingService.getAlienDeploymentInfo("867ed450-f6a8-4d33-9b0e-e11c5513990b"))
                .thenReturn(Optional.of(new AlienDeploymentMapping("mock-alien-id", DeploymentStatus.UNDEPLOYMENT_IN_PROGRESS)));

        final PaaSDeploymentStatusMonitorEvent monitorEvent = eventConverter.fromMarathonEvent(event);
        assertEquals(DeploymentStatus.UNDEPLOYMENT_IN_PROGRESS, monitorEvent.getDeploymentStatus());
        assertEquals("mock-alien-id", monitorEvent.getDeploymentId());
        assertEquals(1393716570158L, monitorEvent.getDate()); // 01/03/2014 - 23:29:30+158 GMT
    }

}