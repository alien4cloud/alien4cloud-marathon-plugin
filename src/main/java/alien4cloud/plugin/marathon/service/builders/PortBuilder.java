package alien4cloud.plugin.marathon.service.builders;

import mesosphere.marathon.client.model.v2.Port;

/**
 * @author Adrian Fraisse
 */
public class PortBuilder {
    final Port port;

    private PortBuilder() {
        this.port = new Port(0);
    }

    public static PortBuilder builder() {
        return new PortBuilder();
    }

    public Port build() {
        return port;
    }

    public PortBuilder tcp() {
        this.port.setProtocol("tcp");
        return this;
    }

    public PortBuilder hostPort(final Integer port) {
        this.port.setHostPort(port);
        return this;
    }

    public PortBuilder containerPort(final Integer port) {
        this.port.setContainerPort(port);
        return this;
    }

    public PortBuilder servicePort(final Integer port) {
        this.port.setServicePort(port);
        return this;
    }

}
