package alien4cloud.plugin.marathon;

import java.util.*;

import javax.inject.Inject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.common.base.Functions;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

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
import alien4cloud.plugin.marathon.service.EventService;
import alien4cloud.plugin.marathon.service.MarathonMappingService;
import lombok.extern.slf4j.Slf4j;
import mesosphere.marathon.client.Marathon;
import mesosphere.marathon.client.MarathonClient;
import mesosphere.marathon.client.model.v2.*;
import mesosphere.marathon.client.utils.MarathonException;

/**
 * @author Adrian Fraisse
 */
@Slf4j
@Component
public class MarathonOrchestrator implements IOrchestratorPlugin<MarathonConfig> {

    @Autowired
    private MarathonMappingService marathonMappingService;

    private EventService eventService;

    private Marathon marathonClient;

    @Inject
    private MarathonLocationConfiguratorFactory marathonLocationConfiguratorFactory;

    @Override
    public ILocationConfiguratorPlugin getConfigurator(String locationType) {
        return marathonLocationConfiguratorFactory.newInstance(locationType);
    }

    @Override
    public List<PluginArchive> pluginArchives() {
        return Lists.newArrayList();
    }

    @Override
    public void setConfiguration(MarathonConfig marathonConfig) throws PluginConfigurationException {
        marathonClient = MarathonClient.getInstance(marathonConfig.getMarathonURL());
        eventService = new EventService(marathonConfig.getMarathonURL().concat("/v2"));
    }

    @Override
    public void init(Map<String, PaaSTopologyDeploymentContext> map) {
    }

    @Override
    public void deploy(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext, IPaaSCallback<?> iPaaSCallback) {
        Group group = marathonMappingService.buildGroupDefinition(paaSTopologyDeploymentContext);
        try {
            Result result = marathonClient.createGroup(group);
            this.eventService.registerDeployment(result.getDeploymentId(), paaSTopologyDeploymentContext.getDeploymentId(), DeploymentStatus.DEPLOYMENT_IN_PROGRESS);
            // Store the deployment ID to handle event mapping
        } catch (MarathonException e) {
            log.error("Failure while deploying - Got error code ["+e.getStatus()+"] with message: " + e.getMessage());
            // TODO deal with status response
        }
        // No callback
    }

    @Override
    public void undeploy(PaaSDeploymentContext paaSDeploymentContext, IPaaSCallback<?> iPaaSCallback) {
        try {
            Result result = marathonClient.deleteGroup(paaSDeploymentContext.getDeploymentPaaSId().toLowerCase());
            this.eventService.registerDeployment(result.getDeploymentId(), paaSDeploymentContext.getDeploymentId(), DeploymentStatus.UNDEPLOYMENT_IN_PROGRESS);
        } catch (MarathonException e) {
            log.error("undeploy : " + e.getMessage());
            e.printStackTrace();
        }
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
        final String groupID = paaSDeploymentContext.getDeploymentPaaSId().toLowerCase();
        try {
            DeploymentStatus status = Optional.of(marathonClient.getGroup(groupID)) // Retrieve the application group of this topology
                    .map(this::getTopologyDeploymentStatus).orElse(DeploymentStatus.UNDEPLOYED); // Check its status
            // Finally, delegate to callback !
            iPaaSCallback.onSuccess(status);
        } catch (MarathonException e) {
            switch (e.getStatus()) {
                case 404 : // If 404 then the group was not found on Marathon
                    iPaaSCallback.onSuccess(DeploymentStatus.UNDEPLOYED);
                    break;
                default: // Other codes are errors
                    log.error("Unable to reach Marathon - Got error code ["+e.getStatus()+"] with message: " + e.getMessage());
                    iPaaSCallback.onFailure(e);
            }
        } catch (RuntimeException e) {
            iPaaSCallback.onFailure(e.getCause());
        }
    }

    /**
     * Retrieves the status of a Topology which has already been deployed on Marathon.
     * @param group The topology's Marathon group
     * @return A <code>DeploymentStatus</code> representing the state of the topology in Marathon
     * @throws RuntimeException Any exception while reaching Marathon.
     */
    private DeploymentStatus getTopologyDeploymentStatus(Group group) throws RuntimeException {
        try {
            return marathonClient.getDeployments().stream() // Retrieve deployments
                    .filter(deployment ->
                            // If any deployment affects an app from the group, then it means the group is undertaking deployment
                            deployment.getAffectedApps().stream().anyMatch(s -> s.matches("^//" + group.getId() + "//"))
                    ).findFirst()
                    .map(this::getRunningDeploymentStatus) // A deployment matches - check if it is deploying or undeploying
                    .orElseGet(() -> getDeployedTopologyStatus(group));// No deployment but the group exists in Marathon => the topology is deployed, check states
        } catch (MarathonException e) {
            log.error("Failure reaching for deployments - Got error code ["+e.getStatus()+"] with message: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Given a running Deployment, returns if it is actually deploying or un-deploying a topology.
     * @param deployment A running deployment on Marathon.
     * @return <code>DeploymentStatus.DEPLOYMENT_IN_PROGRESS</code> or <code>DeploymentStatus.UNDEPLOYMENT_IN_PROGRESS</code>.
     */
    private DeploymentStatus getRunningDeploymentStatus(Deployment deployment) {
        return deployment.getCurrentActions()
                .stream()
                .noneMatch(action -> // All actions but StopApplication reflect a deployment in progress
                        action.getType().matches("^StopApplication$")
                ) ? DeploymentStatus.DEPLOYMENT_IN_PROGRESS : DeploymentStatus.UNDEPLOYMENT_IN_PROGRESS;
    }

    /**
     * Given a deployed topology, get its status.
     * @param group The Marathon application group.
     * @return <code>DeploymentStatus.DEPLOYED</code> if all apps are healthy or <code>DeploymentStatus.FAILURE</code> if not.
     */
    private DeploymentStatus getDeployedTopologyStatus(Group group) throws RuntimeException {
        // First, we retrieve the Apps info from marathon
        List<App> appInfo = Lists.newArrayList();
        group.getApps().forEach(app -> {
            try {
                appInfo.add(marathonClient.getApp(app.getId()).getApp());
            } catch (MarathonException e) {
                log.error("Failure reaching for apps - Got error code ["+e.getStatus()+"] with message: " + e.getMessage());
                switch (e.getStatus()) {
                case 404:
                    break;
                    // Continue checking for apps
                default:
                    throw new RuntimeException(e);
                }
            }
        });

        // Then check task status for each app
        if (appInfo.size() < group.getApps().size() && appInfo.stream().map(App::getTasksUnhealthy).reduce(Integer::sum).orElse(0) > 0)
            return DeploymentStatus.FAILURE; // If any of the Tasks is unhealthy, then consider the topology to be failing
        else
            return DeploymentStatus.DEPLOYED;
    }

    @Override
    public void getInstancesInformation(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext, IPaaSCallback<Map<String, Map<String, InstanceInformation>>> iPaaSCallback) {
        Map<String, Map<String, InstanceInformation>> topologyInfo = Maps.newHashMap();

        final String groupID = paaSTopologyDeploymentContext.getDeploymentPaaSId().toLowerCase();
        // For each app query Marathon for its tasks status
        paaSTopologyDeploymentContext.getPaaSTopology().getNonNatives().forEach(paaSNodeTemplate -> {
            Map<String, InstanceInformation> instancesInfo = Maps.newHashMap();
            final String appID = groupID + "/" + paaSNodeTemplate.getId().toLowerCase();

            try {
                final Collection<Task> tasks = marathonClient.getAppTasks(appID).getTasks();

                tasks.forEach(task -> {
                    final Map<String, String> runtimeProps = Maps.newHashMap();
                    final Collection<String> ports = Collections2.transform(task.getPorts(), Functions.toStringFunction());

                    // Outputs Marathon endpoints as host:port1,port2, ...
                    runtimeProps.put("endpoint",
                            "http://".concat(task.getHost().concat(":").concat(String.join(",", ports))));

                    InstanceStatus instanceStatus;
                    String state;

                    // Leverage Mesos's TASK_STATUS
                    switch (task.getState()) {
                        case "TASK_RUNNING":
                            // Retrieve health checks results - if no healthcheck then success
                            state = "started";
                            instanceStatus =
                                Optional.ofNullable(task.getHealthCheckResults())
                                    .map(healthCheckResults ->
                                        healthCheckResults
                                            .stream()
                                            .findFirst()
                                            .map(HealthCheckResult::isAlive)
                                            .map(alive -> alive ? InstanceStatus.SUCCESS : InstanceStatus.FAILURE)
                                        .orElse(InstanceStatus.PROCESSING)
                                    ).orElse(InstanceStatus.SUCCESS);
                            break;
                        case "TASK_STARTING":
                            state = "starting";
                            instanceStatus = InstanceStatus.PROCESSING;
                            break;
                        case "TASK_STAGING":
                            state = "creating";
                            instanceStatus = InstanceStatus.PROCESSING;
                            break;
                        case "TASK_ERROR":
                            state = "stopped";
                            instanceStatus = InstanceStatus.FAILURE;
                            break;
                        default:
                            state = "uninitialized";
                            instanceStatus = InstanceStatus.PROCESSING;
                    }

                    instancesInfo.put(task.getId(), new InstanceInformation(state, instanceStatus, runtimeProps, runtimeProps, Maps.newHashMap()));
                });
                topologyInfo.put(paaSNodeTemplate.getId(), instancesInfo);
            } catch (MarathonException e) {
                switch (e.getStatus()) {
                    case 404: // The app cannot be found in marathon - we display no information
                        break;
                    default:
                        iPaaSCallback.onFailure(e);
                }
            }
        });
        iPaaSCallback.onSuccess(topologyInfo);
    }

    @Override
    public void getEventsSince(Date date, int i, IPaaSCallback<AbstractMonitorEvent[]> iPaaSCallback) {
        final List<AbstractMonitorEvent> eventList = this.eventService.getEventsSince(date, i);
        iPaaSCallback.onSuccess(eventList.toArray(new AbstractMonitorEvent[eventList.size()]));
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
