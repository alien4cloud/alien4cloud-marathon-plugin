package alien4cloud.plugin.marathon;

import static com.google.common.collect.Maps.newHashMap;
import static java.util.Collections.emptyMap;

import java.util.*;

import javax.inject.Inject;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.google.common.base.Functions;
import com.google.common.collect.Collections2;
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
import alien4cloud.plugin.marathon.service.EventService;
import alien4cloud.plugin.marathon.service.MarathonBuilderService;
import alien4cloud.plugin.marathon.service.MarathonMappingService;
import alien4cloud.utils.MapUtil;
import lombok.extern.slf4j.Slf4j;
import mesosphere.marathon.client.Marathon;
import mesosphere.marathon.client.MarathonClient;
import mesosphere.marathon.client.model.v2.*;
import mesosphere.marathon.client.utils.MarathonException;

/**
 * The Marathon orchestrator implementation.
 *
 * @author Adrian Fraisse
 */
@Slf4j
@Component
@RequiredArgsConstructor(onConstructor=@__(@Autowired))
@Scope("prototype")
public class MarathonOrchestrator implements IOrchestratorPlugin<MarathonConfig> {

    private final @NonNull MarathonBuilderService marathonBuilderService;

    private final @NonNull MarathonMappingService marathonMappingService;

    private final @NonNull EventService eventService;

    private @NonNull MarathonLocationConfiguratorFactory marathonLocationConfiguratorFactory;

    private Marathon marathonClient;

    @Override
    public void setConfiguration(MarathonConfig marathonConfig) throws PluginConfigurationException {
        // Set up the connexion to Marathon
        marathonClient = MarathonClient.getInstance(marathonConfig.getMarathonURL());
        eventService.subscribe(marathonConfig.getMarathonURL().concat("/v2"));
    }

    @Override
    public void init(Map<String, PaaSTopologyDeploymentContext> activeDeployments) {
        // Init mapping
        marathonMappingService.init(activeDeployments.values());
    }

    @Override
    public void deploy(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext, IPaaSCallback<?> iPaaSCallback) {
        Group group = marathonBuilderService.buildGroupDefinition(paaSTopologyDeploymentContext);
        try {
            Result result = marathonClient.createGroup(group);
            // Store the deployment ID to handle event mapping
            marathonMappingService.registerDeploymentInfo(result.getDeploymentId(), paaSTopologyDeploymentContext.getDeploymentId(), DeploymentStatus.DEPLOYMENT_IN_PROGRESS);
        } catch (MarathonException e) {
            log.error("Failure while deploying - Got error code ["+e.getStatus()+"] with message: " + e.getMessage());
        }
        // No callback
    }

    @Override
    public void undeploy(PaaSDeploymentContext paaSDeploymentContext, IPaaSCallback<?> iPaaSCallback) {
        // TODO: Add force option in Marathon-client to always force undeployment - better : cancel running deployment
        try {
            Result result = marathonClient.deleteGroup(paaSDeploymentContext.getDeploymentPaaSId().toLowerCase());
            marathonMappingService.registerDeploymentInfo(result.getDeploymentId(), paaSDeploymentContext.getDeploymentId(), DeploymentStatus.UNDEPLOYMENT_IN_PROGRESS);
        } catch (MarathonException e) {
            log.error("Failure while undeploying - Got error code ["+e.getStatus()+"] with message: " + e.getMessage());
            iPaaSCallback.onFailure(e);
        }
        iPaaSCallback.onSuccess(null);
    }

    @Override
    public void getStatus(PaaSDeploymentContext paaSDeploymentContext, IPaaSCallback<DeploymentStatus> iPaaSCallback) {
        final String groupID = paaSDeploymentContext.getDeploymentPaaSId().toLowerCase();
        try {
            DeploymentStatus status = Optional.ofNullable(marathonClient.getGroup(groupID)) // Retrieve the application group of this topology
                    .map(this::getTopologyDeploymentStatus).orElse(DeploymentStatus.UNDEPLOYED); // Check its status
            // Finally, delegate to callback
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
        final Map<String, Map<String, InstanceInformation>> topologyInfo = newHashMap();

        final String groupID = paaSTopologyDeploymentContext.getDeploymentPaaSId().toLowerCase();
        // For each app query Marathon for its tasks
        paaSTopologyDeploymentContext.getPaaSTopology().getNonNatives().forEach(paaSNodeTemplate -> {
            Map<String, InstanceInformation> instancesInfo = newHashMap();
            final String appID = groupID + "/" + paaSNodeTemplate.getId().toLowerCase();

            try {
                // Marathon tasks are alien instances
                final Collection<Task> tasks = marathonClient.getAppTasks(appID).getTasks();
                tasks.forEach(task -> {
                    final InstanceInformation instanceInformation = this.getInstanceInformation(task);
                    instancesInfo.put(task.getId(), instanceInformation);
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
        paaSTopologyDeploymentContext.getPaaSTopology().getVolumes().forEach(volumeTemplate-> {
            // Volumes have the same state than their app
            final InstanceInformation volumeInstanceInfo =
                volumeTemplate.getRelationshipTemplates().stream()
                    .filter(paaSRelationshipTemplate -> "alien.relationships.MountDockerVolume".equals(paaSRelationshipTemplate.getTemplate().getType()))
                    .findFirst() // Retrieve the node this volume is attached to
                    .map(paaSRelationshipTemplate -> paaSRelationshipTemplate.getTemplate().getTarget())
                    .map(topologyInfo::get)  // Retrieve the InstanceInformation map of the node the volume is attached to
                    .flatMap(instancesInfoMap -> // Use any instance of the node as base for the volume's InstanceInformation
                            instancesInfoMap.entrySet().stream().findAny().map(instanceInfoEntry ->
                                    new InstanceInformation(instanceInfoEntry.getValue().getState(), instanceInfoEntry.getValue().getInstanceStatus(), emptyMap(), emptyMap(), emptyMap())
                            )
                    ).orElse(new InstanceInformation("uninitialized", InstanceStatus.PROCESSING, emptyMap(), emptyMap(), emptyMap()));
            topologyInfo.put(volumeTemplate.getId(), MapUtil.newHashMap(new String[] {volumeTemplate.getId()}, new InstanceInformation[] {volumeInstanceInfo}));
        });
        iPaaSCallback.onSuccess(topologyInfo);
    }

    /**
     * Get instance information, eg. status and runtime properties, from a Marathon Task.
     * @param task A Marathon Task
     * @return An InstanceInformation
     */
    private InstanceInformation getInstanceInformation(Task task) {
        final Map<String, String> runtimeProps = newHashMap();

        // Outputs Marathon endpoints as host:port1,port2, ...
        final Collection<String> ports = Collections2.transform(task.getPorts(), Functions.toStringFunction());
        runtimeProps.put("endpoint",
                "http://".concat(task.getHost().concat(":").concat(String.join(",", ports))));

        InstanceStatus instanceStatus;
        String state;

        // Leverage Mesos's TASK_STATUS - TODO: add Mesos 1.0 task states
        switch (task.getState()) {
            case "TASK_RUNNING":
                state = "started";
                // Retrieve health checks results - if no healthcheck then assume healthy
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
                state = "uninitialized"; // Unknown
                instanceStatus = InstanceStatus.PROCESSING;
        }

        return new InstanceInformation(state, instanceStatus, runtimeProps, runtimeProps, newHashMap());
    }

    @Override
    public void getEventsSince(Date date, int i, IPaaSCallback<AbstractMonitorEvent[]> iPaaSCallback) {
        iPaaSCallback.onSuccess(eventService.flushEvents());
    }

    @Override
    public void executeOperation(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext, NodeOperationExecRequest nodeOperationExecRequest, IPaaSCallback<Map<String, String>> iPaaSCallback) throws OperationExecutionException {}

    @Override
    public ILocationConfiguratorPlugin getConfigurator(String locationType) {
        return marathonLocationConfiguratorFactory.newInstance(locationType);
    }

    @Override
    public List<PluginArchive> pluginArchives() {
        return Collections.emptyList();
    }

    @Override
    public void switchMaintenanceMode(PaaSDeploymentContext paaSDeploymentContext, boolean b) throws MaintenanceModeException {

    }

    @Override
    public void switchInstanceMaintenanceMode(PaaSDeploymentContext paaSDeploymentContext, String s, String s1, boolean b) throws MaintenanceModeException {

    }

    @Override
    public void scale(PaaSDeploymentContext paaSDeploymentContext, String nodeTemplateId, int instances, IPaaSCallback<?> iPaaSCallback) {
        String appId = paaSDeploymentContext.getDeploymentPaaSId().toLowerCase() + "/" + nodeTemplateId.toLowerCase();
        try {
            // retrieve the app
            Optional.ofNullable(marathonClient.getApp(appId)).map(GetAppResponse::getApp).map(App::getInstances).ifPresent(currentInstances -> {
                currentInstances += instances;
                App app = new App();
                app.setInstances(currentInstances);
                try {
                    marathonClient.updateApp(appId, app, true);
                    iPaaSCallback.onSuccess(null);
                } catch (MarathonException e) {
                    log.error("Failure while scaling - Got error code ["+e.getStatus()+"] with message: " + e.getMessage());
                    iPaaSCallback.onFailure(e);
                }
            });

        } catch (MarathonException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void launchWorkflow(PaaSDeploymentContext paaSDeploymentContext, String s, Map<String, Object> map, IPaaSCallback<?> iPaaSCallback) {}
}
