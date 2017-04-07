package alien4cloud.plugin.marathon.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.alien4cloud.tosca.model.definitions.*;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.ScalingPolicy;
import org.apache.commons.lang.NotImplementedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import alien4cloud.exception.InvalidArgumentException;
import alien4cloud.paas.function.FunctionEvaluator;
import alien4cloud.paas.model.PaaSNodeTemplate;
import alien4cloud.paas.model.PaaSRelationshipTemplate;
import alien4cloud.paas.model.PaaSTopology;
import alien4cloud.paas.model.PaaSTopologyDeploymentContext;
import alien4cloud.plugin.marathon.service.builders.AppBuilder;
import alien4cloud.plugin.marathon.service.builders.ExternalVolumeBuilder;
import alien4cloud.plugin.marathon.service.builders.PortBuilder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mesosphere.marathon.client.model.v2.App;
import mesosphere.marathon.client.model.v2.Group;

/**
 * Service for transformation of Alien PaaSTopologies into Marathon Groups and Apps definitions.
 * Currently only Docker containers are supported.
 *
 * @author Adrian Fraisse
 */
@Service
@RequiredArgsConstructor(onConstructor=@__(@Autowired))
@Slf4j
public class BuilderService {

    private final @NonNull MappingService mappingService;

    /**
     * We allocate Service Ports starting from 10000.
     * TODO: Store in DB or retrieve from Marathon
     */
    private final AtomicInteger servicePortIncrement = new AtomicInteger(10000);

    /**
     * Map to store service ports allocated to marathon endpoints. Used to fulfill relationships requirements. // TODO store this in ES
     */
    private final Map<String, Integer> mapPortEndpoints = Maps.newHashMap();

    /**
     * Map an Alien deployment context to a Marathon group definition.
     *
     * @param paaSTopologyDeploymentContext the deployment to process
     * @return A Marathon Group definition
     */
    public Group buildGroupDefinition(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext) {
        // Setup parent group
        Group parentGrp = new Group();
        // Group id == pass topology deployment id.
        final String groupID = paaSTopologyDeploymentContext.getDeploymentPaaSId().toLowerCase();
        parentGrp.setId(groupID);
        parentGrp.setApps(Lists.newArrayList());

        // Initialize a new group mapping
        mappingService.registerGroupMapping(groupID, paaSTopologyDeploymentContext.getDeploymentId());

        // Marathon topologies contain only non-natives nodes (eg. apps) and volumes.
        // Each non-native node (and eventually, its attached volumes) are converted to a Marathon App
        final List<PaaSNodeTemplate> nonNatives = Optional.ofNullable(paaSTopologyDeploymentContext.getPaaSTopology().getNonNatives())
                .orElseThrow(() -> new InvalidArgumentException("The topology does not contain any non-native nodes."));
        final List<PaaSNodeTemplate> volumes = Optional.ofNullable(paaSTopologyDeploymentContext.getPaaSTopology().getVolumes())
                .orElse(Collections.emptyList());
        nonNatives.forEach(node -> {
            // Find volumes attached to the node
            final List<PaaSNodeTemplate> attachedVolumes = volumes.stream()
                    .filter(paaSNodeTemplate -> paaSNodeTemplate.getRelationshipTemplates().stream()
                            .filter(paaSRelationshipTemplate -> paaSRelationshipTemplate.instanceOf("alien.relationships.MountDockerVolume")).findFirst()
                            .map(paaSRelationshipTemplate -> paaSRelationshipTemplate.getTemplate().getTarget()).orElse("").equals(node.getId()))
                    .collect(Collectors.toList());
            // Build the app definition and add it to the group
            final App app = buildAppDefinition(groupID, node, paaSTopologyDeploymentContext.getPaaSTopology(), attachedVolumes);
            parentGrp.getApps().add(app);
            // Register app mapping
            mappingService.registerAppMapping(groupID, app.getId(), node.getId());
        });

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
    private App buildAppDefinition(String parentGroupID, PaaSNodeTemplate paaSNodeTemplate, PaaSTopology paaSTopology, List<PaaSNodeTemplate> volumeNodeTemplates) {
        final NodeTemplate nodeTemplate = paaSNodeTemplate.getTemplate();

        /*
         * CREATE OPERATION defines a docker container
         * Retrieve docker image from the Create operation implementation artifact
         */
        final Operation createOperation = paaSNodeTemplate.getInterfaces().get("tosca.interfaces.node.lifecycle.Standard").getOperations().get("create");

        // Initialize the App builder - A docker app with default healthcheck enabled.
        AppBuilder appBuilder = AppBuilder.builder(paaSNodeTemplate.getId())
                .parentGroupID(parentGroupID)
                .instances(Optional.ofNullable(paaSNodeTemplate.getScalingPolicy()).orElse(ScalingPolicy.NOT_SCALABLE_POLICY).getInitialInstances())
                .docker(Optional.ofNullable(createOperation.getImplementationArtifact())
                    .map(AbstractArtifact::getArtifactRef)
                    .orElseThrow(() -> new NotImplementedException("Create implementation artifact should specify the image"))
                ).defaultHealthCheck();

        /*
         * RELATIONSHIPS
         * Only connectsTo relationships are supported : an app can only connect to a container endpoint.
         * Each relationship implies the need to create a service port for the targeted capability.
         * We keep track of service ports allocated to relationships' targets in order to allocate only one port per capability.
         */
        buildDependenciesDefinition(paaSNodeTemplate.getRelationshipTemplates(), appBuilder);

        /*
         * External persistent Docker volumes using the RexRay driver
         */
        buildVolumesDefinition(volumeNodeTemplates, appBuilder);

        /*
         * CAPABILITIES
         * Turn Alien endpoints capabilities into a PortMapping definition and attribute a service port to each endpoint.
         * This means that this node CAN be targeted by a ConnectsTo relationship.
         * Register the app into the internal service discovery group.
         */
        buildPortDefinition(nodeTemplate.getCapabilities(), paaSNodeTemplate.getId().toLowerCase(), appBuilder);

        /*
         * INPUTS from the Create operation
         */
        /* Prefix-based mapping : ENV_ => Env var, OPT_ => docker option, ARG_ => Docker run args */
        if (createOperation.getInputParameters() != null) {
            createOperation.getInputParameters().forEach((key, val) -> {

                // Inputs can either be a ScalarValue or a pointer to a capability targeted by one of the node's requirements
                // NOTE: This could be generalized into Alien parser
                if (val instanceof FunctionPropertyValue && "get_property".equals(((FunctionPropertyValue) val).getFunction())
                        && "REQ_TARGET".equals(((FunctionPropertyValue) val).getTemplateName()))
                    // Get property of a requirement's targeted capability
                    getPropertyFromReqTarget(paaSNodeTemplate, paaSTopology, (FunctionPropertyValue) val, parentGroupID).ifPresent(value -> appBuilder.input(key, value));
                else if (val instanceof ScalarPropertyValue)
                    appBuilder.input(key, ((ScalarPropertyValue) val).getValue());

            });
        }

        /*
         * USER DEFINED PROPERTIES
         */
        buildUserPropsDefinition(nodeTemplate.getProperties(), appBuilder);

        return appBuilder.build();
    }

    private AppBuilder buildUserPropsDefinition(Map<String, AbstractPropertyValue> nodeTemplateProperties, AppBuilder appBuilder) {
    /* Resources Marathon should allocate the container - default 1.0 cpu 256 MB ram */
        appBuilder.cpu(Optional.ofNullable(((ScalarPropertyValue) nodeTemplateProperties.get("cpu_share")).getValue()).map(Double::valueOf).orElse(1.0));
        appBuilder.mem(Optional.ofNullable(((ScalarPropertyValue) nodeTemplateProperties.get("mem_share")).getValue()).map(Double::valueOf).orElse(256.0));

        /* Docker command */
        if (nodeTemplateProperties.get("docker_run_cmd") != null)
            appBuilder.cmd(((ScalarPropertyValue) nodeTemplateProperties.get("docker_run_cmd")).getValue());

        /* Env variables ==> Map of String values */
        if (nodeTemplateProperties.get("docker_env_vars") != null)
            ((ComplexPropertyValue) nodeTemplateProperties.get("docker_env_vars")).getValue().forEach((envName, envValue) ->
                // Mapped property expected as String. Deal with the property as a environment variable
                appBuilder.envVariable(envName, String.valueOf(envValue))
            );

        /* Docker options ==> Map of String values */
        if (nodeTemplateProperties.get("docker_options") != null) {
            ((ComplexPropertyValue) nodeTemplateProperties.get("docker_options")).getValue().forEach((optName, optValue) ->
                appBuilder.dockerOption(optName, String.valueOf(optValue))
            );
        }

        /* Docker run args */
        if (nodeTemplateProperties.get("docker_run_args") != null) {
            appBuilder.dockerArgs(((ListPropertyValue) nodeTemplateProperties.get("docker_run_args")).getValue().stream().map(String::valueOf).toArray(String[]::new));
        }
        return appBuilder;
    }

    private AppBuilder buildPortDefinition(Map<String, Capability> capabilities, String appId, AppBuilder appBuilder) {
        capabilities.forEach((name, capability) -> {
            if (capability.getType().contains("capabilities.endpoint")) { // FIXME : better check of capability types

                // TODO: Attribute service port only if necessary, eg. the capability is targeted and ports are not statically allocated
                final String endpointID = appBuilder.getParentGroupID() + "/" + appId + "/" + name;
                final Integer servicePort = mapPortEndpoints.getOrDefault(endpointID, this.servicePortIncrement.getAndIncrement()); // If this node's capability is targeted by a relationship, we may already have pre-allocated a service port for it
                mapPortEndpoints.put(endpointID, servicePort); // Store the endpoint for further use by other apps

                // Build a port definition
                PortBuilder portBuilder = PortBuilder.builder()
                        .containerPort(capability.getProperties().get("port") != null ?
                                Integer.valueOf(((ScalarPropertyValue) capability.getProperties().get("port")).getValue()) : 0)
                        .servicePort(servicePort);

                // If the capability has a "docker_bridge_port_mapping" property, then use Docker bridge networking
                if (capability.getProperties().containsKey("docker_bridge_port_mapping")) {
                    appBuilder.bridgeNetworking();
                    portBuilder.hostPort(
                            Optional.ofNullable(((ScalarPropertyValue) capability.getProperties()
                                    .get("docker_bridge_port_mapping"))
                                    .getValue())
                                    .map(Integer::valueOf)
                                    .orElse(0)) // If not value is present, let Marathon decide
                    .tcp();
                } else
                    appBuilder.hostNetworking();

                // TODO: set haproxy group only if necessary, eg. if there's a service port.
                // The HAPROXY_GROUP label indicates which load balancer group this application should register to.
                appBuilder.portMapping(portBuilder.build()).internallyLoadBalanced();
            }
        });
        return appBuilder;
    }

    private AppBuilder buildDependenciesDefinition(List<PaaSRelationshipTemplate> relationships, AppBuilder appBuilder) {
        if (relationships != null) { // Get all the relationships this node is a source of
            relationships.stream().filter(rel -> rel.getSource().equalsIgnoreCase(appBuilder.getAppID())).forEach(relationshipTemplate -> {
                // TODO: Validate that the targeted node is of Docker type (for hybrid topologies)
                if (relationshipTemplate.instanceOf("tosca.relationships.ConnectsTo")) {
                    final RelationshipTemplate template = relationshipTemplate.getTemplate();
                    final String endpointID = appBuilder.getParentGroupID() + "/" + template.getTarget().toLowerCase() + "/" + template.getTargetedCapabilityName();
                    if (!mapPortEndpoints.containsKey(endpointID)) {
                        // We haven't processed the target already: we pre-allocate a service port
                        mapPortEndpoints.put(endpointID,
                                this.servicePortIncrement.getAndIncrement());
                    }
                    // Add a dependency to the target
                    appBuilder.dependency(template.getTarget().toLowerCase());
                }
            });
        }
        return appBuilder;
    }

    private AppBuilder buildVolumesDefinition(List<PaaSNodeTemplate> volumeNodeTemplates, AppBuilder appBuilder) {
        volumeNodeTemplates.forEach(volumeTemplate -> {
            final Map<String, AbstractPropertyValue> volumeTemplateProperties = volumeTemplate.getTemplate().getProperties();
            // Build volume definition
            appBuilder.externalVolume(
                ExternalVolumeBuilder.builder(volumeTemplate.getRelationshipTemplates().stream() // Find containerPath - a property of the relationship
                        .filter(paaSRelationshipTemplate -> paaSRelationshipTemplate.instanceOf("alien.relationships.MountDockerVolume")).findFirst()
                        .map(PaaSRelationshipTemplate::getTemplate).map(RelationshipTemplate::getProperties)
                        .map(relationshipProps -> ((ScalarPropertyValue) relationshipProps.get("container_path"))).map(ScalarPropertyValue::getValue)
                        .orElseThrow(() -> new InvalidArgumentException("A container path must be provided to mount a volume to a container.")))
                    .name(Optional.ofNullable(((ScalarPropertyValue) volumeTemplateProperties.get("volume_name")))
                            .map(ScalarPropertyValue::getValue).orElse(null)) // TODO: Should persist and manage volume names
//                    .size(Optional.ofNullable(((ScalarPropertyValue) volumeTemplateProperties.get("size")))
//                            .map(ScalarPropertyValue::getValue)
//                            .filter(s -> s.matches("^[1-9][0-9]*\\s(GiB|GB)$"))
//                            .map(s -> s.split("\\s")[0]).map(Integer::valueOf).orElse(null)) // FIXME: won't work for docker containers ATM)
                    .build()
            ).instances(1); // External volumes make the app not scalable
        });
        return appBuilder;
    }

    /**
     * Search for a property of a capability being required as a target of a relationship.
     * 
     * @param paaSNodeTemplate The source node of the relationships, wich defines the requirement.
     * @param paaSTopology the topology the node belongs to.
     * @param params the function parameters, e.g. the requirement name & property name to lookup.
     * @return a String representing the property value.
     */
    private Optional<String> getPropertyFromReqTarget(PaaSNodeTemplate paaSNodeTemplate, PaaSTopology paaSTopology, FunctionPropertyValue params, String deploymentID) {
        // Search for the requirement's target by filter the relationships' templates of this node.
        // If a target is found, then lookup for the given property name in its capabilities.
        // For Docker containers X Marathon, the orchestrator replaces the PORT and IP_ADDRESS by the target's service port and the load balancer hostname
        // respectively.
        String requirementName = params.getCapabilityOrRequirementName();
        String propertyName = params.getElementNameToFetch();

        return paaSNodeTemplate.getRelationshipTemplates().stream()
                .filter(item -> paaSNodeTemplate.getId().equals(item.getSource()) && requirementName.equals(item.getTemplate().getRequirementName()))
                .findFirst() // Find the first relationship template which fulfills the given requirement, for this source
                .map(relationshipTemplate -> {
                    final String target = relationshipTemplate.getTemplate().getTarget();
                    final String targetedCapabilityName = relationshipTemplate.getTemplate().getTargetedCapabilityName();

                    // Nominal case : get the requirement's targeted capability property using the relationship as source.
                    FunctionPropertyValue functionPropertyValue =
                            new FunctionPropertyValue(params.getFunction(), Lists.newArrayList("TARGET", targetedCapabilityName, propertyName));

                    if (relationshipTemplate.instanceOf("tosca.relationships.ConnectsTo")) {
                        // Special marathon case: use service ports if the "Port" property is required.
                        final String endpointID = deploymentID + "/" + target.toLowerCase() + "/" + targetedCapabilityName;
                        if ("port".equalsIgnoreCase(propertyName)) {
                            // Retrieve service port if exists - if not, get capability value (for use cases where ports are statically defined)
                            // Service ports are mapped using the targetName + capabilityName
                            return Optional.ofNullable(mapPortEndpoints.get(endpointID)).map(String::valueOf)
                                    .orElse(FunctionEvaluator.evaluateGetPropertyFunction(functionPropertyValue, relationshipTemplate, paaSTopology.getAllNodes()));
                        } else if ("ip_address".equalsIgnoreCase(propertyName))
                            // Special marathon case: return marathon-lb hostname if an ip_address is required.
                            // If there is no service port, we return <target_app_id>.marathon.mesos for DNS resolution
                            return mapPortEndpoints.get(endpointID) != null ?
                                    "marathon-lb.marathon.mesos" :
                                    target.toLowerCase() + "." + deploymentID + ".marathon.mesos";
                    }
                    // Nominal case
                    return FunctionEvaluator.evaluateGetPropertyFunction(functionPropertyValue, relationshipTemplate, paaSTopology.getAllNodes());
                });
    }

}
