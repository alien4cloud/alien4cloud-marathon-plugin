package alien4cloud.plugin.marathon;

import java.util.Map;

import javax.annotation.Resource;
import javax.inject.Inject;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.common.collect.Maps;

import alien4cloud.model.components.PropertyDefinition;
import alien4cloud.model.orchestrators.ArtifactSupport;
import alien4cloud.model.orchestrators.locations.LocationSupport;
import alien4cloud.orchestrators.plugin.IOrchestratorPluginFactory;
import alien4cloud.plugin.marathon.config.MarathonConfig;

/**
 * @author Adrian Fraisse
 */
@Component("marathon-orchestrator-factory")
public class MarathonOrchestratorFactory implements IOrchestratorPluginFactory<MarathonOrchestrator, MarathonConfig>{

    @Autowired
    private BeanFactory beanFactory;

    @Override
    public MarathonOrchestrator newInstance() {
        return beanFactory.getBean(MarathonOrchestrator.class);
    }

    @Override
    public void destroy(MarathonOrchestrator marathonOrchestrator) {
        // Garbage collected
    }

    @Override
    public MarathonConfig getDefaultConfiguration() {
        return new MarathonConfig("http://localhost:8080");
    }

    @Override
    public Class<MarathonConfig> getConfigurationType() {
        return MarathonConfig.class;
    }

    @Override
    public LocationSupport getLocationSupport() {
        return new LocationSupport(false, new String[] { "marathon" });
    }

    @Override
    public ArtifactSupport getArtifactSupport() {
        return new ArtifactSupport(new String[] { "tosca.artifacts.Deployment.Image.Container.Docker" });
    }

    @Override
    public Map<String, PropertyDefinition> getDeploymentPropertyDefinitions() {
        return Maps.newHashMap();
    }

    @Override
    public String getType() {
        return "Marathon";
    }
}
