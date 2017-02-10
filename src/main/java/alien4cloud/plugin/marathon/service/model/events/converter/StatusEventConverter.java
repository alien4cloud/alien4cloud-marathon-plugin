package alien4cloud.plugin.marathon.service.model.events.converter;

import java.util.Optional;

import org.springframework.stereotype.Service;

import alien4cloud.paas.model.InstanceStatus;
import alien4cloud.paas.model.PaaSInstanceStateMonitorEvent;
import alien4cloud.plugin.marathon.service.MarathonMappingService;
import alien4cloud.plugin.marathon.service.model.events.status.AbstractStatusEvent;
import alien4cloud.plugin.marathon.service.model.events.status.HealthStatusChangedEvent;
import alien4cloud.plugin.marathon.service.model.events.status.StatusUpdateEvent;
import alien4cloud.plugin.marathon.service.model.mapping.MarathonAppsMapping;

/**
 * Converts Marathon task status related events into <code>PaaSInstanceStateMonitorEvent</code>.
 *
 * @author Adrian Fraisse
 */
@Service
public class StatusEventConverter extends AbstractEventConverter<AbstractStatusEvent, PaaSInstanceStateMonitorEvent> {

    public StatusEventConverter(MarathonMappingService mappingService) {
        super(mappingService);
    }

    @Override
    protected PaaSInstanceStateMonitorEvent fromMarathonEvent(AbstractStatusEvent marathonEvent) {
        final PaaSInstanceStateMonitorEvent monitorEvent = super.fromMarathonEvent(marathonEvent);

        // Retrieve deployment id and nodetemplate id from marathon app id (== /paasDeploymentId/nodetemplateid lower cased)
        final String[] fullAppId = marathonEvent.getAppId().split("/");
        final String groupId = fullAppId[1];
        final String appId = fullAppId[2]; // This will throw for apps not started with alien, ex is silently caught afterwards

        final Optional<MarathonAppsMapping> appMapping = getMappingService().getMarathonAppMapping(groupId);
        monitorEvent.setDeploymentId(appMapping.map(MarathonAppsMapping::getAlienDeploymentId).orElse("UNKNOWN_DEPLOYMENT"));
        monitorEvent.setNodeTemplateId(appMapping.map(mapping -> mapping.getNodeTemplateId(appId)).orElse("UNKNOWN_NODE"));
        monitorEvent.setInstanceId(marathonEvent.getTaskId());
        return monitorEvent;
    }


    public PaaSInstanceStateMonitorEvent fromStatusUpdateEvent(StatusUpdateEvent marathonEvent) {
        final PaaSInstanceStateMonitorEvent instanceStateMonitorEvent = this.fromMarathonEvent(marathonEvent);

        switch (marathonEvent.getTaskStatus()) {
            case "TASK_STARTING":
                instanceStateMonitorEvent.setInstanceState("starting");
                instanceStateMonitorEvent.setInstanceStatus(InstanceStatus.PROCESSING);
                break;
            case "TASK_RUNNING":
                instanceStateMonitorEvent.setInstanceState("started");
                instanceStateMonitorEvent.setInstanceStatus(InstanceStatus.SUCCESS);
                break;
            case "TASK_STAGING":
                instanceStateMonitorEvent.setInstanceState("creating");
                instanceStateMonitorEvent.setInstanceStatus(InstanceStatus.PROCESSING);
                break;
            case "TASK_ERROR":
            case "TASK_LOST" :
                instanceStateMonitorEvent.setInstanceState("stopped");
                instanceStateMonitorEvent.setInstanceStatus(InstanceStatus.FAILURE);
                break;
            case "TASK_KILLED":
                instanceStateMonitorEvent.setInstanceState("deleted");
                instanceStateMonitorEvent.setInstanceStatus(InstanceStatus.MAINTENANCE);
            default:
                instanceStateMonitorEvent.setInstanceStatus(InstanceStatus.PROCESSING);
        }
        return instanceStateMonitorEvent;
    }

    public PaaSInstanceStateMonitorEvent fromHealthStatusChangedEvent(HealthStatusChangedEvent marathonEvent) {
        final PaaSInstanceStateMonitorEvent instanceStateMonitorEvent = fromMarathonEvent(marathonEvent);

        if (marathonEvent.isAlive()) {
            instanceStateMonitorEvent.setInstanceState("started");
            instanceStateMonitorEvent.setInstanceStatus(InstanceStatus.SUCCESS);
        } else {
            instanceStateMonitorEvent.setInstanceStatus(InstanceStatus.FAILURE);
        }
        return instanceStateMonitorEvent;
    }

    @Override
    protected PaaSInstanceStateMonitorEvent createMonitorEvent() {
        return new PaaSInstanceStateMonitorEvent();
    }

}
