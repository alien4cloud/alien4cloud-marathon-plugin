package alien4cloud.plugin.marathon.service.model.events.deployments;

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
public class DeploymentFailedEvent extends AbstractDeploymentEvent {
    private String id;
}
