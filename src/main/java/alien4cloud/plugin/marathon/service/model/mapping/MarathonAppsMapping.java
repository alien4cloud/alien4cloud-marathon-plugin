package alien4cloud.plugin.marathon.service.model.mapping;

import com.google.common.collect.Maps;
import lombok.Getter;

import java.util.Map;

/**
 * Utility class to store mapping between a Marathon Group and an Alien deployment.
 */
@Getter
public class MarathonAppsMapping {
    private String alienDeploymentId;
    private Map<String, String> appIdToNodeTemplateIdMap;

    public MarathonAppsMapping(String alienDeploymentId) {
        this.alienDeploymentId = alienDeploymentId;
        this.appIdToNodeTemplateIdMap = Maps.newHashMap();
    }

    public void addAppToNodeTemplateMapping(String appId, String nodeTemplateId) {
        appIdToNodeTemplateIdMap.put(appId, nodeTemplateId);
    }

    public String getNodeTemplateId(String appId) {
       return appIdToNodeTemplateIdMap.get(appId);
    }
}
