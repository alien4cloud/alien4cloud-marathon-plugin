package alien4cloud.plugin.marathon.service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;

import org.glassfish.jersey.media.sse.EventListener;
import org.glassfish.jersey.media.sse.EventSource;
import org.glassfish.jersey.media.sse.SseFeature;

import com.google.common.collect.Maps;

import alien4cloud.paas.model.AbstractMonitorEvent;
import alien4cloud.paas.model.DeploymentStatus;
import alien4cloud.paas.model.PaaSDeploymentStatusMonitorEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentFailedEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentInfoEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentSuccessEvent;
import alien4cloud.plugin.marathon.service.model.events.status.HealthStatusChangedEvent;
import alien4cloud.plugin.marathon.service.model.events.status.StatusUpdateEvent;
import alien4cloud.plugin.marathon.service.model.events.status.UnhealthyTaskKillEvent;
import lombok.extern.log4j.Log4j;
import mesosphere.marathon.client.utils.ModelUtils;

/**
 * Service to listen to the Marathon events stream
 *
 * @author Adrian Fraisse
 */
@Log4j
public class EventService {

    /**
     * A cache to store events
     */
    private EventCache eventCache;

    /**
     * Map Alien deployment ids to marathon deployment ids
     */
    private final Map<String, AlienDeploymentInfo> deploymentMap = Maps.newConcurrentMap();

    /**
     * Date format of Marathon's events. FIXME: deal with the 2 hours offset
     */
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    public EventService(String apiURL) {
        this.eventCache = new EventCache(15);

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

        switch (name) {
            case "deployment_info":
                this.eventCache.pushEvent(parseDeploymentInfoEvent(data));
                break;
            case "deployment_success":
                this.eventCache.pushEvent(parseDeploymentSuccessEvent(data));
                break;
            case "deployment_failed":
                this.eventCache.pushEvent(parseDeploymentFailedEvent(data));
                break;
            case "status_update_event":
                // TODO: Implement status update events
                final StatusUpdateEvent statusUpdateEvent = ModelUtils.GSON.fromJson(data, StatusUpdateEvent.class);
                log.info("[Event from marathon]: " + statusUpdateEvent.toString());
                break;
            case "health_status_changed_event":
                // TODO: Implement health status changed events
                final HealthStatusChangedEvent healthStatusChangedEvent = ModelUtils.GSON.fromJson(data, HealthStatusChangedEvent.class);
                log.info("[Event from marathon]: " + healthStatusChangedEvent.toString());
                break;
            case "unhealthy_task_kill_event":
                // TODO: Implement unhealthy task kill events
                final UnhealthyTaskKillEvent unhealthyTaskKillEvent = ModelUtils.GSON.fromJson(data, UnhealthyTaskKillEvent.class);
                log.info("[Event from marathon]: " + unhealthyTaskKillEvent.toString());
                break;
            default:
                log.warn("Unknown ["+ name + "] event received from Marathon with data : " + data);
        }

    }

    /**
     * Parse a deployment_failed event
     * @param data the raw JSON String
     * @return a PaaSDeploymentStatusMonitorEvent
     */
    private PaaSDeploymentStatusMonitorEvent parseDeploymentFailedEvent(String data) {
        final DeploymentFailedEvent deploymentFailedEvent = ModelUtils.GSON.fromJson(data, DeploymentFailedEvent.class);
        log.info("[Event from marathon]: " + deploymentFailedEvent.toString());

        final PaaSDeploymentStatusMonitorEvent pdsme = new PaaSDeploymentStatusMonitorEvent();
        try {
            pdsme.setDate(dateFormat.parse(deploymentFailedEvent.getTimestamp()).getTime() + 7200000); // FIXME
        } catch (ParseException e) {
            log.error("Unable to parse event time from Marathon", e);
            pdsme.setDate(new Date().getTime());
        }
        pdsme.setDeploymentStatus(DeploymentStatus.FAILURE);
        pdsme.setDeploymentId(deploymentMap.get(deploymentFailedEvent.getId()).getAlienId());

        this.deploymentMap.remove(pdsme.getDeploymentId()); // clean the deployment map.
        return pdsme;
    }

    /**
     * Parse a deployment_success event
     * @param data the raw JSON String
     * @return a PaaSDeploymentStatusMonitorEvent
     */
    private PaaSDeploymentStatusMonitorEvent parseDeploymentSuccessEvent(String data) {
        final DeploymentSuccessEvent deploymentSuccessEvent = ModelUtils.GSON.fromJson(data, DeploymentSuccessEvent.class);
        log.info("[Event from marathon]: " + deploymentSuccessEvent.toString());

        final PaaSDeploymentStatusMonitorEvent pdsme = new PaaSDeploymentStatusMonitorEvent();
        try {
            pdsme.setDate(dateFormat.parse(deploymentSuccessEvent.getTimestamp()).getTime() + 7200000); // FIXME
        } catch (ParseException e) {
            log.error("Unable to parse event time from Marathon", e);
            pdsme.setDate(new Date().getTime());
        }

        // Determine if this is the end of a Deployment or an Undeployment
        pdsme.setDeploymentId(deploymentMap.get(deploymentSuccessEvent.getId()).getAlienId());
        switch (deploymentMap.get(deploymentSuccessEvent.getId()).getStatus()) {
            case DEPLOYMENT_IN_PROGRESS:
                pdsme.setDeploymentStatus(DeploymentStatus.DEPLOYED);
                break;
            case UNDEPLOYMENT_IN_PROGRESS:
                pdsme.setDeploymentStatus(DeploymentStatus.UNDEPLOYED);
                break;
            default:
                pdsme.setDeploymentStatus(DeploymentStatus.UNKNOWN);
        }
        this.deploymentMap.remove(pdsme.getDeploymentId()); // clean the deployment map.
        return pdsme;
    }

    /**
     * Parse a deployment_failed event
     * @param data the raw JSON String
     * @return a PaaSDeploymentStatusMonitorEvent
     */
    private PaaSDeploymentStatusMonitorEvent parseDeploymentInfoEvent(String data) {
        final DeploymentInfoEvent deploymentInfoEvent = ModelUtils.GSON.fromJson(data, DeploymentInfoEvent.class);
        log.info("[Event from marathon]: " + deploymentInfoEvent.toString());

        final PaaSDeploymentStatusMonitorEvent pdsme = new PaaSDeploymentStatusMonitorEvent();
        try {
            pdsme.setDate(dateFormat.parse(deploymentInfoEvent.getTimestamp()).getTime() + 7200000); // FIXME
        } catch (ParseException e) {
            log.error("Unable to parse event time from Marathon", e);
            pdsme.setDate(new Date().getTime());
        }
        // Determine if this is a Deployment or an Undeployment (Marathon doesn't make the difference)
        pdsme.setDeploymentStatus(deploymentMap.get(deploymentInfoEvent.getPlan().getId()).getStatus());
        pdsme.setDeploymentId(deploymentMap.get(deploymentInfoEvent.getPlan().getId()).getAlienId());
        return pdsme;
    }

    /**
     * Register a running deployment into the EventService.
     * @param marathonDeploymentId the id of the deployment in Marathon
     * @param alienDeploymentId the id of the deployment in Alien
     * @param status The initial status of the deployment
     */
    public void registerDeployment(String marathonDeploymentId, String alienDeploymentId, DeploymentStatus status) {
        this.deploymentMap.put(marathonDeploymentId, new AlienDeploymentInfo(alienDeploymentId, status));
    }

    /**
     * Returns events from a given date.
     * @param date The earliest date the method should pull events froms
     * @param batchSize Max array list size to return
     * @return A List of AbstractMonitorEvents
     */
    public List<AbstractMonitorEvent> getEventsSince(Date date, int batchSize) {
        return this.eventCache.getEventsSince(date.getTime(), batchSize);
    }

    /**
     * Utility class to store mapping between an AlienDeployment and Marathon.
     */
    private class AlienDeploymentInfo {
        private String alienId;
        private DeploymentStatus status;

        AlienDeploymentInfo(String alienId, DeploymentStatus status) {
            this.alienId = alienId;
            this.status = status;
        }

        String getAlienId() {
            return alienId;
        }

        DeploymentStatus getStatus() {
            return status;
        }

    }

}
