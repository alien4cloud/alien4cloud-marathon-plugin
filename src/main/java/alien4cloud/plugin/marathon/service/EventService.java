package alien4cloud.plugin.marathon.service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;

import org.glassfish.jersey.media.sse.EventListener;
import org.glassfish.jersey.media.sse.EventSource;
import org.glassfish.jersey.media.sse.SseFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import alien4cloud.paas.model.*;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentFailedEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentInfoEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentSuccessEvent;
import alien4cloud.plugin.marathon.service.model.events.status.HealthStatusChangedEvent;
import alien4cloud.plugin.marathon.service.model.events.status.StatusUpdateEvent;
import alien4cloud.plugin.marathon.service.model.events.status.UnhealthyTaskKillEvent;
import alien4cloud.plugin.marathon.service.model.mapping.AlienDeploymentMapping;
import lombok.extern.log4j.Log4j;
import mesosphere.marathon.client.utils.ModelUtils;

/**
 * Service to listen to the Marathon events stream
 *
 * @author Adrian Fraisse
 */
@Service
@Log4j
public class EventService {

    @Autowired
    private MarathonMappingService mappingService;

    /**
     * Event queue
     */
    private final Queue<AbstractMonitorEvent> eventQueue;

    /**
     * Date format of Marathon's events.
     */
    private final SimpleDateFormat dateFormat;

    EventService() {
        // The event cache
        eventQueue = new LinkedList<>();
        dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public void subscribe(String apiURL) {
        // Setup an Event listener connected to Marathon's EventBus
        Client client = ClientBuilder.newBuilder().register(SseFeature.class).build();
        WebTarget target = client.target(apiURL.concat("/events"));

        /* Jersey SSE client */
        EventSource eventSource = EventSource.target(target).build();
        EventListener listener = inboundEvent -> this.parseMarathonEvent(inboundEvent.getName(), inboundEvent.readData(String.class));

        // TODO: Implement more events
        eventSource.register(
                listener,
                "status_update_event",
                "deployment_success", "deployment_info", "deployment_failed",
                "health_status_changed_event", "unhealthy_task_kill_event"
        );
        if (!eventSource.isOpen()) eventSource.open();
    }

    /**
     * Parse a Marathon SSE event and pushes it into the EventCache.
     * @param name The name of the event
     * @param data The JSON data associated with the event
     */
    private void parseMarathonEvent(String name, String data) {
        log.warn("[Event from marathon]: " + name + " : " + data);
        switch (name) {
            case "deployment_info":
                eventQueue.add(parseDeploymentInfoEvent(data));
                break;
            case "deployment_success":
                eventQueue.add(parseDeploymentSuccessEvent(data));
                break;
            case "deployment_failed":
                eventQueue.add(parseDeploymentFailedEvent(data));
                break;
            case "status_update_event":
                eventQueue.add(parseStatusUpdateEvent(ModelUtils.GSON.fromJson(data, StatusUpdateEvent.class)));
                break;
            case "health_status_changed_event":
                eventQueue.add(parseHealthStatusChangedEvent(ModelUtils.GSON.fromJson(data, HealthStatusChangedEvent.class)));
                break;
            case "unhealthy_task_kill_event":
                // TODO: Implement unhealthy task kill events - notify user that the task is going to be killed ?
                final UnhealthyTaskKillEvent unhealthyTaskKillEvent = ModelUtils.GSON.fromJson(data, UnhealthyTaskKillEvent.class);
                break;
            default:
                log.warn("Unknown ["+ name + "] event received from Marathon with data : " + data);
        }

    }

    private PaaSInstanceStateMonitorEvent parseStatusUpdateEvent(StatusUpdateEvent statusUpdateEvent) {
        final PaaSInstanceStateMonitorEvent instanceStateMonitorEvent = new PaaSInstanceStateMonitorEvent();
        try {
            instanceStateMonitorEvent.setDate(dateFormat.parse(statusUpdateEvent.getTimestamp()).getTime());
        } catch (ParseException e) {
            log.error("Unable to parse event time from Marathon", e);
            instanceStateMonitorEvent.setDate(new Date().getTime());
        }

        // Retrieve deployment id and nodetemplate id from marathon app id (== /paasDeploymentId/nodetemplateid lower cased)
        final String[] fullAppId = statusUpdateEvent.getAppId().split("/");
        final String groupId = fullAppId[1];
        final String appId = fullAppId[2]; // This may throw for apps not started with alien, and that's ok because ex is silently caught afterwards

        mappingService.getMarathonAppMapping(groupId).ifPresent(marathonAppsMapping -> {
            instanceStateMonitorEvent.setDeploymentId(marathonAppsMapping.getAlienDeploymentId());
            instanceStateMonitorEvent.setNodeTemplateId(marathonAppsMapping.getNodeTemplateId(appId));
        });

        instanceStateMonitorEvent.setInstanceId(statusUpdateEvent.getTaskId());
        instanceStateMonitorEvent.setInstanceState(statusUpdateEvent.getTaskStatus());
        switch (statusUpdateEvent.getTaskStatus()) {
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

    private PaaSInstanceStateMonitorEvent parseHealthStatusChangedEvent(HealthStatusChangedEvent healthStatusChangedEvent) {
        final PaaSInstanceStateMonitorEvent instanceStateMonitorEvent = new PaaSInstanceStateMonitorEvent();
        try {
            instanceStateMonitorEvent.setDate(dateFormat.parse(healthStatusChangedEvent.getTimestamp()).getTime());
        } catch (ParseException e) {
            log.error("Unable to parse event time from Marathon", e);
            instanceStateMonitorEvent.setDate(new Date().getTime());
        }

        // Retrieve deployment id and nodetemplate id from marathon app id (== /paasDeploymentId/nodetemplateid lower cased)
        final String[] fullAppId = healthStatusChangedEvent.getAppId().split("/");
        final String groupId = fullAppId[1];
        final String appId = fullAppId[2]; // This will throw for apps not started with alien, ex is silently caught afterwards

        mappingService.getMarathonAppMapping(groupId).ifPresent(marathonAppsMapping -> {
            instanceStateMonitorEvent.setDeploymentId(marathonAppsMapping.getAlienDeploymentId());
            instanceStateMonitorEvent.setNodeTemplateId(marathonAppsMapping.getNodeTemplateId(appId));
        });

        if (healthStatusChangedEvent.isAlive()) {
            instanceStateMonitorEvent.setInstanceState("started");
            instanceStateMonitorEvent.setInstanceStatus(InstanceStatus.SUCCESS);
        } else {
            instanceStateMonitorEvent.setInstanceStatus(InstanceStatus.FAILURE);
        }

        return instanceStateMonitorEvent;
    }

    /**
     * Parse a deployment_failed event
     * @param data the raw JSON String
     * @return a PaaSDeploymentStatusMonitorEvent
     */
    private PaaSDeploymentStatusMonitorEvent parseDeploymentFailedEvent(String data) {
        final DeploymentFailedEvent deploymentFailedEvent = ModelUtils.GSON.fromJson(data, DeploymentFailedEvent.class);

        final PaaSDeploymentStatusMonitorEvent pdsme = new PaaSDeploymentStatusMonitorEvent();
        try {
            pdsme.setDate(dateFormat.parse(deploymentFailedEvent.getTimestamp()).getTime());
        } catch (ParseException e) {
            log.error("Unable to parse event time from Marathon", e);
            pdsme.setDate(new Date().getTime());
        }
        pdsme.setDeploymentStatus(DeploymentStatus.FAILURE);
        // Marathon deployment is over - remove mapping
        pdsme.setDeploymentId(
                mappingService.getAndRemoveAlienDeploymentInfo(deploymentFailedEvent.getId())
                        .map(AlienDeploymentMapping::getAlienDeploymentId).orElse(AlienDeploymentMapping.EMPTY.getAlienDeploymentId())
        );

        return pdsme;
    }

    /**
     * Parse a deployment_success event
     * @param data the raw JSON String
     * @return a PaaSDeploymentStatusMonitorEvent
     */
    private PaaSDeploymentStatusMonitorEvent parseDeploymentSuccessEvent(String data) {
        final DeploymentSuccessEvent deploymentSuccessEvent = ModelUtils.GSON.fromJson(data, DeploymentSuccessEvent.class);

        final PaaSDeploymentStatusMonitorEvent pdsme = new PaaSDeploymentStatusMonitorEvent();
        try {
            pdsme.setDate(dateFormat.parse(deploymentSuccessEvent.getTimestamp()).getTime());
        } catch (ParseException e) {
            log.error("Unable to parse event time from Marathon", e);
            pdsme.setDate(new Date().getTime());
        }

        // Marathon deployment is over - remove mapping
        AlienDeploymentMapping deploymentInfo = mappingService.getAndRemoveAlienDeploymentInfo(deploymentSuccessEvent.getId()).orElse(AlienDeploymentMapping.EMPTY);
        pdsme.setDeploymentId(deploymentInfo.getAlienDeploymentId());
        // Determine if this is the end of a Deployment or an undeployment
        switch (deploymentInfo.getStatus()) {
            case DEPLOYMENT_IN_PROGRESS:
                pdsme.setDeploymentStatus(DeploymentStatus.DEPLOYED);
                break;
            case UNDEPLOYMENT_IN_PROGRESS:
                pdsme.setDeploymentStatus(DeploymentStatus.UNDEPLOYED);
                break;
            default:
                pdsme.setDeploymentStatus(DeploymentStatus.UNKNOWN);
        }
        return pdsme;
    }

    /**
     * Parse a deployment_info event
     * @param data the raw JSON String
     * @return a PaaSDeploymentStatusMonitorEvent
     */
    private PaaSDeploymentStatusMonitorEvent parseDeploymentInfoEvent(String data) {
        final DeploymentInfoEvent deploymentInfoEvent = ModelUtils.GSON.fromJson(data, DeploymentInfoEvent.class);

        final PaaSDeploymentStatusMonitorEvent pdsme = new PaaSDeploymentStatusMonitorEvent();
        try {
            pdsme.setDate(dateFormat.parse(deploymentInfoEvent.getTimestamp()).getTime());
        } catch (ParseException e) {
            log.error("Unable to parse event time from Marathon", e);
            pdsme.setDate(new Date().getTime());
        }
        // Determine if this is a Deployment or an Undeployment (Marathon doesn't make the difference)
        final AlienDeploymentMapping deploymentMapping = mappingService.getAlienDeploymentInfo(deploymentInfoEvent.getPlan().getId()).orElse(AlienDeploymentMapping.EMPTY);
        pdsme.setDeploymentStatus(deploymentMapping.getStatus());
        pdsme.setDeploymentId(deploymentMapping.getAlienDeploymentId());
        return pdsme;
    }

    /**
     * Takes all events from Marathon then flush the Queue.
     * @return All events in the Queue.
     */
    public AbstractMonitorEvent[] flushEvents() {
        ArrayList<AbstractMonitorEvent> events = new ArrayList<>(eventQueue.size());
        AbstractMonitorEvent e;
        while (( e = eventQueue.poll()) != null) {
            events.add(e);
        }
        return events.toArray(new AbstractMonitorEvent[events.size()]);
    }

}
