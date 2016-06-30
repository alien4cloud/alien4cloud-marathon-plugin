package alien4cloud.plugin.marathon.service.builders;

import alien4cloud.paas.model.PaaSTopology;
import lombok.extern.log4j.Log4j;
import mesosphere.marathon.client.model.v2.App;
import mesosphere.marathon.client.model.v2.Container;
import mesosphere.marathon.client.model.v2.Docker;
import mesosphere.marathon.client.model.v2.Parameter;

/**
 * @author Adrian Fraisse
 */
@Log4j
public class AppBuilder {
    private final App app;
    private final PaaSTopology paaSTopology;

    private AppBuilder(PaaSTopology paaSTopology) {
        this.app = new App();
        this.paaSTopology = paaSTopology;
    }

    public App build() {
        return app;
    }

    public static AppBuilder builder(PaaSTopology paaSTopology) {
        return new AppBuilder(paaSTopology);
    }

    public AppBuilder id(final String id) {
        this.app.setId(id.toLowerCase());
        return this;
    }

    public AppBuilder input(final String key, final String value) {
        if (key.startsWith("ENV_")) {
            // Input as environment variable within the container
            this.app.getEnv().put(key.replaceFirst("^ENV_", ""), value);
        } else if (key.startsWith("OPT_")) {
            // Input as a docker option given to the docker cli
            this.app.getContainer().getDocker().getParameters().add(new Parameter(key.replaceFirst("OPT_", ""), value));
        } else if (key.startsWith("ARG_")) {
            // Input as an argument to the docker run command
            this.app.getArgs().add(value); // Arguments are unnamed
        } else
            log.warn("Unrecognized prefix for input : <" + key + ">");
        return this;
    }

    // Delegate to docker builder
    public DockerBuilder docker() {
        return DockerBuilder.builder(this);
    }

    protected AppBuilder setDocker(Docker docker) {
        final Container container = new Container();
        container.setType("DOCKER");
        container.setDocker(docker);
        this.app.setContainer(container);
        return this;
    }

}
