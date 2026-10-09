package com.ltplatform.settings.web;

import com.ltplatform.config.LtPlatformProperties;
import com.ltplatform.settings.service.SettingsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
    private final SettingsService settings;
    private final LtPlatformProperties props;

    public SettingsController(SettingsService settings, LtPlatformProperties props) {
        this.settings = settings;
        this.props = props;
    }

    @GetMapping
    public Map<String, Object> getAll() {
        return Map.of(
                "bitbucket", settings.getPublic("bitbucket"),
                "storage", Map.of(
                        "artifactRoot", props.getArtifactRoot(),
                        "logRoot", props.getLogRoot(),
                        "jmeterBundlePath", props.getJmeterBundlePath(),
                        "agentBinaryPath", props.getAgentBinaryPath()
                ),
                "grpc", Map.of(
                        "port", props.getGrpc().getPort(),
                        "certsDir", props.getGrpc().getCertsDir()
                ),
                "retention", settings.getPublic("retention"),
                "jmeter", settings.getPublic("jmeter")
        );
    }

    @PutMapping("/bitbucket")
    public Map<String, Object> putBitbucket(@RequestBody Map<String, Object> body) {
        settings.putMap("bitbucket", body);
        return settings.getPublic("bitbucket");
    }

    @PutMapping("/retention")
    public Map<String, Object> putRetention(@RequestBody Map<String, Object> body) {
        settings.putMap("retention", body);
        return settings.getPublic("retention");
    }

    @PutMapping("/jmeter")
    public Map<String, Object> putJmeter(@RequestBody Map<String, Object> body) {
        settings.putMap("jmeter", body);
        return settings.getPublic("jmeter");
    }
}
