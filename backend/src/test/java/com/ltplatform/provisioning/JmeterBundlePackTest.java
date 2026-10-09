package com.ltplatform.provisioning;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ltplatform.provisioning.service.ProvisioningService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JmeterBundlePackTest {

    @TempDir
    Path temp;

    @Test
    void packsDirectoryContentsIntoTgz() throws Exception {
        Path bundle = temp.resolve("jmeter");
        Files.createDirectories(bundle.resolve("bin"));
        Files.writeString(bundle.resolve("bin/jmeter"), "#!/bin/sh\necho stub\n");
        Files.writeString(bundle.resolve("README"), "hello");

        Path archive = temp.resolve("out.tgz");
        ProvisioningService.packDirectoryToTgz(bundle, archive);
        assertTrue(Files.size(archive) > 20);

        Path extract = temp.resolve("extract");
        Files.createDirectories(extract);
        Process p = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", extract.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(Files.isRegularFile(extract.resolve("bin/jmeter")));
        assertTrue(Files.isRegularFile(extract.resolve("README")));
    }
}
