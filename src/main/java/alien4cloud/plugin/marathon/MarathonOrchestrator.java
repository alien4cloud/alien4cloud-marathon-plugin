package alien4cloud.plugin.marathon;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;

import org.glassfish.jersey.media.sse.EventListener;
import org.glassfish.jersey.media.sse.EventSource;
import org.glassfish.jersey.media.sse.SseFeature;
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
import mesosphere.marathon.client.model.v2.App;
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
        EventListener listener = inboundEvent -> log.info("[Event from marathon] : { name:" + inboundEvent.getName() + ", data: " + inboundEvent.readData(String.class) + " }");
        eventSource.register(listener, "status_update_event", "group_change_success", "group_change_failed", "deployment_success", "deployment_info", "deployment_failed", "health_status_changed_event", "failed_health_check_event");
        if (!eventSource.isOpen()) {
            eventSource.open();
        }
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
        // This is java 8 ! TODO: Throw exception in lambdas ?
        try {
            // Retrieve the group
            final String groupID = paaSDeploymentContext.getDeploymentPaaSId().toLowerCase();
            DeploymentStatus status = Optional.of(marathonClient.getGroup(groupID))
                .map(group -> {
                    // Get all running deployments
                    try {
                        return marathonClient.getDeployments().stream() // Retrieve deployments
                            .filter(deploy ->
                                // If any deployment affects an app from the group, then it means the group is undertaking deployment
                                deploy.getAffectedApps().stream().anyMatch(s -> s.matches("^//" + groupID + "//"))
                            ).findFirst()
                                .map(deployment -> // We got a deployment - check if it is deploying or undeploying an application group
                                    deployment.getCurrentActions()
                                    .stream()
                                    .noneMatch(action -> // All actions but StopApplication reflect a deployment in progress
                                            action.getAction().matches("^StopApplication$")
                                    ) ? DeploymentStatus.DEPLOYMENT_IN_PROGRESS : DeploymentStatus.UNDEPLOYMENT_IN_PROGRESS
                                ).orElseGet(() -> {
                                    // There is no deployment but the group exists in Marathon : Check app states
                                    // First, we retrieve the Apps info from marathon
                                    List<App> appInfo = Lists.newArrayList();
                                    group.getApps().forEach(app -> {
                                        try {
                                            appInfo.add(marathonClient.getApp(app.getId()).getApp());
                                        } catch (MarathonException e) {
                                            log.error("Failure reaching for apps");
                                            // Continue looking
                                        }
                                    });
                                    // Then check task status for each app
                                    if (appInfo.stream().map(App::getTasksUnhealthy).reduce(Integer::sum).orElse(0) > 0)
                                        return DeploymentStatus.FAILURE; // If any of the Tasks is unhealthy, then consider the topology to be failing
                                    else
                                        return DeploymentStatus.DEPLOYED;
                                });
                    } catch (MarathonException e) {
                        log.error("Failure reaching for deployments");
                        iPaaSCallback.onFailure(e);
                    }
                    return DeploymentStatus.DEPLOYMENT_IN_PROGRESS;
                }).orElse(DeploymentStatus.UNDEPLOYED);
            // Finally, delegate to callback !
            iPaaSCallback.onSuccess(status);
        } catch (MarathonException e) {
            switch (e.getStatus()) {
            case 404 : // If 404 then the group was not found on Marathon
                iPaaSCallback.onSuccess(DeploymentStatus.UNDEPLOYED);
                break;
            default: iPaaSCallback.onFailure(e);
            }
            log.error("Unable to reach Marathon");
            iPaaSCallback.onFailure(e);
        }
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
