package alien4cloud.plugin.marathon.service.model.events;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * @author Adrian Fraisse
 */
@Setter
@Getter
@NoArgsConstructor
@ToString
public abstract class AbstractEvent {

    private String eventType;
    private String timestamp;
}
