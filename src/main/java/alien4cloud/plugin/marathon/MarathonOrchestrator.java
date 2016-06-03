package alien4cloud.plugin.marathon;

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
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import mesosphere.marathon.client.Marathon;
import mesosphere.marathon.client.MarathonClient;
import mesosphere.marathon.client.model.v2.App;
import mesosphere.marathon.client.utils.MarathonException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @author Adrian Fraisse
 */
@Slf4j
@Component
public class MarathonOrchestrator implements IOrchestratorPlugin<MarathonConfig> {

    @Autowired
    private MarathonMappingService marathonMappingService;

    private Marathon marathonClient;

    private boolean deployed = false;

    @Inject
    private MarathonLocationConfiguratorFactory marathonLocationConfiguratorFactory;

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
        marathonClient = MarathonClient.getInstance(marathonConfig.getMarathonURL());
    }

    @Override
    public void init(Map<String, PaaSTopologyDeploymentContext> map) {

    }

    @Override
    public void deploy(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext, IPaaSCallback<?> iPaaSCallback) {
        App appDef = marathonMappingService.buildAppDefinition(paaSTopologyDeploymentContext);
        try {
            appDef = marathonClient.createApp(appDef);
            deployed = true;
        } catch (MarathonException e) {
            log.error("Got HTTP error response : " + e.getStatus());
            log.error("With body : " + e.getMessage());

            // TODO deal with status response
        }

        // No callback
    }

    @Override
    public void undeploy(PaaSDeploymentContext paaSDeploymentContext, IPaaSCallback<?> iPaaSCallback) {
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
        if (deployed) iPaaSCallback.onSuccess(DeploymentStatus.DEPLOYED);
        else iPaaSCallback.onSuccess(DeploymentStatus.UNDEPLOYED);
    }

    @Override
    public void getInstancesInformation(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext, IPaaSCallback<Map<String, Map<String, InstanceInformation>>> iPaaSCallback) {

    }

    @Override
    public void getEventsSince(Date date, int i, IPaaSCallback<AbstractMonitorEvent[]> iPaaSCallback) {

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
