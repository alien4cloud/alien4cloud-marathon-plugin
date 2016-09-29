package alien4cloud.plugin.marathon.artifacts;

import alien4cloud.component.repository.IArtifactResolver;
import alien4cloud.repository.model.ValidationResult;
import alien4cloud.repository.model.ValidationStatus;
import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * @author Adrian Fraisse
 */
@Component("docker-image-resolver")
public class DockerImageResolver implements IArtifactResolver {

    @Override
    public String getResolverType() {
        return "docker";
    }

    @Override
    public ValidationResult canHandleArtifact(String artifactReference, String repositoryURL, String repositoryType, Map<String, Object> credentials) {
        return getResolverType().equals(repositoryType) ? ValidationResult.SUCCESS
                : new ValidationResult(ValidationStatus.INVALID_REPOSITORY_TYPE, "");
    }

    @Override
    public String resolveArtifact(String artifactReference, String repositoryURL, String repositoryType, Map<String, Object> credentials) {
        return canHandleArtifact(artifactReference, repositoryURL, repositoryType, credentials) == ValidationResult.SUCCESS
                ? artifactReference : null;
    }
}
