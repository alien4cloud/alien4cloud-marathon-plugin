package alien4cloud.plugin.marathon.service.model.events.deployments;

import alien4cloud.plugin.marathon.service.model.events.AbstractEvent;

/**
 * @author Adrian Fraisse
 */
public abstract class AbstractDeploymentEvent extends AbstractEvent {

    public abstract String getId();
}
