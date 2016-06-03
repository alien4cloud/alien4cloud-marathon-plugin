package alien4cloud.plugin.marathon.service;

import alien4cloud.model.components.ImplementationArtifact;
import alien4cloud.model.components.ScalarPropertyValue;
import alien4cloud.paas.exception.NotSupportedException;
import alien4cloud.paas.model.PaaSNodeTemplate;
import alien4cloud.paas.model.PaaSTopologyDeploymentContext;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mesosphere.marathon.client.model.v2.App;
import mesosphere.marathon.client.model.v2.Container;
import mesosphere.marathon.client.model.v2.Docker;
import mesosphere.marathon.client.model.v2.Group;
import org.apache.commons.lang.NotImplementedException;
import org.springframework.http.converter.json.GsonBuilderUtils;
import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * @author Adrian Fraisse
 */
@Service
public class MarathonMappingService {

    /**
     * Parse an Alien deployment context into a Marathon group definition.
     * @param paaSTopologyDeploymentContext
     * @return
     */
    public Group buildGroupDefinition(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext) {
        Group groupDef = new Group();



        return groupDef;
    }

    /**
     * Parse an alien topology into a Marathon App Definition
     * @param paaSTopologyDeploymentContext
     * @return
     */
    public App buildAppDefinition(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext) {
        // Asumes there is only one App
        PaaSNodeTemplate nodeTemplate = paaSTopologyDeploymentContext.getPaaSTopology().getNonNatives().get(0);

        App appDef = new App();
        Container container = new Container();
        Docker docker = new Docker();

        // Retrieve docker image
        final ImplementationArtifact implementationArtifact = nodeTemplate.getInterfaces()
                .get("tosca.interfaces.node.lifecycle.Standard").getOperations()
                .get("create").getImplementationArtifact();
        if (implementationArtifact != null) {
            final String artifactRef = implementationArtifact.getArtifactRef();
            if (artifactRef.contains(".dockerimg")) docker.setImage(artifactRef.split(Pattern.quote(".dockerimg"))[0]);
            else throw new NotSupportedException("Create artifact should be in the form <hub/repo/image:version.dockerimg>");
        } else throw new NotImplementedException("Create artifact should contain the image");

        // Docker conf
        docker.setNetwork("HOST");
        container.setDocker(docker);
        container.setType("DOCKER");
        appDef.setContainer(container);
        appDef.setInstances(1);
        appDef.setId("/" + nodeTemplate.getId().toLowerCase());

        // Resources
        final ScalarPropertyValue cpu_share = (ScalarPropertyValue) nodeTemplate.getTemplate().getProperties().get("cpu_share");
        appDef.setCpus(Double.valueOf(cpu_share.getValue()));

        final ScalarPropertyValue mem_share = (ScalarPropertyValue) nodeTemplate.getTemplate().getProperties().get("mem_share");
        appDef.setMem(Double.valueOf(mem_share.getValue()));

        return appDef;
    }
}
