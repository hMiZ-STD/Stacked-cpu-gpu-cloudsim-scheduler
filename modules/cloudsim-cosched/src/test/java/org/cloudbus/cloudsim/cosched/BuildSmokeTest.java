package org.cloudbus.cloudsim.cosched;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BuildSmokeTest {
    @Test
    void cloudsimAndJacksonOnClasspath() {
        assertNotNull(org.cloudbus.cloudsim.core.CloudSim.class);
        assertNotNull(new com.fasterxml.jackson.dataformat.yaml.YAMLMapper());
    }
}
