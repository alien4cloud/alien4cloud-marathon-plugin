package alien4cloud.plugin.marathon.service.builders;

import org.springframework.util.StringUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import mesosphere.marathon.client.model.v2.*;

/**
 * @author Adrian Fraisse
 */
@Slf4j
public class AppBuilder {
    private App app;

    // Keeping IDs is useful when building dependencies
    @Getter
    private String parentGroupID;
    @Getter
    String appID;

    private AppBuilder(String id) {
        // Initialize the app as a docker container
        this.app = new App();
        appID = id.toLowerCase();
        Container container = new Container();
        app.setContainer(container);
    }
    
    public App build() {
        assert app.getContainer().getDocker() == null || !StringUtils.isEmpty(app.getContainer().getDocker().getImage());
        if (!appID.matches("^(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])|(\\.|\\.\\.)$")) {
            throw new IllegalArgumentException("Node ID is invalid. Allowed: lowercase letters, digits, hyphens, \".\", \"..\"");
        }
        app.setId(appID);
        return this.app;
    }

    public static AppBuilder builder(String id) {
        return new AppBuilder(id);
    }

    public AppBuilder parentGroupID(String parentGroupID) {
        this.parentGroupID = parentGroupID;
        return this;
    }

    public AppBuilder docker(String image) {
        Docker docker = new Docker();
        docker.setImage(image);
        app.getContainer().setDocker(docker);
        app.getContainer().setType("DOCKER");
        return this;
    }

    public AppBuilder instances(Integer instances) {
        app.setInstances(instances);
        return this;
    }

    public AppBuilder cpu(Double cpu) {
        app.setCpus(cpu);
        return this;
    }

    public AppBuilder mem(Double mem) {
        app.setMem(mem);
        return this;
    }

    public AppBuilder cmd(String cmd) {
        app.setCmd(cmd);
        return this;
    }

    public AppBuilder bridgeNetworking() {
        getDocker().setNetwork("BRIDGE");
        return this;
    }

    public AppBuilder hostNetworking() {
        // Only set to host if not already set as bridged
        if (getDocker().getNetwork() == null)
            getDocker().setNetwork("HOST");
        return this;
    }

    public AppBuilder portMapping(Port portMapping) {
        if (getDocker().getPortMappings() == null)
            getDocker().setPortMappings(Lists.newArrayList());
        getDocker().getPortMappings().add(portMapping);
        return this;
    }

    public AppBuilder internallyLoadBalanced() {
        app.addLabel("HAPROXY_GROUP", "internal");
        return this;
    }

    public AppBuilder envVariable(String varName, String value) {
        if (app.getEnv() == null) app.setEnv(Maps.newHashMap());
        app.getEnv().put(varName, value);
        return this;
    }

    public AppBuilder dockerOption(String optName, String value) {
        if (getDocker().getParameters() == null)
            getDocker().setParameters(Lists.newArrayList());
        getDocker().getParameters().add(new Parameter(optName, value));
        return this;
    }

    public AppBuilder dockerArgs(String... args) {
        if (app.getArgs() == null)
            app.setArgs(Lists.newArrayList(args));
        else
            app.getArgs().addAll(Lists.newArrayList(args));
        return this;
    }


    public AppBuilder input(final String key, final String value) {
        if (key.startsWith("ENV_")) {
            // Input as environment variable within the container
            if (app.getEnv() == null) app.setEnv(Maps.newHashMap());
            app.getEnv().put(key.replaceFirst("^ENV_", ""), value);
        } else if (key.startsWith("OPT_")) {
            // Input as a docker option given to the docker cli
            if (getDocker().getParameters() == null) getDocker().setParameters(Lists.newArrayList());
            getDocker().getParameters().add(new Parameter(key.replaceFirst("OPT_", ""), value));
        } else if (key.startsWith("ARG_")) {
            // Input as an argument to the docker run command
            if (app.getArgs() == null) app.setArgs(Lists.newArrayList());
            this.app.getArgs().add(value); // Arguments are unnamed
        } else
            log.warn("Unrecognized prefix for input : <" + key + ">");
        return this;
    }

    public AppBuilder dependency(String appId) {
        app.addDependency(appId);
        return this;
    }

    public AppBuilder defaultHealthCheck() {
        if (app.getHealthChecks() == null)
            app.setHealthChecks(Lists.newArrayList());
        HealthCheck healthCheck = new HealthCheck();
        healthCheck.setPortIndex(0);
        healthCheck.setProtocol("TCP");
        healthCheck.setGracePeriodSeconds(300);
        healthCheck.setIntervalSeconds(15);
        healthCheck.setMaxConsecutiveFailures(1);
        app.getHealthChecks().add(healthCheck);
        return this;
    }

    public AppBuilder externalVolume(ExternalVolume externalVolume) {
        if (app.getContainer().getVolumes() == null) app.getContainer().setVolumes(Lists.newArrayList());
        app.getContainer().getVolumes().add(externalVolume);
        return this;
    }

    private Docker getDocker() {
        return app.getContainer().getDocker();
    }

}
