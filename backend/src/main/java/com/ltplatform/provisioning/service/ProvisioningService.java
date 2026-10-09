package com.ltplatform.provisioning.service;

import com.ltplatform.agent.session.AgentSessionRegistry;
import com.ltplatform.common.ApiException;
import com.ltplatform.config.LtPlatformProperties;
import com.ltplatform.generator.domain.Generator;
import com.ltplatform.generator.domain.GeneratorStatus;
import com.ltplatform.generator.domain.SshCredential;
import com.ltplatform.generator.dto.GeneratorDtos.ProvisionStepResponse;
import com.ltplatform.generator.repo.GeneratorRepository;
import com.ltplatform.generator.repo.SshCredentialRepository;
import com.ltplatform.generator.service.GeneratorLogService;
import com.ltplatform.provisioning.ssh.SshAgentTunnelService;
import com.ltplatform.provisioning.ssh.SshClientFactory;
import com.ltplatform.settings.service.SettingsService;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.schmizz.sshj.SSHClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProvisioningService {
    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);

    private final GeneratorRepository generators;
    private final SshCredentialRepository credentials;
    private final SshClientFactory ssh;
    private final LtPlatformProperties props;
    private final JdbcTemplate jdbc;
    private final GeneratorLogService genLogs;
    private final SettingsService settings;
    private final AgentSessionRegistry sessions;
    private final SshAgentTunnelService tunnels;

    public ProvisioningService(
            GeneratorRepository generators,
            SshCredentialRepository credentials,
            SshClientFactory ssh,
            LtPlatformProperties props,
            JdbcTemplate jdbc,
            GeneratorLogService genLogs,
            SettingsService settings,
            AgentSessionRegistry sessions,
            SshAgentTunnelService tunnels
    ) {
        this.generators = generators;
        this.credentials = credentials;
        this.ssh = ssh;
        this.props = props;
        this.jdbc = jdbc;
        this.genLogs = genLogs;
        this.settings = settings;
        this.sessions = sessions;
        this.tunnels = tunnels;
    }

    @Async("orchestrationExecutor")
    public void provisionAsync(UUID generatorId) {
        try {
            provision(generatorId);
        } catch (Exception e) {
            log.error("Provisioning failed for {}: {}", generatorId, e.getMessage(), e);
            markError(generatorId, e.getMessage());
        }
    }

    public void provision(UUID generatorId) throws Exception {
        // Brief retry: after-commit scheduling should make the row visible, but tolerate slow commit visibility.
        Generator g = waitForGenerator(generatorId, 10);
        SshCredential cred = credentials.findById(g.getSshCredentialId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "SSH credential missing"));

        updateStatus(g, GeneratorStatus.PREPARING, null);
        genLogs.info(generatorId, "PROVISION", "START",
                "Provisioning started for " + g.getHostname() + ":" + g.getSshPort() + " as " + g.getSshUser());

        // Prefer SSH reverse-tunnel so agents register without a publicly reachable controller.
        // Fallback advertise host is still logged for operators who expose gRPC directly.
        String advertiseHost = resolveControllerAdvertiseHost();
        int grpcPort = props.getGrpc().getPort();
        genLogs.info(generatorId, "PROVISION", "CONTROLLER",
                "Public advertise candidate " + advertiseHost + ":" + grpcPort
                        + " (SSH provision uses reverse tunnel 127.0.0.1:"
                        + SshAgentTunnelService.REMOTE_FORWARD_PORT + " → controller :" + grpcPort + ")");

        SSHClient client = null;
        boolean keepTunnel = false;
        try {
            var opened = tunnels.open(generatorId, g.getHostname(), g.getSshPort(), g.getSshUser(), cred);
            client = opened.client();
            String controllerAddr = opened.agentControllerAddr();
            credentials.save(cred);
            runStep(g, "CONNECT", "SSH connected + reverse tunnel " + controllerAddr);
            genLogs.info(generatorId, "PROVISION", "CONNECT",
                    "SSH connection established; agent will dial " + controllerAddr
                            + " (tunneled to controller gRPC " + grpcPort + ")");

            var os = execLogged(client, generatorId, "DETECT_OS",
                    "cat /etc/os-release | head -5; uname -m; id; sudo -n true 2>/dev/null; echo SUDO:$?", 30);
            runStep(g, "DETECT_OS", truncate(os.stdout().trim(), 500));

            runStep(g, "INSTALL_JAVA", "Ensuring Java 21");
            execLogged(client, generatorId, "INSTALL_JAVA", """
                    if ! java -version 2>&1 | grep -Eq 'version "21'; then
                      (sudo dnf install -y java-21-openjdk-devel || sudo yum install -y java-21-openjdk-devel || true)
                    fi
                    java -version 2>&1 | head -1
                    """, 300);

            runStep(g, "INSTALL_JMETER", "Copying JMeter bundle");
            Path bundle = Path.of(props.getJmeterBundlePath());
            execLogged(client, generatorId, "INSTALL_JMETER",
                    "sudo mkdir -p /opt/lt-jmeter && sudo chown $(whoami) /opt/lt-jmeter || true", 30);
            installJmeterBundle(client, generatorId, bundle);

            runStep(g, "CREATE_DIRS", "Creating agent directories");
            execLogged(client, generatorId, "CREATE_DIRS", """
                    sudo useradd -r -s /sbin/nologin lt-agent 2>/dev/null || true
                    sudo mkdir -p /opt/lt-agent /var/lib/lt-agent/workspaces /var/lib/lt-agent/state /var/log/lt-agent
                    sudo chown -R lt-agent:lt-agent /opt/lt-agent /var/lib/lt-agent /var/log/lt-agent
                    """, 60);

            runStep(g, "INSTALL_AGENT", "Installing Go agent");
            Path agentBin = Path.of(props.getAgentBinaryPath());
            if (!Files.isRegularFile(agentBin)) {
                String msg = "Agent binary missing at " + agentBin
                        + " — build/push lt-agent into the controller image or set LT_AGENT_BINARY";
                genLogs.error(generatorId, "PROVISION", "INSTALL_AGENT", msg);
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, msg);
            }
            genLogs.info(generatorId, "PROVISION", "INSTALL_AGENT",
                    "Uploading agent binary from " + agentBin + " (" + Files.size(agentBin) + " bytes)");
            byte[] bin = Files.readAllBytes(agentBin);
            ssh.upload(client, bin, "/tmp/lt-agent");
            var install = execLogged(client, generatorId, "INSTALL_AGENT",
                    "sudo mv /tmp/lt-agent /opt/lt-agent/lt-agent && sudo chmod +x /opt/lt-agent/lt-agent"
                            + " && sudo chown lt-agent:lt-agent /opt/lt-agent/lt-agent"
                            + " && /opt/lt-agent/lt-agent -h 2>&1 | head -3 || file /opt/lt-agent/lt-agent || true", 30);
            if (!install.ok()) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Failed to install agent binary: " + truncate(install.stderr(), 500));
            }

            String bootstrap = UUID.randomUUID().toString();
            String unit = """
                    [Unit]
                    Description=LT Platform Go Agent
                    After=network-online.target
                    Wants=network-online.target

                    [Service]
                    Type=simple
                    User=lt-agent
                    Environment=LT_CONTROLLER=%s
                    Environment=LT_GENERATOR_ID=%s
                    Environment=LT_BOOTSTRAP_TOKEN=%s
                    Environment=LT_AGENT_ID=%s
                    Environment=LT_WORK_ROOT=/var/lib/lt-agent/workspaces
                    Environment=LT_STATE_DIR=/var/lib/lt-agent/state
                    Environment=LT_JMETER_HOME=/opt/lt-jmeter
                    Environment=LT_AGENT_VERSION=0.1.0
                    ExecStart=/opt/lt-agent/lt-agent
                    Restart=always
                    RestartSec=5
                    StandardOutput=journal
                    StandardError=journal

                    [Install]
                    WantedBy=multi-user.target
                    """.formatted(
                    controllerAddr,
                    g.getId(),
                    bootstrap,
                    "agent-" + g.getId()
            );
            runStep(g, "CONFIGURE", "Writing systemd unit → " + controllerAddr);
            genLogs.info(generatorId, "PROVISION", "CONFIGURE",
                    "systemd unit LT_CONTROLLER=" + controllerAddr + " LT_GENERATOR_ID=" + g.getId());
            ssh.upload(client, unit.getBytes(), "/tmp/lt-agent.service");
            var configure = execLogged(client, generatorId, "CONFIGURE",
                    "sudo mv /tmp/lt-agent.service /etc/systemd/system/lt-agent.service"
                            + " && sudo systemctl daemon-reload"
                            + " && sudo systemctl enable lt-agent"
                            + " && sudo systemctl restart lt-agent"
                            + " && sleep 1"
                            + " && sudo systemctl is-active lt-agent"
                            + " && sudo systemctl status lt-agent --no-pager -l | head -40", 90);
            if (!configure.ok() && !configure.stdout().contains("active")) {
                String journal = execLogged(client, generatorId, "CONFIGURE",
                        "sudo journalctl -u lt-agent -n 80 --no-pager || true", 30).stdout();
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Failed to start lt-agent systemd unit. journal: " + truncate(journal, 1500));
            }

            runStep(g, "REGISTER_WAIT", "Waiting for agent gRPC registration at " + controllerAddr);
            g.setProvisionStep("REGISTER_WAIT");
            generators.save(g);
            genLogs.info(generatorId, "PROVISION", "REGISTER_WAIT",
                    "Agent service started; waiting up to " + props.getAgentRegisterWaitSeconds()
                            + "s for gRPC registration");

            boolean registered = waitForAgentRegistration(client, generatorId, props.getAgentRegisterWaitSeconds());
            if (!registered) {
                String journal = execLogged(client, generatorId, "REGISTER_WAIT",
                        "sudo journalctl -u lt-agent -n 100 --no-pager || true", 30).stdout();
                genLogs.error(generatorId, "PROVISION", "REGISTER_WAIT",
                        "Agent did not register within timeout via SSH reverse tunnel "
                                + controllerAddr + ". journal tail:\n" + truncate(journal, 3000));
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT,
                        "Agent started but did not register within "
                                + props.getAgentRegisterWaitSeconds()
                                + "s (SSH reverse tunnel " + controllerAddr
                                + "). See generator diagnostic logs / journalctl.");
            }

            // Ensure DB status matches success (registration may have raced with our in-memory entity).
            Generator finalized = generators.findById(generatorId).orElse(g);
            finalized.setStatus(GeneratorStatus.AVAILABLE);
            finalized.setProvisionStep("READY");
            finalized.setProvisionError(null);
            finalized.touch();
            generators.save(finalized);
            g = finalized;

            runStep(g, "VERIFY", "Agent registered; generator AVAILABLE");
            genLogs.info(generatorId, "PROVISION", "VERIFY",
                    "Provisioning complete — agent connected and generator is AVAILABLE");
            keepTunnel = true; // keep reverse tunnel so the agent session stays up
        } catch (Exception e) {
            genLogs.error(generatorId, "PROVISION", "FAILED", e.getMessage());
            throw e;
        } finally {
            if (!keepTunnel) {
                tunnels.close(generatorId);
            }
        }
    }

    public Map<String, Object> checkConnectivity(UUID generatorId) {
        Generator g = generators.findById(generatorId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Generator not found"));
        SshCredential cred = credentials.findById(g.getSshCredentialId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "SSH credential missing"));
        genLogs.info(generatorId, "PROVISION", "CHECK",
                "Connectivity check to " + g.getHostname() + ":" + g.getSshPort());
        try (SSHClient client = ssh.connect(g.getHostname(), g.getSshPort(), g.getSshUser(), cred)) {
            var result = ssh.exec(client, "echo OK; uname -a", 15);
            credentials.save(cred);
            genLogs.append(generatorId, "PROVISION", result.ok() ? "INFO" : "ERROR", "CHECK",
                    "SSH check exit=" + result.exitCode(),
                    Map.of("stdout", truncate(result.stdout(), 2000), "stderr", truncate(result.stderr(), 2000)));
            return Map.of("ok", result.ok(), "stdout", result.stdout(), "stderr", result.stderr());
        } catch (Exception e) {
            genLogs.error(generatorId, "PROVISION", "CHECK", "SSH check failed: " + e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<ProvisionStepResponse> listSteps(UUID generatorId) {
        return jdbc.query(
                "SELECT step_name, status, message, started_at, finished_at FROM provisioning_steps WHERE generator_id = ? ORDER BY id",
                (rs, i) -> new ProvisionStepResponse(
                        rs.getString("step_name"),
                        rs.getString("status"),
                        rs.getString("message"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") != null ? rs.getTimestamp("finished_at").toInstant() : null
                ),
                generatorId
        );
    }

    /**
     * Host/IP written into remote agent systemd units. Order:
     * 1) Settings controller.advertiseHost
     * 2) LT_CONTROLLER_HOST / ltplatform.controller-advertise-host
     * 3) Best-effort non-loopback IPv4 of this process
     */
    String resolveControllerAdvertiseHost() {
        Map<String, Object> cfg = settings.getMap("controller");
        Object fromSettings = cfg.get("advertiseHost");
        if (fromSettings != null && !String.valueOf(fromSettings).isBlank()) {
            return String.valueOf(fromSettings).trim();
        }
        if (props.getControllerAdvertiseHost() != null && !props.getControllerAdvertiseHost().isBlank()) {
            return props.getControllerAdvertiseHost().trim();
        }
        String detected = detectOutboundIpv4();
        if (detected != null) {
            log.warn("LT_CONTROLLER_HOST unset; using detected address {} for remote agents", detected);
            return detected;
        }
        return "127.0.0.1";
    }

    private boolean waitForAgentRegistration(SSHClient client, UUID generatorId, int timeoutSeconds)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            // Require persisted AVAILABLE — an in-memory session alone can appear if DB save failed.
            Generator refreshed = generators.findById(generatorId).orElse(null);
            boolean available = refreshed != null
                    && refreshed.getStatus() == GeneratorStatus.AVAILABLE
                    && refreshed.getLastHeartbeatAt() != null;
            boolean sessionOnline = sessions.isOnline(generatorId);
            if (available) {
                genLogs.info(generatorId, "PROVISION", "REGISTER_WAIT",
                        "Generator AVAILABLE"
                                + (sessionOnline ? " with live session" : " (heartbeat persisted)")
                                + " after " + attempt + " poll(s)");
                return true;
            }
            if (attempt == 1 || attempt % 5 == 0) {
                var status = ssh.exec(client,
                        "sudo systemctl is-active lt-agent; sudo journalctl -u lt-agent -n 15 --no-pager || true",
                        20);
                genLogs.append(generatorId, "PROVISION", "INFO", "REGISTER_WAIT_POLL",
                        "Still waiting for gRPC register (poll #" + attempt + ")"
                                + " sessionOnline=" + sessionOnline
                                + " status=" + (refreshed != null ? refreshed.getStatus() : "missing"),
                        Map.of(
                                "systemd", truncate(status.stdout(), 1500),
                                "stderr", truncate(status.stderr(), 500)
                        ));
            }
            Thread.sleep(2000);
        }
        return false;
    }

    private Generator waitForGenerator(UUID generatorId, int attempts) throws InterruptedException {
        for (int i = 0; i < attempts; i++) {
            var opt = generators.findById(generatorId);
            if (opt.isPresent()) {
                return opt.get();
            }
            Thread.sleep(200L * (i + 1));
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "Generator not found");
    }

    private static String detectOutboundIpv4() {
        try {
            try (var socket = new java.net.DatagramSocket()) {
                socket.connect(InetAddress.getByName("8.8.8.8"), 53);
                InetAddress local = socket.getLocalAddress();
                if (local instanceof Inet4Address && !local.isLoopbackAddress()) {
                    return local.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // fall through to interface scan
        }
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private SshClientFactory.ExecResult execLogged(SSHClient client, UUID generatorId, String step,
                                                   String command, long timeout) throws Exception {
        genLogs.append(generatorId, "PROVISION", "INFO", step + "_CMD",
                "SSH exec: " + truncate(command.replace('\n', ' '), 500),
                Map.of("timeoutSec", timeout));
        var result = ssh.exec(client, command, timeout);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("exitCode", result.exitCode());
        detail.put("stdout", truncate(result.stdout(), 4000));
        detail.put("stderr", truncate(result.stderr(), 4000));
        genLogs.append(generatorId, "PROVISION", result.ok() ? "INFO" : "WARN", step + "_RESULT",
                "SSH result exit=" + result.exitCode()
                        + (result.stdout().isBlank() ? "" : " · " + truncate(result.stdout().trim(), 200)),
                detail);
        return result;
    }

    private void runStep(Generator g, String step, String message) {
        g.setProvisionStep(step);
        g.touch();
        generators.save(g);
        jdbc.update(
                "INSERT INTO provisioning_steps(generator_id, step_name, status, message, started_at, finished_at) VALUES (?,?,?,?,?,?)",
                g.getId(), step, "DONE", message, Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
        genLogs.info(g.getId(), "PROVISION", step, message);
        log.info("Generator {} step {}: {}", g.getId(), step, message);
    }

    private void updateStatus(Generator g, GeneratorStatus status, String error) {
        g.setStatus(status);
        g.setProvisionError(error);
        g.touch();
        generators.save(g);
    }

    private void markError(UUID id, String message) {
        generators.findById(id).ifPresent(g -> {
            g.setStatus(GeneratorStatus.ERROR);
            g.setProvisionError(message);
            g.touch();
            generators.save(g);
            jdbc.update(
                    "INSERT INTO provisioning_steps(generator_id, step_name, status, message, started_at, finished_at) VALUES (?,?,?,?,?,?)",
                    id, g.getProvisionStep() != null ? g.getProvisionStep() : "UNKNOWN", "FAILED", message,
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
            );
            genLogs.error(id, "PROVISION", "FAILED", message);
        });
    }

    private void installJmeterBundle(SSHClient client, UUID generatorId, Path bundle) throws Exception {
        if (Files.isRegularFile(bundle)) {
            genLogs.info(generatorId, "PROVISION", "INSTALL_JMETER",
                    "Uploading JMeter archive from " + bundle + " (" + Files.size(bundle) + " bytes)");
            ssh.upload(client, Files.readAllBytes(bundle), "/tmp/jmeter-bundle.tgz");
            execLogged(client, generatorId, "INSTALL_JMETER",
                    "rm -rf /opt/lt-jmeter/* /opt/lt-jmeter/.[!.]* 2>/dev/null; "
                            + "tar -xzf /tmp/jmeter-bundle.tgz -C /opt/lt-jmeter --strip-components=1 "
                            + "&& rm -f /tmp/jmeter-bundle.tgz "
                            + "&& test -x /opt/lt-jmeter/bin/jmeter "
                            + "&& /opt/lt-jmeter/bin/jmeter -v 2>&1 | head -2", 180);
            return;
        }
        if (Files.isDirectory(bundle)) {
            Path binDir = bundle.resolve("bin");
            boolean hasBin = Files.isDirectory(binDir);
            boolean hasJmeter = Files.isRegularFile(bundle.resolve("bin/jmeter"))
                    || Files.isRegularFile(bundle.resolve("bin/jmeter.bat"));
            long fileCount;
            try (Stream<Path> walk = Files.walk(bundle)) {
                fileCount = walk.filter(Files::isRegularFile).count();
            }
            if (!hasBin || fileCount == 0) {
                genLogs.warn(generatorId, "PROVISION", "INSTALL_JMETER",
                        "JMeter directory " + bundle + " is empty or missing bin/; writing pending marker");
                execLogged(client, generatorId, "INSTALL_JMETER",
                        "mkdir -p /opt/lt-jmeter && echo 'pending' > /opt/lt-jmeter/.installed", 30);
                return;
            }
            Path archive = Files.createTempFile("jmeter-bundle-", ".tgz");
            try {
                packDirectoryToTgz(bundle, archive);
                long size = Files.size(archive);
                genLogs.info(generatorId, "PROVISION", "INSTALL_JMETER",
                        "Packing directory " + bundle + " (" + fileCount + " files) → upload "
                                + size + " bytes"
                                + (hasJmeter ? "" : " (bin/jmeter not found; extracting anyway)"));
                ssh.upload(client, Files.readAllBytes(archive), "/tmp/jmeter-bundle.tgz");
                var extract = execLogged(client, generatorId, "INSTALL_JMETER",
                        "rm -rf /opt/lt-jmeter/* /opt/lt-jmeter/.[!.]* 2>/dev/null; "
                                + "tar -xzf /tmp/jmeter-bundle.tgz -C /opt/lt-jmeter "
                                + "&& rm -f /tmp/jmeter-bundle.tgz "
                                + "&& chmod +x /opt/lt-jmeter/bin/jmeter /opt/lt-jmeter/bin/jmeter-server 2>/dev/null || true "
                                + "&& ls /opt/lt-jmeter/bin | head -20 "
                                + "&& (test -x /opt/lt-jmeter/bin/jmeter && /opt/lt-jmeter/bin/jmeter -v 2>&1 | head -2 || echo 'jmeter binary missing after extract')",
                        300);
                if (!extract.ok()) {
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "Failed to extract JMeter on generator: " + truncate(extract.stderr(), 500));
                }
            } finally {
                Files.deleteIfExists(archive);
            }
            return;
        }
        genLogs.warn(generatorId, "PROVISION", "INSTALL_JMETER",
                "JMeter bundle not found at " + bundle + "; writing pending marker");
        execLogged(client, generatorId, "INSTALL_JMETER",
                "mkdir -p /opt/lt-jmeter && echo 'pending' > /opt/lt-jmeter/.installed", 30);
    }

    /** Pack directory contents (not the root folder name) into a gzipped tar via system tar. */
    public static void packDirectoryToTgz(Path directory, Path archive) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "tar", "-czf", archive.toAbsolutePath().toString(),
                "-C", directory.toAbsolutePath().toString(),
                "."
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
            p.destroyForcibly();
            throw new IOException("tar pack failed for " + directory + ": " + out);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
