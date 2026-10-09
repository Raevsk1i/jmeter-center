package com.ltplatform.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ltplatform.generator.domain.Generator;
import org.junit.jupiter.api.Test;

class GeneratorVersionClipTest {

    @Test
    void clipsLongJavaAndJmeterVersions() {
        Generator g = new Generator();
        String longJava = "openjdk version \"21.0.10\" 2026-01-20 LTS " + "x".repeat(600);
        String longJmeter = "Apache JMeter Version 5.6.3 " + "y".repeat(600);
        g.setJavaVersion(longJava);
        g.setJmeterVersion(longJmeter);
        g.setAgentVersion("0.1.0");
        assertEquals(512, g.getJavaVersion().length());
        assertEquals(512, g.getJmeterVersion().length());
        assertEquals("0.1.0", g.getAgentVersion());
        assertTrue(g.getJavaVersion().startsWith("openjdk version"));
    }
}
