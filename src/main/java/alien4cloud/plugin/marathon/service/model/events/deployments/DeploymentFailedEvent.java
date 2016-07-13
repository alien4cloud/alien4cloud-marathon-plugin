package alien4cloud.plugin.marathon.service.model.events.deployments;

import alien4cloud.plugin.marathon.service.model.events.AbstractEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * @author Adrian Fraisse
 */
@Setter
@Getter
@NoArgsConstructor
public class DeploymentFailedEvent extends AbstractEvent {
    private String id;
}
