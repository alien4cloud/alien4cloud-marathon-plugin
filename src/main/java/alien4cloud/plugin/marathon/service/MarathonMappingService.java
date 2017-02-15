package alien4cloud.plugin.marathon.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import lombok.Getter;
import org.springframework.stereotype.Service;

import com.google.common.collect.Maps;

import alien4cloud.paas.model.DeploymentStatus;
import alien4cloud.paas.model.PaaSTopologyDeploymentContext;
import alien4cloud.plugin.marathon.service.model.mapping.AlienDeploymentMapping;
import alien4cloud.plugin.marathon.service.model.mapping.MarathonAppsMapping;
import lombok.extern.log4j.Log4j;

/**
 * Service to help maintain a mapping between ids in Marathon and ids in Alien.
 * Optional-based api to allow custom behavior when null.
 */
@Service
@Log4j
public class MarathonMappingService {

    /**
     * For each groupId (eg. lower-cased PaaSDeploymentId), map a wrapper with the Alien deployment id and a Map of <AppIds, NodeTemplateIds>
     * Assumes that every marathon app launched with the plugin is in a group named by the PaaSDeploymentId.
     */
    private final Map<String, MarathonAppsMapping> marathonToAlienAppsMap = Maps.newConcurrentMap();

    /**
     * Map marathon deployment ids, which are ephemeral, to alien deployment ids
     */
    private final Map<String, AlienDeploymentMapping> marathonToAlienDeploymentMap = Maps.newConcurrentMap();

    /**
     * Register a running deployment into the MappingService.
     * @param marathonDeploymentId the id of the deployment in Marathon
     * @param alienDeploymentId the id of the deployment in Alien
     * @param status The running status of the deployment, Deploying or Undeploying.
     */
    public void registerDeploymentInfo(String marathonDeploymentId, String alienDeploymentId, DeploymentStatus status) {
        marathonToAlienDeploymentMap.put(marathonDeploymentId, new AlienDeploymentMapping(alienDeploymentId, status));
    }

    public Optional<AlienDeploymentMapping> getAlienDeploymentInfo(String marathonDeploymentId) {
        return Optional.ofNullable(marathonToAlienDeploymentMap.get(marathonDeploymentId));
    }

    public void removeAlienDeploymentInfo(String marathonDeploymentId) {
        marathonToAlienDeploymentMap.remove(marathonDeploymentId);
    }

    void registerGroupMapping(String groupId, String alienDeploymentId) {
        marathonToAlienAppsMap.put(groupId, new MarathonAppsMapping(alienDeploymentId)); // TODO throw if already present ?
    }

    void registerAppMapping(String groupId, String appId, String nodeTemplateId) { // TODO throw if already present ?
        marathonToAlienAppsMap.get(groupId).addAppToNodeTemplateMapping(appId, nodeTemplateId);
    }

    public Optional<MarathonAppsMapping> getMarathonAppMapping(String marathonGroupId) {
        return Optional.ofNullable(marathonToAlienAppsMap.get(marathonGroupId));
    }

    public void init(Collection<PaaSTopologyDeploymentContext> activeDeployments) {
        activeDeployments.forEach(context -> {
            // Initialize a new group mapping
            final String groupId = context.getDeploymentPaaSId().toLowerCase();
            // Fill app mapping
            registerGroupMapping(groupId, context.getDeploymentId());
            context.getPaaSTopology().getNonNatives().forEach(nodeTemplate -> registerAppMapping(groupId, nodeTemplate.getId().toLowerCase(), nodeTemplate.getId()));
        });
    }
}
