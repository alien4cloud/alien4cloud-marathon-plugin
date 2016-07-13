package alien4cloud.plugin.marathon.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import alien4cloud.paas.model.AbstractMonitorEvent;

/**
 * @author Adrian Fraisse
 */
class EventCache {

    private long lastAccessed;
    private List<AbstractMonitorEvent> events = Collections.synchronizedList(new ArrayList<>());

    public EventCache(final long cacheCleanupInterval) {

        if (cacheCleanupInterval > 0) Executors.newSingleThreadExecutor().submit(() -> {
            while (true) {
                Thread.sleep(cacheCleanupInterval * 1000);
                cleanUp();
            }
        });
    }

    // TODO: leverage the fact that the event list is always sorted
    private void cleanUp() {
        synchronized (events) {
            events = events.stream()
                        .filter(event -> event.getDate() < lastAccessed)
                        .collect(Collectors.toList());
        }
    }

    protected List<AbstractMonitorEvent> getEventsSince(long date, int batchSize) {
        synchronized (events) {
            List<AbstractMonitorEvent> subList =
                    events.stream().filter(event -> event.getDate() > date).limit(batchSize).collect(Collectors.toList());
            this.lastAccessed = date;
            return subList;
        }
    }

    protected void pushEvent(AbstractMonitorEvent event) {
        synchronized (events) {
            events.add(event);
        }
    }
}
