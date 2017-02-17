package alien4cloud.plugin.marathon.service.model.mapping;

import alien4cloud.paas.model.DeploymentStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Utility class to store mapping between an AlienDeployment and Marathon.
 */
@Getter
@AllArgsConstructor
public class AlienDeploymentMapping {

    public static final AlienDeploymentMapping EMPTY = new AlienDeploymentMapping("unknown_deployment_id", DeploymentStatus.UNKNOWN);

    private String alienDeploymentId;
    private DeploymentStatus status;

}
