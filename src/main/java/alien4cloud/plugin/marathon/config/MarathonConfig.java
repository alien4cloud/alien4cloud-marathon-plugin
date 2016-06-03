package alien4cloud.plugin.marathon.config;

import alien4cloud.ui.form.annotation.FormProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * @author Adrian Fraisse
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@FormProperties({ "marathonURL", "mesosDNS" })
public class MarathonConfig {

    private String marathonURL;

    private String mesosDNS;
}
