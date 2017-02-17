package alien4cloud.plugin.marathon.service.model.events.deployments;

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
public class DeploymentSuccessEvent extends AbstractDeploymentEvent {
    private String id;
}
