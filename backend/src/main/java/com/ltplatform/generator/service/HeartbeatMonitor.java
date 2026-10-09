package com.ltplatform.generator.service;

import com.ltplatform.agent.session.AgentSessionRegistry;
import com.ltplatform.config.LtPlatformProperties;
import com.ltplatform.generator.domain.Generator;
import com.ltplatform.generator.repo.GeneratorRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HeartbeatMonitor {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatMonitor.class);

    private final GeneratorRepository generators;
    private final GeneratorService generatorService;
    private final AgentSessionRegistry sessions;
    private final LtPlatformProperties props;
    private final GeneratorLogService genLogs;

    public HeartbeatMonitor(
            GeneratorRepository generators,
            GeneratorService generatorService,
            AgentSessionRegistry sessions,
            LtPlatformProperties props,
            GeneratorLogService genLogs
    ) {
        this.generators = generators;
        this.generatorService = generatorService;
        this.sessions = sessions;
        this.props = props;
        this.genLogs = genLogs;
    }

    @Scheduled(fixedDelayString = "15000")
    public void checkHeartbeats() {
        Instant threshold = Instant.now().minus(props.getHeartbeatTimeoutSeconds(), ChronoUnit.SECONDS);
        List<Generator> stale = generators.findStaleHeartbeats(threshold);
        for (Generator g : stale) {
            if (!sessions.isOnline(g.getId())) {
                log.warn("Generator {} heartbeat timeout -> OFFLINE (reservation retained if any)", g.getId());
                genLogs.warn(g.getId(), "AGENT_CONN", "HEARTBEAT_TIMEOUT",
                        "No heartbeat within " + props.getHeartbeatTimeoutSeconds()
                                + "s and agent session offline → status OFFLINE (reservation retained)");
                generatorService.markOffline(g.getId());
            }
        }
    }
}
