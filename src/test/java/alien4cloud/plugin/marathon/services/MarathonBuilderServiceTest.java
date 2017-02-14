package alien4cloud.plugin.marathon.services;

import alien4cloud.plugin.marathon.service.MarathonMappingService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import alien4cloud.plugin.marathon.service.MarathonBuilderService;

/**
 * @author Adrian Fraisse
 */
@RunWith(SpringJUnit4ClassRunner.class)
public class MarathonBuilderServiceTest {

    private MarathonBuilderService builderService;
    private MarathonMappingService mockMappingService;

    @Before
    public void setUp() {
        mockMappingService = Mockito.mock(MarathonMappingService.class);
        builderService = new MarathonBuilderService(mockMappingService);
    }

    @Test
    public void testBuildSingleNodeTopology() {

    }

    @Test
    public void testBuildNodeWithVolumeTopology() {

    }

    @Test
    public void testBuildNodeWithBridgeNetworkingTopology() {

    }

    @Test
    public void testBuildNodeWithHostNetworkingTopology() {

    }

    @Test
    public void testBuildNodesWithRelationshipTopology() {

    }

    @Test
    public void testBuildNodeWithUserProperties() {

    }

    @Test
    public void testBuildComplexTopology() {

    }
}
