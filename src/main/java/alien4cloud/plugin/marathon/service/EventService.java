package alien4cloud.plugin.marathon.service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;

import org.glassfish.jersey.media.sse.EventSource;
import org.glassfish.jersey.media.sse.SseFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import alien4cloud.paas.model.AbstractMonitorEvent;
import alien4cloud.plugin.marathon.service.model.events.converters.DeploymentEventConverter;
import alien4cloud.plugin.marathon.service.model.events.converters.StatusEventConverter;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentFailedEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentInfoEvent;
import alien4cloud.plugin.marathon.service.model.events.deployments.DeploymentSuccessEvent;
import alien4cloud.plugin.marathon.service.model.events.status.HealthStatusChangedEvent;
import alien4cloud.plugin.marathon.service.model.events.status.StatusUpdateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mesosphere.marathon.client.utils.ModelUtils;

/**
 * Service to listen to Marathon's event stream
 *
 * @author Adrian Fraisse
 */
@Service
@Slf4j
@RequiredArgsConstructor(onConstructor=@__(@Autowired))
public class EventService {

    private final DeploymentEventConverter deploymentEventConverter;

    private final StatusEventConverter statusEventConverter;

    private final Queue<AbstractMonitorEvent> eventQueue = new LinkedList<>();;

    /**
     * Subscribe to Marathon event stream.
     * @param apiURL Marathon's api url.
     */
    public void subscribe(String apiURL) {
        // Setup an Event listener connected to Marathon's EventBus
        Client client = ClientBuilder.newBuilder().register(SseFeature.class).build();
        WebTarget target = client.target(apiURL.concat("/events"));

        /* Jersey SSE client */
        EventSource eventSource = EventSource.target(target).build();

        /* Register events listeners */
        eventSource.register(inboundEvent -> eventQueue.add(statusEventConverter.fromStatusUpdateEvent(
                ModelUtils.GSON.fromJson(inboundEvent.readData(String.class), StatusUpdateEvent.class)))
            , "status_update_event");

        eventSource.register(inboundEvent -> eventQueue.add(statusEventConverter.fromHealthStatusChangedEvent(
                ModelUtils.GSON.fromJson(inboundEvent.readData(String.class), HealthStatusChangedEvent.class)))
            , "health_status_changed_event");

        eventSource.register(inboundEvent -> eventQueue.add(deploymentEventConverter.fromDeploymentSuccessEvent(
                ModelUtils.GSON.fromJson(inboundEvent.readData(String.class), DeploymentSuccessEvent.class)))
            , "deployment_success");

        eventSource.register(inboundEvent -> eventQueue.add(deploymentEventConverter.fromDeploymentFailedEvent(
                ModelUtils.GSON.fromJson(inboundEvent.readData(String.class), DeploymentFailedEvent.class)))
            , "deployment_failed");

        eventSource.register(inboundEvent -> eventQueue.add(deploymentEventConverter.fromDeploymentInfoEvent(
                ModelUtils.GSON.fromJson(inboundEvent.readData(String.class), DeploymentInfoEvent.class)))
            , "deployment_info");

        if (!eventSource.isOpen()) eventSource.open();
    }

    /**
     * Poll all events from Marathon then flush the Queue.
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
