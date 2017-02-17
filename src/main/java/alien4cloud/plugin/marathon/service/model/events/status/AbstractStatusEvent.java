package alien4cloud.plugin.marathon.service.model.events.status;

import alien4cloud.plugin.marathon.service.model.events.AbstractEvent;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * @author Adrian Fraisse
 */
@Getter
@Setter
@ToString
public abstract class AbstractStatusEvent extends AbstractEvent {
    private String appId;
    private String taskId;
}
