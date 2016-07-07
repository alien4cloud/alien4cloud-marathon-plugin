package alien4cloud.plugin.marathon;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;

import org.glassfish.jersey.media.sse.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.common.collect.Lists;

import alien4cloud.orchestrators.plugin.ILocationConfiguratorPlugin;
import alien4cloud.orchestrators.plugin.IOrchestratorPlugin;
import alien4cloud.orchestrators.plugin.model.PluginArchive;
import alien4cloud.paas.IPaaSCallback;
import alien4cloud.paas.exception.MaintenanceModeException;
import alien4cloud.paas.exception.OperationExecutionException;
import alien4cloud.paas.exception.PluginConfigurationException;
import alien4cloud.paas.model.*;
import alien4cloud.plugin.marathon.config.MarathonConfig;
import alien4cloud.plugin.marathon.location.MarathonLocationConfiguratorFactory;
import alien4cloud.plugin.marathon.service.MarathonMappingService;
import lombok.extern.slf4j.Slf4j;
import mesosphere.marathon.client.Marathon;
import mesosphere.marathon.client.MarathonClient;
import mesosphere.marathon.client.model.v2.Group;
import mesosphere.marathon.client.model.v2.Result;
import mesosphere.marathon.client.utils.MarathonException;

/**
 * @author Adrian Fraisse
 */
@Slf4j
@Component
public class MarathonOrchestrator implements IOrchestratorPlugin<MarathonConfig> {

    private MarathonConfig config;

    @Autowired
    private MarathonMappingService marathonMappingService;

    private Marathon marathonClient;

    private boolean deployed = false;

    @Inject
    private MarathonLocationConfiguratorFactory marathonLocationConfiguratorFactory;
    private EventSource eventSource;

    @Override
    public ILocationConfiguratorPlugin getConfigurator(String locationType) {
        return marathonLocationConfiguratorFactory.newInstance(locationType);
    }

    @Override
    public List<PluginArchive> pluginArchives() {
        // TODO: ship docker types with orchestrator
        return Lists.newArrayList();
    }

    @Override
    public void setConfiguration(MarathonConfig marathonConfig) throws PluginConfigurationException {
        this.config = marathonConfig;
        marathonClient = MarathonClient.getInstance(config.getMarathonURL());
        Client client = ClientBuilder.newBuilder().register(SseFeature.class).build();
        WebTarget target = client.target(config.getMarathonURL().concat("/v2/events"));
        eventSource = new EventSource(target);
        EventListener listener = inboundEvent -> log.debug("[Event from marathon] : { name:" + inboundEvent.getName() + ", data: " + inboundEvent.readData(String.class) + " }");
        eventSource.register(listener, "event_stream_attached", "status_update_event", "group_change_success", "deployment_success", "deployment_info");
        eventSource.open();
    }

    @Override
    public void init(Map<String, PaaSTopologyDeploymentContext> map) {
    }

    @Override
    public void deploy(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext, IPaaSCallback<?> iPaaSCallback) {
        Group group = marathonMappingService.buildGroupDefinition(paaSTopologyDeploymentContext);
        try {
            Result result = marathonClient.createGroup(group);
            log.debug(result.toString());
            deployed = true;
        } catch (MarathonException e) {
            log.error("Got HTTP error response : " + e.getStatus());
            log.error("With body : " + e.getMessage());
            e.printStackTrace();
            // TODO deal with status response
        }

        // No callback
    }

    @Override
    public void undeploy(PaaSDeploymentContext paaSDeploymentContext, IPaaSCallback<?> iPaaSCallback) {
        try {
            Result result = marathonClient.deleteGroup(paaSDeploymentContext.getDeploymentPaaSId().toLowerCase());
            log.debug(result.toString());
        } catch (MarathonException e) {
            log.error("undeploy : " + e.getMessage());
            e.printStackTrace();
        }
        deployed = false;
        iPaaSCallback.onSuccess(null);
    }

    @Override
    public void scale(PaaSDeploymentContext paaSDeploymentContext, String s, int i, IPaaSCallback<?> iPaaSCallback) {

    }

    @Override
    public void launchWorkflow(PaaSDeploymentContext paaSDeploymentContext, String s, Map<String, Object> map, IPaaSCallback<?> iPaaSCallback) {

    }

    @Override
    public void getStatus(PaaSDeploymentContext paaSDeploymentContext, IPaaSCallback<DeploymentStatus> iPaaSCallback) {
        // Récupération du groupe, ou vérification du déploiement ? Subscription a l'event stream ?
        if (deployed) iPaaSCallback.onSuccess(DeploymentStatus.DEPLOYED);
        else iPaaSCallback.onSuccess(DeploymentStatus.UNDEPLOYED);
    }

    @Override
    public void getInstancesInformation(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext, IPaaSCallback<Map<String, Map<String, InstanceInformation>>> iPaaSCallback) {

    }

    @Override
    public void getEventsSince(Date date, int i, IPaaSCallback<AbstractMonitorEvent[]> iPaaSCallback) {
        System.out.println("");
    }

    @Override
    public void executeOperation(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext, NodeOperationExecRequest nodeOperationExecRequest, IPaaSCallback<Map<String, String>> iPaaSCallback) throws OperationExecutionException {

    }

    @Override
    public void switchMaintenanceMode(PaaSDeploymentContext paaSDeploymentContext, boolean b) throws MaintenanceModeException {

    }

    @Override
    public void switchInstanceMaintenanceMode(PaaSDeploymentContext paaSDeploymentContext, String s, String s1, boolean b) throws MaintenanceModeException {

    }
}
