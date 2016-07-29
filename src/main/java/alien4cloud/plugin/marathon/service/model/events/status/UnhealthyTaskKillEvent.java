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
public class UnhealthyTaskKillEvent extends AbstractEvent {
    private String appId;
    private String taskId;
    private String reason;
    private String host;
    private String slaveId;
}
