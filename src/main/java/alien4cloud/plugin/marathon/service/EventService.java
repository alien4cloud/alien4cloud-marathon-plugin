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
import alien4cloud.plugin.marathon.service.model.events.AbstractEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentFailedEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentInfoEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentSuccessEvent;
import alien4cloud.plugin.marathon.service.model.events.status.HealthStatusChangedEvent;
import alien4cloud.plugin.marathon.service.model.events.status.StatusUpdateEvent;
import alien4cloud.plugin.marathon.service.model.events.status.UnhealthyTaskKillEvent;
import lombok.extern.log4j.Log4j;
import mesosphere.marathon.client.utils.ModelUtils;

/**
 * @author Adrian Fraisse
 */
@Log4j
public class EventService {

    private EventCache eventCache;
    private final EventSource eventSource;
    private final Map<String, AlienDeploymentInfo> deploymentMap;
    private final SimpleDateFormat dateFormat;

    public EventService(String apiURL) {
        this.eventCache = new EventCache(15);
        this.deploymentMap = Maps.newConcurrentMap();
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

        // Setup an Event listener connected to Marathon's EventBus
        Client client = ClientBuilder.newBuilder().register(SseFeature.class).build();
        WebTarget target = client.target(apiURL.concat("/events"));

        this.eventSource = EventSource.target(target).build();
        EventListener listener = inboundEvent -> {
            this.parseMarathonEvent(inboundEvent.getName(), inboundEvent.readData(String.class));
        };

        eventSource.register(listener, "status_update_event", "deployment_success", "deployment_info", "deployment_failed", "health_status_changed_event", "unhealthy_task_kill_event");
        if (!eventSource.isOpen()) eventSource.open();
    }

    /**
     * TODO
     * @param name
     * @param data
     * @return
     */
    private void parseMarathonEvent(String name, String data) {
        AbstractEvent parsedEvent;
        PaaSDeploymentStatusMonitorEvent pdsme = null;
        switch (name) {
            case "status_update_event":
                parsedEvent = ModelUtils.GSON.fromJson(data, StatusUpdateEvent.class);
                log.info("[Event from marathon]: " + parsedEvent.toString());
                break;
            case "deployment_info":
                final DeploymentInfoEvent event = ModelUtils.GSON.fromJson(data, DeploymentInfoEvent.class);
                log.info("[Event from marathon]: " + event.toString());

                pdsme = new PaaSDeploymentStatusMonitorEvent();
                try {
                    pdsme.setDate(dateFormat.parse(event.getTimestamp()).getTime() + 7200000);
                } catch (ParseException e) {
                    log.error("Unable to parse event time from Marathon", e);
                    pdsme.setDate(new Date().getTime());
                }
                pdsme.setDeploymentStatus(deploymentMap.get(event.getPlan().getId()).getStatus());
                pdsme.setDeploymentId(deploymentMap.get(event.getPlan().getId()).getAlienId());

                this.eventCache.pushEvent(pdsme);
                break;
            case "deployment_success":
                parsedEvent = ModelUtils.GSON.fromJson(data, DeploymentSuccessEvent.class);

                log.info("[Event from marathon]: " + parsedEvent.toString());
                pdsme = new PaaSDeploymentStatusMonitorEvent();
                try {
                    final Date parse = dateFormat.parse(parsedEvent.getTimestamp());
                    pdsme.setDate(parse.getTime() + 7200000);
                    log.info("Parsed date : " +parse.toString());
                } catch (ParseException e) {
                    log.error("Unable to parse event time from Marathon", e);
                    pdsme.setDate(new Date().getTime());
                }

                pdsme.setDeploymentId(deploymentMap.get(((DeploymentSuccessEvent) parsedEvent).getId()).getAlienId());
                switch (deploymentMap.get(((DeploymentSuccessEvent) parsedEvent).getId()).getStatus()) {
                    case DEPLOYMENT_IN_PROGRESS:
                        pdsme.setDeploymentStatus(DeploymentStatus.DEPLOYED);
                        break;
                    case UNDEPLOYMENT_IN_PROGRESS:
                        pdsme.setDeploymentStatus(DeploymentStatus.UNDEPLOYED);
                        break;
                    default:
                        pdsme.setDeploymentStatus(DeploymentStatus.UNKNOWN);
                }

                this.eventCache.pushEvent(pdsme);
                break;
            case "deployment_failed":
                parsedEvent = ModelUtils.GSON.fromJson(data, DeploymentFailedEvent.class);
                log.info("[Event from marathon]: " + parsedEvent.toString());

                pdsme = new PaaSDeploymentStatusMonitorEvent();
                try {
                    pdsme.setDate(dateFormat.parse(parsedEvent.getTimestamp()).getTime() + 7200000);
                } catch (ParseException e) {
                    log.error("Unable to parse event time from Marathon", e);
                    pdsme.setDate(new Date().getTime());
                }
                pdsme.setDeploymentStatus(DeploymentStatus.FAILURE);
                pdsme.setDeploymentId(deploymentMap.get(((DeploymentFailedEvent) parsedEvent).getId()).getAlienId());

                this.eventCache.pushEvent(pdsme);
                break;
            case "health_status_changed_event":
                parsedEvent = ModelUtils.GSON.fromJson(data, HealthStatusChangedEvent.class);
                break;
            case "unhealthy_task_kill_event":
                parsedEvent = ModelUtils.GSON.fromJson(data, UnhealthyTaskKillEvent.class);
                break;
            default:
                log.warn("Unknown ["+ name + "] event received from Marathon with data : " + data);
        }

    }

    public void registerDeployment(String marathonDeploymentId, String alienDeploymentId, DeploymentStatus status) {
        this.deploymentMap.put(marathonDeploymentId, new AlienDeploymentInfo(alienDeploymentId, status));
    }

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
