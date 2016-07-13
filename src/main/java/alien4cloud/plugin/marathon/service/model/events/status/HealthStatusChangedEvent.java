package alien4cloud.plugin.marathon.service.model.events.status;

import alien4cloud.plugin.marathon.service.model.events.AbstractEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * @author Adrian Fraisse
 */
@Getter
@Setter
@NoArgsConstructor
public class HealthStatusChangedEvent extends AbstractEvent {
    private String appId;
    private String taskId;
    private boolean alive;
}
