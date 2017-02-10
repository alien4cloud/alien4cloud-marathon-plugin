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
public class UnhealthyTaskKillEvent extends AbstractStatusEvent {
    private String reason;
    private String host;
    private String slaveId;
}
