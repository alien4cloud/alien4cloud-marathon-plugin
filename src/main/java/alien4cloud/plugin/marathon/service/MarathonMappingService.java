package alien4cloud.plugin.marathon.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.alien4cloud.tosca.model.definitions.*;
import org.apache.commons.lang.NotImplementedException;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.ScalingPolicy;
import alien4cloud.paas.exception.NotSupportedException;
import alien4cloud.paas.function.FunctionEvaluator;
import alien4cloud.paas.model.PaaSNodeTemplate;
import alien4cloud.paas.model.PaaSTopology;
import alien4cloud.paas.model.PaaSTopologyDeploymentContext;
import lombok.extern.log4j.Log4j;
import mesosphere.marathon.client.model.v2.*;

/**
 * Service for transformation of Alien PaaSTopologies into Marathon Groups and Apps definitions.
 * Currently only Docker containers are supported.
 *
 * TODO: Split the massive buildAppDefinition method (see the builder branch)
 *
 * @author Adrian Fraisse
 */
@Service
@Log4j
@Scope("prototype")
public class MarathonMappingService {

    /**
     * We allocate Service Ports starting from 10000.
     * TODO: Store in DB or retrieve from Marathon. Alternatively, we could let Marathon randomly allocate a Service Port then poll for its value.
     */
    private AtomicInteger servicePortIncrement = new AtomicInteger(10000);

    /**
     * Map to store service ports allocated to marathon endpoints. Used to fulfill relationships requirements.
     */
    private Map<String, Integer> mapPortEndpoints = Maps.newHashMap();

    /**
     * Map an Alien deployment context to a Marathon group definition.
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

        // Marathon topologies are only non-natives nodes (eg. apps)
        final List<PaaSNodeTemplate> paaSNodeTemplates = paaSTopologyDeploymentContext.getPaaSTopology().getNonNatives();

        paaSNodeTemplates.forEach(node ->
            parentGrp.getApps().add(buildAppDefinition(node, paaSTopologyDeploymentContext.getPaaSTopology()))
        );

        // Clean the port endpoints map
        mapPortEndpoints.clear();

        return parentGrp;
    }

    /**
     * Map an alien PaaSNodeTemplate to a Marathon App Definition.
     *
     * @param paaSNodeTemplate the node template to map
     * @param paaSTopology the topology the node belongs to
     * @return a Marathon App definition
     */
    private App buildAppDefinition(PaaSNodeTemplate paaSNodeTemplate, PaaSTopology paaSTopology) {
        final NodeTemplate nodeTemplate = paaSNodeTemplate.getTemplate();

        /**
         * Init app structure
         */
        App appDef = new App();
        appDef.setInstances(Optional.ofNullable(paaSNodeTemplate.getScalingPolicy()).orElse(ScalingPolicy.NOT_SCALABLE_POLICY).getInitialInstances());
        appDef.setId(paaSNodeTemplate.getId().toLowerCase());
        Container container = new Container();
        Docker docker = new Docker();
        container.setType("DOCKER");
        container.setDocker(docker);
        appDef.setContainer(container);
        docker.setPortMappings(Lists.newArrayList());
        docker.setParameters(Lists.newArrayList());
        appDef.setEnv(Maps.newHashMap());

        /**
         *  CREATE OPERATION
         *  Map Docker image
         */
        // Only the create operation is supported
        final Operation createOperation = paaSNodeTemplate.getInterfaces()
                .get("tosca.interfaces.node.lifecycle.Standard").getOperations()
                .get("create");

        // Retrieve docker image from the Create operation implementation artifact
        // TODO: Rethink how we deal with images to get rid of the .dockerimg extension
        final ImplementationArtifact implementationArtifact = createOperation.getImplementationArtifact();
        if (implementationArtifact != null) {
            final String artifactRef = implementationArtifact.getArtifactRef();
            if (artifactRef.endsWith(".dockerimg")) docker.setImage(artifactRef.split(Pattern.quote(".dockerimg"))[0]); // TODO use a regex instead
            else throw new NotSupportedException("Create implementation artifact should be in the form <hub/repo/image:version.dockerimg>");
        } else throw new NotImplementedException("Create implementation artifact should contain the image");

        /**
         * RELATIONSHIPS
         * Only connectsTo relationships are supported : an app can only connect to a container endpoint.
         * Each relationship implies the need to create a service port for the targeted capability.
         * We keep track of service ports allocated to relationships' targets in order to allocate only one port per capability.
         */
        if (nodeTemplate.getRelationships() != null) { // Get all the relationships this node is a source of
            nodeTemplate.getRelationships().forEach((key, relationshipTemplate) -> {
                // TODO: We should validate that the targeted node is of Docker type and better check the relationship type
                if ("tosca.relationships.connectsto".equalsIgnoreCase(relationshipTemplate.getType())) {
                    if (!mapPortEndpoints.containsKey(relationshipTemplate.getTarget().concat(relationshipTemplate.getTargetedCapabilityName()))) {
                        // We haven't processed the target already: we pre-allocate a service port
                        mapPortEndpoints.put(
                                relationshipTemplate.getTarget().concat(relationshipTemplate.getTargetedCapabilityName()),
                                this.servicePortIncrement.getAndIncrement()
                        );
                    }
                    // Add a dependency to the target
                    appDef.addDependency(relationshipTemplate.getTarget().toLowerCase());
                }
            });
        }

        /*
         * INPUTS from the Create operation
         */
        /* Prefix-based mapping : ENV_ => Env var, OPT_ => docker option, ARG_ => Docker run args */
        if (createOperation.getInputParameters() != null) {
            createOperation.getInputParameters().forEach((key, val) -> {

                // Inputs can either be a ScalarValue or a pointer to a capability targeted by one of the node's requirements
                String value = ""; // TODO: This should be generalized into Alien parser
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

        /**
         * CAPABILITIES
         * Turn Alien endpoints capabilities into a PortMapping definition and attribute a service port to each endpoint.
         * This means that this node CAN be targeted by a ConnectsTo relationship.
         * Register the app into the internal service discovery group.
         */
        nodeTemplate.getCapabilities().forEach((name, capability) -> {
            if (capability.getType().contains("capabilities.endpoint")) { // FIXME : better check of capability types...
                // Retrieve port mapping for the capability - note : if no port is specified then let marathon decide.
                Port port = capability.getProperties().get("port") != null ?
                        new Port(Integer.valueOf(((ScalarPropertyValue) capability.getProperties().get("port")).getValue())) :
                        new Port(0);

                // TODO: Attribute service port only if necessary, eg. the capability is NOT targeted or if ports are statically allocated
                // If this node's capability is targeted by a relationship, we may already have pre-allocated a service port for it
                final String endpointID = paaSNodeTemplate.getId().concat(name);
                final Integer servicePort = mapPortEndpoints.getOrDefault(
                        endpointID,
                        this.servicePortIncrement.getAndIncrement()
                );
                port.setServicePort(servicePort);
                mapPortEndpoints.put(endpointID, servicePort); // Store the endpoint for further use by other apps

                // TODO: set haproxy group only if necessary
                // The HAPROXY_GROUP label indicates which load balancer group this application should register to.
                // For now this default to internal.
                appDef.addLabel("HAPROXY_GROUP", "internal");

                // If the capability has a "docker_bridge_port_mapping" property, then use Docker bridge networking
                if (capability.getProperties().containsKey("docker_bridge_port_mapping")) {
                    docker.setNetwork("BRIDGE");
                    port.setHostPort(Integer.valueOf(((ScalarPropertyValue) capability.getProperties().get("docker_bridge_port_mapping")).getValue()));
                    port.setProtocol("tcp");
                } else
                    docker.setNetwork("HOST");

                docker.getPortMappings().add(port);
            }
        });

        /**
         * USER DEFINED PROPERTIES
         */
        final Map<String, AbstractPropertyValue> nodeTemplateProperties = nodeTemplate.getProperties();

        /* Resources Marathon should allocate the container - default 1.0 cpu 256 MB ram */
        final Optional<String> cpu_share = Optional.ofNullable(((ScalarPropertyValue) nodeTemplateProperties.get("cpu_share")).getValue());
        final Optional<String> mem_share = Optional.ofNullable(((ScalarPropertyValue) nodeTemplateProperties.get("mem_share")).getValue());
        appDef.setCpus(Double.valueOf(cpu_share.orElse("1.0")));
        appDef.setMem(Double.valueOf(mem_share.orElse("256.0")));

        /* Docker command */
        if (nodeTemplateProperties.get("docker_run_cmd") != null) {
            appDef.setCmd(((ScalarPropertyValue) nodeTemplateProperties.get("docker_run_cmd")).getValue());
        }

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

        /* Create a basic TCP health check */
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
        // For Docker containers X Marathon, the orchestrator replaces the PORT and IP_ADDRESS by the target's service port and the load balancer hostname respectively.

        String requirementName = params.getCapabilityOrRequirementName();
        String propertyName = params.getElementNameToFetch();

        return paaSNodeTemplate.getRelationshipTemplates().stream()
            .filter(item -> paaSNodeTemplate.getId().equals(item.getSource()) && requirementName.equals(item.getTemplate().getRequirementName()))
            .findFirst() // Find the first relationship template which fulfills the given requirement, for this source
            .map(relationshipTemplate -> {

                if (relationshipTemplate.instanceOf("tosca.relationships.ConnectsTo")) { // - TODO/FIXME : check target derived from docker type
                    // Special marathon case: use service ports if the "Port" property is required.
                    if ("port".equalsIgnoreCase(propertyName))
                        // TODO: Retrieve service port if exists - if not, get capability value (for use cases where ports are statically defined)
                        return String.valueOf( // Service ports are mapped using the targetName + capabilityName
                                mapPortEndpoints.getOrDefault(
                                        relationshipTemplate.getTemplate().getTarget()
                                            + relationshipTemplate.getTemplate().getTargetedCapabilityName()
                                        , 0));
                    else if ("ip_address".equalsIgnoreCase(propertyName))
                        // TODO: If there is no service port, return <target_app_id>.marathon.mesos for DNS resolution
                        // Special marathon case: return marathon-lb hostname if an ip_address is required.
                        return "marathon-lb.marathon.mesos";
                }
                // Nominal case : get the requirement's targeted capability property.
                // TODO: Add the REQ_TARGET keyword in the evaluateGetProperty function so this is evaluated at parsing
                return FunctionEvaluator.evaluateGetPropertyFunction(params, paaSNodeTemplate, paaSTopology.getAllNodes());
            }).orElse("");
    }

}
