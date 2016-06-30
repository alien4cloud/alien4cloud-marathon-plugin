package alien4cloud.plugin.marathon.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang.NotImplementedException;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import alien4cloud.model.components.*;
import alien4cloud.model.topology.NodeTemplate;
import alien4cloud.model.topology.RelationshipTemplate;
import alien4cloud.paas.exception.NotSupportedException;
import alien4cloud.paas.function.FunctionEvaluator;
import alien4cloud.paas.model.PaaSNodeTemplate;
import alien4cloud.paas.model.PaaSRelationshipTemplate;
import alien4cloud.paas.model.PaaSTopology;
import alien4cloud.paas.model.PaaSTopologyDeploymentContext;
import lombok.extern.log4j.Log4j;
import mesosphere.marathon.client.model.v2.*;

/**
 * @author Adrian Fraisse
 */
@Service
@Log4j
public class MarathonMappingService {

    private Map<String, Map<String, Integer>> mapPortEndpoints = Maps.newHashMap();

    /**
     * Parse an Alien deployment context into a Marathon group definition.
     * @param paaSTopologyDeploymentContext
     * @return
     */
    public Group buildGroupDefinition(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext) {
        // Setup parent group
        Group parentGrp = new Group();
        // Group id == pass topology deployment id
        parentGrp.setId(paaSTopologyDeploymentContext.getDeploymentPaaSId().toLowerCase());
        parentGrp.setApps(Lists.newArrayList());
        parentGrp.setDependencies(Lists.newArrayList());

        // Docker containers are non-natives
        final List<PaaSNodeTemplate> paaSNodeTemplates = paaSTopologyDeploymentContext.getPaaSTopology().getNonNatives();

        paaSNodeTemplates.forEach(node -> {
            parentGrp.getApps().add(buildAppDefinition(node, paaSTopologyDeploymentContext.getPaaSTopology()));
        });

        return parentGrp;
    }

    /**
     * Parse an alien topology into a Marathon App Definition
     *
     */
    public App buildAppDefinition(PaaSNodeTemplate nodeTemplate, MarathonConfig config) {

        // Generate app structure
        App appDef = new App();
        Container container = new Container();
        Docker docker = new Docker();
        container.setDocker(docker);
        appDef.setContainer(container);

        // Retrieve docker image
        final ImplementationArtifact implementationArtifact = nodeTemplate.getInterfaces()
                .get("tosca.interfaces.node.lifecycle.Standard").getOperations()
                .get("create").getImplementationArtifact();
        if (implementationArtifact != null) {
            final String artifactRef = implementationArtifact.getArtifactRef();
            if (artifactRef.contains(".dockerimg")) docker.setImage(artifactRef.split(Pattern.quote(".dockerimg"))[0]);
            else throw new NotSupportedException("Create artifact should be in the form <hub/repo/image:version.dockerimg>");
        } else throw new NotImplementedException("Create artifact should contain the image");

        // Handle capabilities
        Map<String, Integer> endpoints = Maps.newHashMap();
        // todo : YAY java 8 !
        nodeTemplate.getTemplate().getCapabilities().forEach((name, capability) -> {
            if (capability.getType().contains("capabilities.endpoint")) { // FIX ME : better check of capability types...
                // Retrieve port mapping for the capability - note : if no port is specified then let marathon decide.
                Port port = capability.getProperties().containsKey("port") ?
                        new Port(Integer.valueOf(((ScalarPropertyValue) capability.getProperties().get("port")).getValue())) :
                        new Port(0);

                if (capability.getProperties().containsKey("docker_port_mapping")) {
                    docker.setNetwork("BRIDGE");
                    final Integer hostPort = Integer.valueOf(((ScalarPropertyValue) capability.getProperties().get("docker_port_mapping")).getValue());
                    port.setHostPort(hostPort);
                    endpoints.put(name, hostPort);
                } else {
                    docker.setNetwork("HOST");
                    // Store endpoint
                    endpoints.put(name, port.getContainerPort());
                }
            }
        });
        mapPortEndpoints.put(nodeTemplate.getId(), endpoints);

        // Get connectsTo relationships - only those are supported.
        // NOTE: each ConnectsTo relationship will result in arguments given to docker's entrypoint in the following pattern : requirement_host & requirement_port
        // Host would be the app marathon id for mesos-dns resolving.
        // FIXME : solution pas opti : il faut d'autres moyens de passer les infos au container... variable d'environnement ?
        // FIXME : il faudrait pouvoir définir ce qu'on attend dans la définition tosca, via un input par ex ?
        nodeTemplate.getTemplate().getRelationships().forEach((k, v) -> {
            if (v.getType().equalsIgnoreCase("tosca.relationships.connectsto")) {
                Integer port = mapPortEndpoints.get(v.getTarget()).get(v.getTargetedCapabilityName());
            }
        });


        appDef.setInstances(1);
        appDef.setId(nodeTemplate.getId().toLowerCase());

        // Resources
        final ScalarPropertyValue cpu_share = (ScalarPropertyValue) nodeTemplate.getTemplate().getProperties().get("cpu_share");
        appDef.setCpus(Double.valueOf(cpu_share.getValue()));

        final ScalarPropertyValue mem_share = (ScalarPropertyValue) nodeTemplate.getTemplate().getProperties().get("mem_share");
        appDef.setMem(Double.valueOf(mem_share.getValue()));

        return appDef;
    }
}
