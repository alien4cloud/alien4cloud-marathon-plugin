package alien4cloud.plugin.marathon.location;

import alien4cloud.model.deployment.matching.MatchingConfiguration;
import alien4cloud.model.orchestrators.locations.LocationResourceTemplate;
import alien4cloud.orchestrators.plugin.ILocationConfiguratorPlugin;
import alien4cloud.orchestrators.plugin.ILocationResourceAccessor;
import alien4cloud.orchestrators.plugin.model.PluginArchive;
import alien4cloud.paas.exception.PluginParseException;
import alien4cloud.plugin.model.ManagedPlugin;
import org.alien4cloud.tosca.catalog.ArchiveParser;
import alien4cloud.tosca.model.ArchiveRoot;
import alien4cloud.tosca.parser.ParsingException;
import alien4cloud.tosca.parser.ParsingResult;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * @author Adrian Fraisse
 */
@Slf4j
@Component
@Scope("prototype")
public class MarathonLocationConfigurator implements ILocationConfiguratorPlugin {

    @Inject
    private ManagedPlugin selfContext;

    @Inject
    private ArchiveParser archiveParser;

    private List<PluginArchive> archives;

    @Override
    public List<PluginArchive> pluginArchives() {
        if (archives == null) {
            archives = Lists.newArrayList();
            try {
                addToArchive(archives, "marathon/resources");
            } catch (ParsingException e) {
                log.error(e.getMessage());
                throw new PluginParseException(e.getMessage());
            }
        }
        return archives;
    }

    @Override
    public List<String> getResourcesTypes() {
        return Lists.newArrayList("alien.nodes.marathon.Container");
    }

    @Override
    public Map<String, MatchingConfiguration> getMatchingConfigurations() {
        return Maps.newHashMap();
    }

    @Override
    public List<LocationResourceTemplate> instances(ILocationResourceAccessor iLocationResourceAccessor) {
        return Lists.newArrayList();
    }

    private void addToArchive(List<PluginArchive> archives, String path) throws ParsingException {
        Path archivePath = selfContext.getPluginPath().resolve(path);
        // Parse the archives
        ParsingResult<ArchiveRoot> result = archiveParser.parseDir(archivePath);
        PluginArchive pluginArchive = new PluginArchive(result.getResult(), archivePath);
        archives.add(pluginArchive);
    }
}
