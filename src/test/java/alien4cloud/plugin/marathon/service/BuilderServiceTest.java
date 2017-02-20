package alien4cloud.plugin.marathon.service;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.alien4cloud.tosca.model.definitions.*;
import org.alien4cloud.tosca.model.templates.*;
import org.alien4cloud.tosca.model.types.RelationshipType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import alien4cloud.model.deployment.Deployment;
import alien4cloud.paas.model.PaaSNodeTemplate;
import alien4cloud.paas.model.PaaSRelationshipTemplate;
import alien4cloud.paas.model.PaaSTopology;
import alien4cloud.paas.model.PaaSTopologyDeploymentContext;
import mesosphere.marathon.client.model.v2.*;

/**
 * @author Adrian Fraisse
 */
public class BuilderServiceTest {

    private BuilderService builderService;
    private MappingService mockMappingService;

    @Before
    public void setUp() {
        mockMappingService = mock(MappingService.class);
        builderService = new BuilderService(mockMappingService);
    }

    @After
    public void tearDown() {
        verify(mockMappingService).registerGroupMapping("test-marathon-deployment", "mock-alien-deployment-id");
    }

    @Test
    public void testBuildSingleNodeTopology() {
        NodeTemplate template = simpleNodeTemplate();

        // Build test topology
        PaaSNodeTemplate singleNodeTemplate = new PaaSNodeTemplate("Single-Node-Template", template);
        singleNodeTemplate.setInterfaces(template.getInterfaces());
        singleNodeTemplate.setScalingPolicy(new ScalingPolicy(1, 1, 1));
        PaaSTopologyDeploymentContext context = preparePaaSContext(Lists.newArrayList(singleNodeTemplate), null);

        final Group groupDefinition = builderService.buildGroupDefinition(context);
        assertNotNull("An alien deployment is converted into a Marathon group", groupDefinition);
        assertEquals("The group's id matches the deployment id, lower cased","test-marathon-deployment", groupDefinition.getId());
        assertNull("Inner groups are not supported", groupDefinition.getGroups());
        assertNull("No inner groups implies there should never have dependencies at the group level", groupDefinition.getDependencies());
        assertNotNull("Each node in the topology is represented by an App in the group", groupDefinition.getApps());
        assertEquals("One node eq. one app",1, groupDefinition.getApps().size());

        final App appDefinition = groupDefinition.getApps().iterator().next();
        // App basics
        assertEquals("The app's id matches the node id, lower cased","single-node-template", appDefinition.getId());
        assertEquals("CPU requirement is a property of the node", Double.valueOf(1.0), appDefinition.getCpus());
        assertEquals("MEM requirement is a property of the node", Double.valueOf(256.0), appDefinition.getMem());
        assertNull("Dependencies modify deployment wkfl - no relationships eg. no dependencies", appDefinition.getDependencies());
        assertNull("Env variables can be defined as user properties or as create operation inputs", appDefinition.getEnv());
        assertNotNull("A marathon app always has a container - either mesos or docker", appDefinition.getContainer());
        assertNotNull("The plugin only supports Docker containers atm", appDefinition.getContainer().getDocker());
        assertEquals("Type should be DOCKER","DOCKER", appDefinition.getContainer().getType());
        assertNull(appDefinition.getContainer().getVolumes());
        assertEquals("Internal load balancing within the cluster","internal", appDefinition.getLabels().get("HAPROXY_GROUP"));
        assertEquals("App's initial instances value matches the node scaling policy", Integer.valueOf(1), appDefinition.getInstances());

        // Docker definition
        Docker dockerDefinition = appDefinition.getContainer().getDocker();
        assertEquals("The app's docker definition must have a docker-img","docker-img", dockerDefinition.getImage());
        assertEquals("HOST networking is used when containers ports are directly exposed throughout marathon","HOST", dockerDefinition.getNetwork());
        assertNull("Parameters to the docker run cmd are defined at the user level or at as create operation inputs", dockerDefinition.getParameters());
        Port portDefinition = dockerDefinition.getPortMappings().iterator().next();
        assertEquals("For host networking, the container port should be set statically", Integer.valueOf(12345), portDefinition.getContainerPort());
        assertNull(portDefinition.getHostPort());
        assertNull(portDefinition.getProtocol());
        assertEquals("A marathonLB service port is always attributed by the plugin", Integer.valueOf(10000), portDefinition.getServicePort());

        // Healthcheck
        HealthCheck healthCheck = appDefinition.getHealthChecks().get(0);
        assertNotNull("A default http healthcheck is setup for each app", healthCheck);

        verify(mockMappingService).registerAppMapping("test-marathon-deployment", "single-node-template", "Single-Node-Template");
    }



    @Test
    public void testBuildNodeWithVolumeTopology() throws Exception {
        // Create a external volume node template
        final NodeTemplate volume = new NodeTemplate();
        volume.setName("extvolume");
        volume.setProperties(Maps.newHashMap());
        volume.getProperties().put("size", new ScalarPropertyValue("1 GB"));
        volume.getProperties().put("volume_name", new ScalarPropertyValue("extdockervolume"));
        volume.setType("alien.nodes.DockerExtVolume");
        volume.setCapabilities(Maps.newHashMap());
        volume.setRequirements(Maps.newHashMap());
        final Requirement attachmentRequirement = new Requirement();
        attachmentRequirement.setType("alien.capabilities.DockerVolumeAttachment");
        volume.getRequirements().put("attachment", attachmentRequirement);

        // Mount the volume to a node using the MountDockerVolume relationship
        volume.setRelationships(Maps.newHashMap());
        final RelationshipTemplate relationshipTemplate = new RelationshipTemplate();
        relationshipTemplate.setType("alien.relationships.MountDockerVolume");
        relationshipTemplate.setTarget("Single-Node-Template");
        relationshipTemplate.setTargetedCapabilityName("attach");
        relationshipTemplate.setRequirementName("attachment");
        relationshipTemplate.setProperties(Maps.newHashMap());
        relationshipTemplate.getProperties().put("container_path", new ScalarPropertyValue("path/volume"));
        volume.getRelationships().put("mount-volume-rel", relationshipTemplate);

        final PaaSNodeTemplate paasVolumeTemplate = new PaaSNodeTemplate("extvolume", volume);
        paasVolumeTemplate.setRelationshipTemplates(volume.getRelationships().values().stream().map(template -> new PaaSRelationshipTemplate("mock-relationship", template, volume.getName())).collect(Collectors.toList()));
        final RelationshipType indexedToscaElement = new RelationshipType();
        indexedToscaElement.setElementId("alien.relationships.MountDockerVolume");
        paasVolumeTemplate.getRelationshipTemplates().get(0).setIndexedToscaElement(indexedToscaElement);

        final NodeTemplate template = simpleNodeTemplate();
        PaaSNodeTemplate singleNodeTemplate = new PaaSNodeTemplate("Single-Node-Template", template);
        singleNodeTemplate.setInterfaces(template.getInterfaces());
        singleNodeTemplate.setScalingPolicy(new ScalingPolicy(1, 5, 3));

        PaaSTopologyDeploymentContext context = preparePaaSContext(Lists.newArrayList(singleNodeTemplate), Lists.newArrayList(paasVolumeTemplate));

        final App appDefinition = builderService.buildGroupDefinition(context).getApps().iterator().next();

        assertEquals("When an external volume is attached to it, apps can only be scaled to 1 instance", Integer.valueOf(1), appDefinition.getInstances());
        final ExternalVolume volDef = (ExternalVolume) appDefinition.getContainer().getVolumes().iterator().next();
        assertEquals("volumes are read/write by default","RW", volDef.getMode());
        assertEquals("The volume's container path matches the mounting relationship property","path/volume", volDef.getContainerPath());

        // workaround ExternalVumeInfo has no getters
        final Gson gson = new Gson();
        final JsonObject external = gson.fromJson(gson.toJson(volDef), JsonObject.class).getAsJsonObject("external");
        assertEquals("The volume's size matches the volume node property", Integer.valueOf(1), Integer.valueOf(external.get("size").getAsInt()));
        assertEquals("The volume's name matches the volume node property","extdockervolume", external.get("name").getAsString());
        assertEquals("External volumes use the docker volume driver isolator","dvdi", external.get("provider").getAsString());
        assertEquals("The plugin leverages the rexray driver","rexray", external.getAsJsonObject("options").get("dvdi/driver").getAsString());
    }

    @Test
    public void testBuildNodeWithBridgeNetworkingTopology() {
        final NodeTemplate template = simpleNodeTemplate();

        // Set a docker_bridge_port_mapping property on the capability
        final Capability bridgedEndpoint = new Capability();
        bridgedEndpoint.setType("capabilities.endpoint");
        bridgedEndpoint.setProperties(Maps.newHashMap());
        bridgedEndpoint.getProperties().put("docker_bridge_port_mapping", new ScalarPropertyValue("54321"));
        bridgedEndpoint.getProperties().put("port", new ScalarPropertyValue("6789"));
        template.getCapabilities().put("bridged_cap", bridgedEndpoint);


        // Build test topology
        PaaSNodeTemplate singleNodeTemplate = new PaaSNodeTemplate("Single-Node-Template", template);
        singleNodeTemplate.setInterfaces(template.getInterfaces());
        singleNodeTemplate.setScalingPolicy(new ScalingPolicy(1, 5, 3));
        PaaSTopologyDeploymentContext context = preparePaaSContext(Lists.newArrayList(singleNodeTemplate), null);

        final App appDefinition = builderService.buildGroupDefinition(context).getApps().iterator().next();

        assertEquals(Integer.valueOf(3), appDefinition.getInstances());

        /* If on port is bridged then all ports are bridged */
        final Docker dockerDefinition = appDefinition.getContainer().getDocker();
        assertEquals("If a container has one or more bridged port mappings, then the container network is bridged","BRIDGE", dockerDefinition.getNetwork());

        /* Verify port mappings */
        final Iterator<Port> portIterator = dockerDefinition.getPortMappings().iterator();
        final Port firstPortMapping = portIterator.next();
        assertEquals("If only a container port is prodived, the host port will be randomized by marathon", Integer.valueOf(12345), firstPortMapping.getContainerPort());
        assertEquals(Integer.valueOf(10000), firstPortMapping.getServicePort());
        final Port secondPortMapping = portIterator.next();
        assertEquals("Bridge ports use TCP","tcp", secondPortMapping.getProtocol());
        assertEquals("Container port definition matches the connects to relationship properties", Integer.valueOf(6789), secondPortMapping.getContainerPort());
        assertEquals("Container port definition matches the connects to relationship properties", Integer.valueOf(54321), secondPortMapping.getHostPort());
        assertEquals("A service port is attributed for bridged containers also", Integer.valueOf(10001), secondPortMapping.getServicePort());

        verify(mockMappingService).registerAppMapping("test-marathon-deployment", "single-node-template", "Single-Node-Template");
    }

    @Test
    public void testBuildNodesWithRelationshipTopology() {
        NodeTemplate sourceNode = simpleNodeTemplate();
        sourceNode.setName("Source-Template");
        sourceNode.setRequirements(Maps.newHashMap());
        final Requirement requirement = new Requirement();
        sourceNode.getRequirements().put("endpoint_req", requirement);
        NodeTemplate targetNode = simpleNodeTemplate();
        targetNode.setName("Target-Template");
        targetNode.getCapabilities().get("endpoint_cap").getProperties().put("opt", new ScalarPropertyValue("abcd"));

        // Define the relationship
        sourceNode.setRelationships(Maps.newHashMap());
        final RelationshipTemplate relationshipTemplate = new RelationshipTemplate();
        relationshipTemplate.setType("tosca.relationships.Connectsto");
        relationshipTemplate.setTarget("Target-Template");
        relationshipTemplate.setTargetedCapabilityName("endpoint_cap");
        relationshipTemplate.setRequirementName("endpoint_req");
        sourceNode.getRelationships().put("mock-relationship", relationshipTemplate);

        // Define operation inputs
        Operation createOp = sourceNode.getInterfaces().get("tosca.interfaces.node.lifecycle.Standard").getOperations().get("create");
        createOp.setInputParameters(Maps.newHashMap());
        createOp.getInputParameters().put("ENV_INPUT_IP", new FunctionPropertyValue("get_property", Lists.newArrayList("REQ_TARGET", "endpoint_req", "ip_address")));
        createOp.getInputParameters().put("ARG_INPUT_PORT", new FunctionPropertyValue("get_property", Lists.newArrayList("REQ_TARGET", "endpoint_req", "port")));
        createOp.getInputParameters().put("OPT_OPT", new FunctionPropertyValue("get_property", Lists.newArrayList("REQ_TARGET", "endpoint_req", "opt")));


        // Build test topology
        PaaSNodeTemplate sourceTemplate = new PaaSNodeTemplate("Source-Template", sourceNode);
        sourceTemplate.setInterfaces(sourceNode.getInterfaces());
        sourceTemplate.setScalingPolicy(new ScalingPolicy(1, 5, 1));
        sourceTemplate.setRelationshipTemplates(sourceNode.getRelationships().values().stream().map(template -> new PaaSRelationshipTemplate("mock-relationship", template, sourceNode.getName())).collect(Collectors.toList()));
        final RelationshipType indexedToscaElement = new RelationshipType();
        indexedToscaElement.setElementId("tosca.relationships.ConnectsTo");
        sourceTemplate.getRelationshipTemplates().get(0).setIndexedToscaElement(indexedToscaElement);

        PaaSNodeTemplate targetTemplate = new PaaSNodeTemplate("Target-Template", targetNode);
        targetTemplate.setInterfaces(targetNode.getInterfaces());
        targetTemplate.setScalingPolicy(new ScalingPolicy(1, 5, 1));
        targetTemplate.setRelationshipTemplates(Lists.newArrayList(sourceTemplate.getRelationshipTemplates()));

        PaaSTopologyDeploymentContext context = preparePaaSContext(Lists.newArrayList(sourceTemplate, targetTemplate), null);

        // Verify dependencies
        final Group groupDefinition = builderService.buildGroupDefinition(context);
        assertEquals(2, groupDefinition.getApps().size());
        final Iterator<App> appIterator = groupDefinition.getApps().iterator();
        final App sourceApp = appIterator.next();
        final App targetApp = appIterator.next();
        assertEquals("The source app has a dependency toward the target app", "target-template", sourceApp.getDependencies().iterator().next());
        assertNull("The target has no dependencies toward the source", targetApp.getDependencies());

        // Verify input filling using the service discovery mechanism
        assertEquals("The plugin replaces an ip_address property mapping with marathonLb dns name","marathon-lb.marathon.mesos", sourceApp.getEnv().get("INPUT_IP"));
        assertEquals("The plugin replaces a port property mapping with the correct service port","10000", sourceApp.getArgs().get(0));
        assertEquals("Input prefixed by OPT_ is converted into a docker param", "OPT", sourceApp.getContainer().getDocker().getParameters().get(0).getKey());
        assertEquals("abcd", sourceApp.getContainer().getDocker().getParameters().get(0).getValue());

        verify(mockMappingService).registerAppMapping("test-marathon-deployment", "source-template", "Source-Template");
        verify(mockMappingService).registerAppMapping("test-marathon-deployment", "target-template", "Target-Template");
    }

    @Test
    public void testBuildNodeWithUserProperties() {
        NodeTemplate template = simpleNodeTemplate();

        List<Object> values = Lists.newArrayList();
        values.add("arg1");
        values.add("arg2");
        template.getProperties().put("docker_run_args", new ListPropertyValue(values));
        template.getProperties().put("docker_run_cmd", new ScalarPropertyValue("node server run"));
        final HashMap<String, Object> options = Maps.newHashMap();
        options.put("opt_name1", "opt_val1");
        options.put("opt_name2", "opt_val2");
        template.getProperties().put("docker_options", new ComplexPropertyValue(options));
        final HashMap<String, Object> env = Maps.newHashMap();
        env.put("env_name_1", "env_val1");
        env.put("env_name_2", "env_val2");
        template.getProperties().put("docker_env_vars", new ComplexPropertyValue(env));

        PaaSNodeTemplate paaSNodeTemplate = new PaaSNodeTemplate("app-id", template);
        paaSNodeTemplate.setInterfaces(template.getInterfaces());

        PaaSTopologyDeploymentContext context = preparePaaSContext(Lists.newArrayList(paaSNodeTemplate, paaSNodeTemplate), null);
        final App appDef = builderService.buildGroupDefinition(context).getApps().iterator().next();
        assertEquals("cmd args defined as node properties","arg1", appDef.getArgs().get(0));
        assertEquals("arg2", appDef.getArgs().get(1));
        assertEquals("docker run command", "node server run", appDef.getCmd());
        final Docker docker = appDef.getContainer().getDocker();
        assertEquals("docker paramaters defined as option properties","opt_name1", docker.getParameters().get(0).getKey());
        assertEquals("opt_val1", docker.getParameters().get(0).getValue());
        assertEquals("opt_name2", docker.getParameters().get(1).getKey());
        assertEquals("opt_val2", docker.getParameters().get(1).getValue());
        assertEquals("env variables defined as user properties","env_val1", appDef.getEnv().get("env_name_1"));
        assertEquals("env_val2", appDef.getEnv().get("env_name_2"));
    }

    private NodeTemplate simpleNodeTemplate() {
        NodeTemplate template = new NodeTemplate();

        template.setCapabilities(Maps.newHashMap());
        template.setInterfaces(Maps.newHashMap());
        template.setProperties(Maps.newHashMap());

        /* Properties */
        template.getProperties().put("cpu_share", new ScalarPropertyValue("1.0"));
        template.getProperties().put("mem_share", new ScalarPropertyValue("256.0"));

        /* Capabilities */
        Capability endpointCapa = new Capability();
        endpointCapa.setType("alien.capabilities.endpoint.Docker");
        endpointCapa.setProperties(Maps.newHashMap());
        endpointCapa.getProperties().put("port", new ScalarPropertyValue("12345"));
        template.getCapabilities().put("endpoint_cap", endpointCapa);
        Capability attach = new Capability();
        attach.setType("alien.capabilities.DockerVolumeAttachment");
        template.getCapabilities().put("attach", attach);

        /* Create operation */
        final Interface standardInt = new Interface();
        final Operation createOp = new Operation();
        createOp.setImplementationArtifact(new ImplementationArtifact("docker-img"));
        standardInt.setOperations(Maps.newHashMap());
        standardInt.getOperations().put("create", createOp);
        template.getInterfaces().put("tosca.interfaces.node.lifecycle.Standard", standardInt);
        return template;
    }

    private PaaSTopologyDeploymentContext preparePaaSContext(List<PaaSNodeTemplate> nonNatives, List<PaaSNodeTemplate> volumes) {
        PaaSTopologyDeploymentContext context = new PaaSTopologyDeploymentContext();
        Deployment deployment = new Deployment();
        PaaSTopology paaSTopology = new PaaSTopology();

        context.setDeployment(deployment);
        context.setPaaSTopology(paaSTopology);

        // Deployment
        deployment.setOrchestratorDeploymentId("test-marathon-deployment");
        deployment.setId("mock-alien-deployment-id");

        // PaasTopology
        paaSTopology.setNonNatives(nonNatives);
        paaSTopology.setVolumes(volumes);
        Map<String, PaaSNodeTemplate> allNodes = Maps.newHashMap();
        nonNatives.forEach(paaSNodeTemplate -> allNodes.put(paaSNodeTemplate.getId(), paaSNodeTemplate));
        paaSTopology.setAllNodes(allNodes);

        return context;
    }
}
