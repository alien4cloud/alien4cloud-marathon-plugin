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
public class DeploymentInfoEvent extends AbstractDeploymentEvent {
    private Plan plan;

    @Override
    public String getId() {
        return this.plan.getId();
    }

    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    public class Plan {
        private String id;
        // NB: We do not need more than the deployment id atm
    }
}
