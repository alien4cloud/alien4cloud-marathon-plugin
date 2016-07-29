package alien4cloud.plugin.marathon.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang.NotImplementedException;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import alien4cloud.model.components.*;
import alien4cloud.model.topology.NodeTemplate;
import alien4cloud.paas.exception.NotSupportedException;
import alien4cloud.paas.function.FunctionEvaluator;
import alien4cloud.paas.model.PaaSNodeTemplate;
import alien4cloud.paas.model.PaaSTopology;
import alien4cloud.paas.model.PaaSTopologyDeploymentContext;
import lombok.extern.log4j.Log4j;
import mesosphere.marathon.client.model.v2.*;

/**
 * @author Adrian Fraisse
 */
@Service
@Log4j
@Scope("prototype")
public class MarathonMappingService {

    // TODO: Store increments in DB, or retrieve from Marathon ?
    private AtomicInteger servicePortIncrement = new AtomicInteger(10000);

    private Map<String, Integer> mapPortEndpoints = Maps.newHashMap();

    /**
     * Parse an Alien deployment context into a Marathon group definition.
     *
     * @param paaSTopologyDeploymentContext the deployment to process
     * @return A Marathon Group definition
     */
    public Group buildGroupDefinition(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext) {
        // Setup parent group
        Group parentGrp = new Group();
        // Group id == pass topology deployment id
        parentGrp.setId(paaSTopologyDeploymentContext.getDeploymentPaaSId().toLowerCase());
        parentGrp.setApps(Lists.newArrayList());

        // Docker containers are non-natives
        final List<PaaSNodeTemplate> paaSNodeTemplates = paaSTopologyDeploymentContext.getPaaSTopology().getNonNatives();

        paaSNodeTemplates.forEach(node -> {
            parentGrp.getApps().add(buildAppDefinition(node, paaSTopologyDeploymentContext.getPaaSTopology()));
        });

        return parentGrp;
    }

    /**
     * Map an alien topology into a Marathon App Definition.
     *
     * @param paaSNodeTemplate the node template to map
     * @param paaSTopology the topology the node belongs to
     * @return a Marathon App definition
     */
    public App buildAppDefinition(PaaSNodeTemplate paaSNodeTemplate, PaaSTopology paaSTopology) {

        final NodeTemplate nodeTemplate = paaSNodeTemplate.getTemplate();
        final Map<String, AbstractPropertyValue> nodeTemplateProperties = nodeTemplate.getProperties();

        // Generate app structure
        App appDef = new App();
        appDef.setInstances(1); // Todo get scalingPolicy
        appDef.setId(paaSNodeTemplate.getId().toLowerCase());
        Container container = new Container();
        Docker docker = new Docker();
        container.setType("DOCKER");
        container.setDocker(docker);
        appDef.setContainer(container);
        docker.setPortMappings(Lists.newArrayList());
        docker.setParameters(Lists.newArrayList());
        appDef.setEnv(Maps.newHashMap());

        // Resources TODO: check null
        final ScalarPropertyValue cpu_share = (ScalarPropertyValue) nodeTemplateProperties.get("cpu_share");
        appDef.setCpus(Double.valueOf(cpu_share.getValue()));

        final ScalarPropertyValue mem_share = (ScalarPropertyValue) nodeTemplateProperties.get("mem_share");
        appDef.setMem(Double.valueOf(mem_share.getValue()));

        // Only the create operation is supported
        final Operation createOperation = paaSNodeTemplate.getInterfaces()
                .get("tosca.interfaces.node.lifecycle.Standard").getOperations()
                .get("create");

        // Retrieve docker image
        final ImplementationArtifact implementationArtifact = createOperation.getImplementationArtifact();
        if (implementationArtifact != null) {
            final String artifactRef = implementationArtifact.getArtifactRef();
            if (artifactRef.endsWith(".dockerimg")) docker.setImage(artifactRef.split(Pattern.quote(".dockerimg"))[0]); // TODO use a regex instead
            else throw new NotSupportedException("Create artifact should be in the form <hub/repo/image:version.dockerimg>");
        } else throw new NotImplementedException("Create artifact should contain the image");

        // Inputs from the create interface
        /* Prefix-based mapping : ENV_ => Env var, OPT_ => docker option, ARG_ => Docker run args */
        if (createOperation.getInputParameters() != null) {
            createOperation.getInputParameters().forEach((key, val) -> {

                String value = "";
                if (val instanceof FunctionPropertyValue
                        && "get_property".equals(((FunctionPropertyValue) val).getFunction())
                        && "REQ_TARGET".equals(((FunctionPropertyValue) val).getTemplateName())) {
                    // Get property of a requirement's targeted capability
                    value = getPropertyFromReqTarget(paaSNodeTemplate, paaSTopology, (FunctionPropertyValue) val);
                } else if (val instanceof ScalarPropertyValue)
                    value = ((ScalarPropertyValue) val).getValue();

                if (key.startsWith("ENV_")) {
                    // Input as environment variable within the container
                    appDef.getEnv().put(key.replaceFirst("^ENV_", ""), value);
                } else if (key.startsWith("OPT_")) {
                    // Input as a docker option given to the docker cli
                    docker.getParameters().add(new Parameter(key.replaceFirst("OPT_", ""), value));
                } else if (key.startsWith("ARG_")) {
                    // Input as an argument to the docker run command
                    appDef.getArgs().add(value); // Arguments are unnamed
                } else
                    log.warn("Unrecognized prefix for input : <" + key + ">");
            });
        }

        // Turn endpoints capabilities into a portMapping definition - Attribute a service point
        nodeTemplate.getCapabilities().forEach((name, capability) -> {
            if (capability.getType().contains("capabilities.endpoint")) { // FIXME : better check of capability types...
                // Retrieve port mapping for the capability - note : if no port is specified then let marathon decide.
                Port port = capability.getProperties().get("port") != null ?
                        new Port(Integer.valueOf(((ScalarPropertyValue) capability.getProperties().get("port")).getValue())) :
                        new Port(0);

                // FIXME: Attribute service port only if necessary - check relationships templates
                // Si pas déjà fait lors du mapping d'une source, on alloue un port de service
                final Integer servicePort = mapPortEndpoints.getOrDefault(paaSNodeTemplate.getId().concat(name), this.servicePortIncrement.getAndIncrement());
                port.setServicePort(servicePort);
                mapPortEndpoints.put(paaSNodeTemplate.getId().concat(name), servicePort);

                // FIXME: set haproxy_group only if necessary
                // The HAPROXY_GROUP label indicates which load balancer group this application should register to.
                // For now this default to internal.
                appDef.addLabel("HAPROXY_GROUP", "internal");

                if (capability.getProperties().containsKey("docker_bridge_port_mapping")) {
                    docker.setNetwork("BRIDGE");
                    final Integer hostPort = Integer.valueOf(((ScalarPropertyValue) capability.getProperties().get("docker_bridge_port_mapping")).getValue());
                    port.setHostPort(hostPort);
                    port.setProtocol("tcp");
                } else
                    docker.setNetwork("HOST");

                docker.getPortMappings().add(port);
            }
        });

        // Get connectsTo relationships - only those are supported.
        // Each implies the need to create a service port for the targeted capability.
        // TODO : Get Requirement target properties - WARN: relationships can be null (apparently).
        if (nodeTemplate.getRelationships() != null) {
            nodeTemplate.getRelationships().forEach((k, v) -> {
                if (v.getType().equalsIgnoreCase("tosca.relationships.connectsto")) { // TODO: verif si target est bien de type docker
                    if (!mapPortEndpoints.containsKey(v.getTarget().concat(v.getTargetedCapabilityName()))) {
                        // Si la target n'a pas déjà été parsée, on pré-alloue un service port pour permettre le mapping
                        mapPortEndpoints.put(v.getTarget().concat(v.getTargetedCapabilityName()), this.servicePortIncrement.getAndIncrement());
                    }
                    // Anyway, add a dependency to the target
                    appDef.addDependency(v.getTarget().toLowerCase());
                }
            });
        }

        // Properties
        /* Env variables ==> Map of String values */
        if (nodeTemplateProperties.get("docker_env_vars") != null) {
            ((ComplexPropertyValue) nodeTemplateProperties.get("docker_env_vars")).getValue().forEach((var, val) -> {
                // Mapped property expected as String. Deal with the property as a environment variable
                appDef.getEnv().put(var, String.valueOf(val)); // TODO Replace by MapUtil || JsonUtil
            });
        }

        /* Docker options ==> Map of String values */
        if (nodeTemplateProperties.get("docker_options") != null) {
            ((ComplexPropertyValue) nodeTemplateProperties.get("docker_options")).getValue().forEach((var, val) -> {
                docker.getParameters().add(new Parameter(var, String.valueOf(val)));
            });
        }

        /* Docker run args */
        if (nodeTemplateProperties.get("docker_run_args") != null) {
            if (appDef.getArgs() == null) {
                appDef.setArgs(Lists.newArrayList());
            }
            appDef.getArgs().addAll(
                    ((ListPropertyValue) nodeTemplateProperties.get("docker_run_args")).getValue().
                    stream().map(String::valueOf).collect(Collectors.toList())
            );
        }

        /* Docker command */
        if (nodeTemplateProperties.get("docker_run_cmd") != null) {
            appDef.setCmd(((ScalarPropertyValue) nodeTemplateProperties.get("docker_run_cmd")).getValue());
        }

        /* Create a TCP health check */
        HealthCheck healthCheck = new HealthCheck();
        healthCheck.setPortIndex(0);
        healthCheck.setProtocol("TCP");
        healthCheck.setGracePeriodSeconds(300);
        healthCheck.setIntervalSeconds(15);
        healthCheck.setMaxConsecutiveFailures(1);
        appDef.setHealthChecks(Lists.newArrayList(healthCheck));

        return appDef;
    }

    /**
     * Search for a property of a capability being required as a target of a relationship.
     * @param paaSNodeTemplate The source node of the relationships, wich defines the requirement.
     * @param paaSTopology the topology the node belongs to.
     * @param params the function parameters, e.g. the requirement name & property name to lookup.
     * @return a String representing the property value.
     */
    private String getPropertyFromReqTarget(PaaSNodeTemplate paaSNodeTemplate, PaaSTopology paaSTopology, FunctionPropertyValue params) {
        // Search for the requirement's target by filter the relationships' templates of this node.
        // If a target is found, then lookup for the given property name in its capabilities.
        // The orchestrator replaces the PORT and IP_ADDRESS by the target's service port and the load balancer hostname respectively.

        String requirementName = params.getCapabilityOrRequirementName();
        String propertyName = params.getElementNameToFetch();

        return paaSNodeTemplate.getRelationshipTemplates().stream()
            .filter(item -> paaSNodeTemplate.getId().equals(item.getSource()) && requirementName.equals(item.getTemplate().getRequirementName()))
            .findFirst() // Find the first relationship template which fulfills the given requirement, for this source
            .map(relationshipTemplate -> {
                // Special marathon case use service ports if the "Port" property is required.
                if (relationshipTemplate.instanceOf("tosca.relationships.ConnectsTo")) { // - TODO/FIXME : check target derived_from marathon
                    if ("port".equalsIgnoreCase(propertyName))
                        // TODO: Retrieve service port if exists - if not, get capability value
                        return String.valueOf( // Service ports are mapped using the targetName + capabilityName
                                mapPortEndpoints.getOrDefault(
                                        relationshipTemplate.getTemplate().getTarget()
                                            + relationshipTemplate.getTemplate().getTargetedCapabilityName()
                                        , 0));
                    else if ("ip_address".equalsIgnoreCase(propertyName))
                        // Return marathon-lb hostname
                        return "marathon-lb.marathon.mesos";
                }
                // Nominal case : get the requirement's targeted capability property.
                // TODO: Add the REQ_TARGET keyword in the evaluateGetProperty function soo this is evaluated at parsing
                return FunctionEvaluator.evaluateGetPropertyFunction(params, paaSNodeTemplate, paaSTopology.getAllNodes());
            }).orElse("");
    }

}
