package alien4cloud.plugin.marathon.service.model.events.status;

import alien4cloud.plugin.marathon.service.model.events.AbstractEvent;
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
public class StatusUpdateEvent extends AbstractEvent {
    private String slaveId;
    private String taskId;
    private String taskStatus;
    private String appId;
    private String host;
}
