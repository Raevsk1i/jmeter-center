package com.ltplatform.provisioning.service;

import com.ltplatform.common.ApiException;
import com.ltplatform.config.LtPlatformProperties;
import com.ltplatform.generator.domain.Generator;
import com.ltplatform.generator.domain.GeneratorStatus;
import com.ltplatform.generator.domain.SshCredential;
import com.ltplatform.generator.dto.GeneratorDtos.ProvisionStepResponse;
import com.ltplatform.generator.repo.GeneratorRepository;
import com.ltplatform.generator.repo.SshCredentialRepository;
import com.ltplatform.generator.service.GeneratorLogService;
import com.ltplatform.provisioning.ssh.SshClientFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    public ProvisioningService(
            GeneratorRepository generators,
            SshCredentialRepository credentials,
            SshClientFactory ssh,
            LtPlatformProperties props,
            JdbcTemplate jdbc,
            GeneratorLogService genLogs
    ) {
        this.generators = generators;
        this.credentials = credentials;
        this.ssh = ssh;
        this.props = props;
        this.jdbc = jdbc;
        this.genLogs = genLogs;
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
        Generator g = generators.findById(generatorId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Generator not found"));
        SshCredential cred = credentials.findById(g.getSshCredentialId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "SSH credential missing"));

        updateStatus(g, GeneratorStatus.PREPARING, null);
        genLogs.info(generatorId, "PROVISION", "START",
                "Provisioning started for " + g.getHostname() + ":" + g.getSshPort() + " as " + g.getSshUser());

        try (SSHClient client = ssh.connect(g.getHostname(), g.getSshPort(), g.getSshUser(), cred)) {
            credentials.save(cred);
            runStep(g, "CONNECT", "SSH connected");
            genLogs.info(generatorId, "PROVISION", "CONNECT", "SSH connection established");

            var os = execLogged(client, generatorId, "DETECT_OS",
                    "cat /etc/os-release | head -5; uname -m; sudo -n true 2>/dev/null; echo SUDO:$?", 30);
            runStep(g, "DETECT_OS", truncate(os.stdout().trim(), 500));

            runStep(g, "INSTALL_JAVA", "Ensuring Java 21");
            execLogged(client, generatorId, "INSTALL_JAVA", """
                    if ! java -version 2>&1 | grep -q '21'; then
                      (sudo dnf install -y java-21-openjdk-devel || sudo yum install -y java-21-openjdk-devel) || true
                    fi
                    java -version 2>&1 | head -1
                    """, 300);

            runStep(g, "INSTALL_JMETER", "Copying JMeter bundle");
            Path bundle = Path.of(props.getJmeterBundlePath());
            execLogged(client, generatorId, "INSTALL_JMETER",
                    "sudo mkdir -p /opt/lt-jmeter && sudo chown $(whoami) /opt/lt-jmeter", 30);
            if (Files.isRegularFile(bundle)) {
                genLogs.info(generatorId, "PROVISION", "INSTALL_JMETER",
                        "Uploading JMeter archive from " + bundle);
                byte[] bytes = Files.readAllBytes(bundle);
                ssh.upload(client, bytes, "/tmp/jmeter-bundle.tgz");
                execLogged(client, generatorId, "INSTALL_JMETER",
                        "tar -xzf /tmp/jmeter-bundle.tgz -C /opt/lt-jmeter --strip-components=1 || true", 120);
            } else if (Files.isDirectory(bundle)) {
                genLogs.info(generatorId, "PROVISION", "INSTALL_JMETER",
                        "JMeter bundle is a directory marker at " + bundle);
                execLogged(client, generatorId, "INSTALL_JMETER",
                        "mkdir -p /opt/lt-jmeter && echo 'bundle-dir' > /opt/lt-jmeter/.installed", 30);
            } else {
                genLogs.warn(generatorId, "PROVISION", "INSTALL_JMETER",
                        "JMeter bundle not found at " + bundle + "; writing pending marker");
                execLogged(client, generatorId, "INSTALL_JMETER",
                        "mkdir -p /opt/lt-jmeter && echo 'pending' > /opt/lt-jmeter/.installed", 30);
            }

            runStep(g, "CREATE_DIRS", "Creating agent directories");
            execLogged(client, generatorId, "CREATE_DIRS", """
                    sudo useradd -r -s /sbin/nologin lt-agent 2>/dev/null || true
                    sudo mkdir -p /opt/lt-agent /var/lib/lt-agent/workspaces /var/lib/lt-agent/state /var/log/lt-agent
                    sudo chown -R lt-agent:lt-agent /opt/lt-agent /var/lib/lt-agent /var/log/lt-agent
                    """, 60);

            runStep(g, "INSTALL_AGENT", "Installing Go agent");
            Path agentBin = Path.of(props.getAgentBinaryPath());
            if (Files.isRegularFile(agentBin)) {
                genLogs.info(generatorId, "PROVISION", "INSTALL_AGENT",
                        "Uploading agent binary from " + agentBin);
                byte[] bin = Files.readAllBytes(agentBin);
                ssh.upload(client, bin, "/tmp/lt-agent");
                execLogged(client, generatorId, "INSTALL_AGENT",
                        "sudo mv /tmp/lt-agent /opt/lt-agent/lt-agent && sudo chmod +x /opt/lt-agent/lt-agent && sudo chown lt-agent:lt-agent /opt/lt-agent/lt-agent", 30);
            } else {
                genLogs.error(generatorId, "PROVISION", "INSTALL_AGENT",
                        "Agent binary missing at " + agentBin);
                execLogged(client, generatorId, "INSTALL_AGENT",
                        "echo 'agent-binary-missing' > /opt/lt-agent/MISSING", 10);
            }

            String bootstrap = UUID.randomUUID().toString();
            String unit = """
                    [Unit]
                    Description=LT Platform Go Agent
                    After=network.target

                    [Service]
                    Type=simple
                    User=lt-agent
                    Environment=LT_CONTROLLER=%s:%d
                    Environment=LT_GENERATOR_ID=%s
                    Environment=LT_BOOTSTRAP_TOKEN=%s
                    Environment=LT_AGENT_ID=%s
                    ExecStart=/opt/lt-agent/lt-agent
                    Restart=always
                    RestartSec=5

                    [Install]
                    WantedBy=multi-user.target
                    """.formatted(
                    System.getenv().getOrDefault("LT_CONTROLLER_HOST", "controller"),
                    props.getGrpc().getPort(),
                    g.getId(),
                    bootstrap,
                    "agent-" + g.getId()
            );
            runStep(g, "CONFIGURE", "Writing systemd unit");
            genLogs.info(generatorId, "PROVISION", "CONFIGURE",
                    "systemd unit → LT_CONTROLLER="
                            + System.getenv().getOrDefault("LT_CONTROLLER_HOST", "controller")
                            + ":" + props.getGrpc().getPort());
            ssh.upload(client, unit.getBytes(), "/tmp/lt-agent.service");
            execLogged(client, generatorId, "CONFIGURE",
                    "sudo mv /tmp/lt-agent.service /etc/systemd/system/lt-agent.service && sudo systemctl daemon-reload && sudo systemctl enable --now lt-agent || true", 60);

            runStep(g, "REGISTER_WAIT", "Waiting for agent registration");
            g.setProvisionStep("REGISTER_WAIT");
            generators.save(g);
            genLogs.info(generatorId, "PROVISION", "REGISTER_WAIT",
                    "SSH bootstrap done; waiting for agent gRPC registration");

            runStep(g, "VERIFY", "Provisioning steps completed; awaiting agent heartbeat");
            genLogs.info(generatorId, "PROVISION", "VERIFY",
                    "Provisioning pipeline finished; status will become AVAILABLE after agent connects");
        } catch (Exception e) {
            genLogs.error(generatorId, "PROVISION", "FAILED", e.getMessage());
            throw e;
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
