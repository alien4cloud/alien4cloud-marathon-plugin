package alien4cloud.plugin.marathon.service.model.events.status;

import alien4cloud.plugin.marathon.service.model.events.AbstractEvent;

/**
 * @author Adrian Fraisse
 */
public class UnhealthyTaskKillEvent extends AbstractEvent {
    private String appId;
    private String taskId;
    private String reason;
    private String host;
    private String slaveId;
}
