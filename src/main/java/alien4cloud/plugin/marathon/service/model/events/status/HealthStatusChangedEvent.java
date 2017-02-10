package alien4cloud.plugin.marathon.service.model.events.status;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * @author Adrian Fraisse
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class HealthStatusChangedEvent extends AbstractStatusEvent {
    private boolean alive;
}
