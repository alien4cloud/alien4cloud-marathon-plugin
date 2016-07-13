package alien4cloud.plugin.marathon.service.model.events.deployments;

import alien4cloud.plugin.marathon.service.model.events.AbstractEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * @author Adrian Fraisse
 */
@Getter
@Setter
@NoArgsConstructor
public class DeploymentInfoEvent extends AbstractEvent {

    private Plan plan;

    public class Plan {
        private String id;

        public Plan() {}

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }
}
