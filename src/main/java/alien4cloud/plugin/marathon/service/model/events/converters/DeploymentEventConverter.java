package alien4cloud.plugin.marathon.service.model.events.converters;

import org.springframework.stereotype.Service;

import alien4cloud.paas.model.DeploymentStatus;
import alien4cloud.paas.model.PaaSDeploymentStatusMonitorEvent;
import alien4cloud.plugin.marathon.service.MarathonMappingService;
import alien4cloud.plugin.marathon.service.model.events.deployments.AbstractDeploymentEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentFailedEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentInfoEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentSuccessEvent;
import alien4cloud.plugin.marathon.service.model.mapping.AlienDeploymentMapping;

/**
 * Converts Marathon deployment-related events into <code>PaaSDeploymentStatusMonitorEvents</code>.
 *
 * @author Adrian Fraisse
 */
@Service
public class DeploymentEventConverter extends AbstractEventConverter<AbstractDeploymentEvent, PaaSDeploymentStatusMonitorEvent> {

    public DeploymentEventConverter(MarathonMappingService mappingService) {
        super(mappingService);
    }

    @Override
    protected PaaSDeploymentStatusMonitorEvent fromMarathonEvent(AbstractDeploymentEvent marathonEvent) {
        final PaaSDeploymentStatusMonitorEvent paaSDeploymentStatusMonitorEvent = super.fromMarathonEvent(marathonEvent);

        // Determine if this is a Deployment or an Undeployment (Marathon doesn't make the difference)
        final AlienDeploymentMapping deploymentMapping = getMappingService().getAlienDeploymentInfo(marathonEvent.getId()).orElse(AlienDeploymentMapping.EMPTY);
        paaSDeploymentStatusMonitorEvent.setDeploymentId(deploymentMapping.getAlienDeploymentId());
        paaSDeploymentStatusMonitorEvent.setDeploymentStatus(deploymentMapping.getStatus());

        return paaSDeploymentStatusMonitorEvent;
    }

    public PaaSDeploymentStatusMonitorEvent fromDeploymentInfoEvent(DeploymentInfoEvent marathonEvent) {
        return this.fromMarathonEvent(marathonEvent);
    }

    public PaaSDeploymentStatusMonitorEvent fromDeploymentFailedEvent(DeploymentFailedEvent marathonEvent) {
        final PaaSDeploymentStatusMonitorEvent paaSDeploymentStatusMonitorEvent = this.fromMarathonEvent(marathonEvent);

        paaSDeploymentStatusMonitorEvent.setDeploymentStatus(DeploymentStatus.FAILURE);
        getMappingService().removeAlienDeploymentInfo(marathonEvent.getId());
        return paaSDeploymentStatusMonitorEvent;
    }

    public PaaSDeploymentStatusMonitorEvent fromDeploymentSuccessEvent(DeploymentSuccessEvent marathonEvent) {
        final PaaSDeploymentStatusMonitorEvent paaSDeploymentStatusMonitorEvent = this.fromMarathonEvent(marathonEvent);

        // Determine if this is the end of a Deployment or an undeployment
        switch (paaSDeploymentStatusMonitorEvent.getDeploymentStatus()) {
            case DEPLOYMENT_IN_PROGRESS:
                paaSDeploymentStatusMonitorEvent.setDeploymentStatus(DeploymentStatus.DEPLOYED);
                break;
            case UNDEPLOYMENT_IN_PROGRESS:
                paaSDeploymentStatusMonitorEvent.setDeploymentStatus(DeploymentStatus.UNDEPLOYED);
                break;
            default:
                paaSDeploymentStatusMonitorEvent.setDeploymentStatus(DeploymentStatus.UNKNOWN);
        }

        getMappingService().removeAlienDeploymentInfo(marathonEvent.getId());
        return paaSDeploymentStatusMonitorEvent;
    }

    @Override
    protected PaaSDeploymentStatusMonitorEvent createMonitorEvent() {
        return new PaaSDeploymentStatusMonitorEvent();
    }

}
