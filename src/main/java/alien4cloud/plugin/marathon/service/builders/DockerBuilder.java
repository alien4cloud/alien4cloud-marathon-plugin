package alien4cloud.plugin.marathon.service.builders;

import com.google.common.collect.Lists;
import mesosphere.marathon.client.model.v2.Docker;
import mesosphere.marathon.client.model.v2.Parameter;
import mesosphere.marathon.client.model.v2.Port;

/**
 * @author Adrian Fraisse
 */
public class DockerBuilder {
    final Docker docker;
    final AppBuilder parentBuilder;

    private DockerBuilder(AppBuilder parentBuilder) {
        this.docker = new Docker();
        this.parentBuilder = parentBuilder;
    }

    public static DockerBuilder builder(AppBuilder parentBuilder) {
        return new DockerBuilder(parentBuilder);
    }

    public Docker build() {
        return docker;
    }

    public DockerBuilder image(final String image) {
        this.docker.setImage(image);
        return this;
    }

    public DockerBuilder option(final String key, final String value) {
        if (this.docker.getParameters() == null) {
            this.docker.setParameters(Lists.newArrayList());
        }
        this.docker.getParameters().add(new Parameter(key, value));
        return this;
    }

    public DockerBuilder bridgeNetworking() {
        this.docker.setNetwork("BRIDGE");
        return this;
    }

    public DockerBuilder hostNetworking() {
        this.docker.setNetwork("HOST");
        return this;
    }

    public PortBuilder portMapping() {
        if (this.docker.getPortMappings() == null)
            this.docker.setPortMappings(Lists.newArrayList());
        return PortBuilder.builder(this);
    }

    protected DockerBuilder setPortMapping(Port portMapping) {
        this.docker.getPortMappings().add(portMapping);
        return this;
    }
}
