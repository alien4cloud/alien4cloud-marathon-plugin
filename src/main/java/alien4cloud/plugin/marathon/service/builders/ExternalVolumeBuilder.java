package alien4cloud.plugin.marathon.service.builders;

import org.springframework.util.StringUtils;

import mesosphere.marathon.client.model.v2.ExternalVolume;

/**
 * @author Adrian Fraisse
 */
public class ExternalVolumeBuilder {
    private ExternalVolume volume;

    private ExternalVolumeBuilder(String containerPath) {
        volume = new ExternalVolume();
        volume.setContainerPath(containerPath);
        volume.setDriver("rexray");
        volume.setMode("RW");
    }

    public static ExternalVolumeBuilder builder(String containerPath) {
        return new ExternalVolumeBuilder(containerPath);
    }

    public ExternalVolume build() {
        assert !StringUtils.isEmpty(volume.getContainerPath());
        return volume;
    }

    public ExternalVolumeBuilder name(String name) {
        volume.setName(name);
        return this;
    }

    public ExternalVolumeBuilder size(Integer size) {
        volume.setSize(size);
        return this;
    }

}
