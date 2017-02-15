package alien4cloud.plugin.marathon.service.model.events.converters;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.springframework.beans.factory.annotation.Autowired;

import alien4cloud.paas.model.AbstractMonitorEvent;
import alien4cloud.plugin.marathon.service.MappingService;
import alien4cloud.plugin.marathon.service.model.events.AbstractEvent;
import lombok.extern.log4j.Log4j;

/**
 * Converts Marathon events into <code>AbstractMonitorEvents</code>.
 *
 * @author Adrian Fraisse
 */
@Log4j
public abstract class AbstractEventConverter<T extends AbstractEvent, U extends AbstractMonitorEvent> {

    private final MappingService mappingService;
    /**
     * Date format of Marathon's events.
     */
    private final SimpleDateFormat dateFormat;

    @Autowired
    protected AbstractEventConverter(MappingService mappingService) {
        this.mappingService = mappingService;
        dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    protected abstract U createMonitorEvent();

    protected U fromMarathonEvent(T marathonEvent) {
        final U monitorEvent = createMonitorEvent();

        try {
            monitorEvent.setDate(dateFormat.parse(marathonEvent.getTimestamp()).getTime());
        } catch (ParseException e) {
            log.error("Unable to parse event time from Marathon", e);
            monitorEvent.setDate(new Date().getTime());
        }
        return monitorEvent;
    }

    protected MappingService getMappingService() {
        return mappingService;
    }

}
