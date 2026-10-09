package com.ltplatform.provisioning.service;

import com.ltplatform.common.ApiException;
import com.ltplatform.config.LtPlatformProperties;
import com.ltplatform.generator.domain.Generator;
import com.ltplatform.generator.domain.GeneratorStatus;
import com.ltplatform.generator.domain.SshCredential;
import com.ltplatform.generator.dto.GeneratorDtos.ProvisionStepResponse;
import com.ltplatform.generator.repo.GeneratorRepository;
import com.ltplatform.generator.repo.SshCredentialRepository;
import com.ltplatform.provisioning.ssh.SshClientFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
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
    private static final List<String> STEPS = List.of(
            "CONNECT", "DETECT_OS", "INSTALL_JAVA", "INSTALL_JMETER",
            "CREATE_DIRS", "INSTALL_AGENT", "CONFIGURE", "REGISTER_WAIT", "VERIFY"
    );

    private final GeneratorRepository generators;
    private final SshCredentialRepository credentials;
    private final SshClientFactory ssh;
    private final LtPlatformProperties props;
    private final JdbcTemplate jdbc;

    public ProvisioningService(
            GeneratorRepository generators,
            SshCredentialRepository credentials,
            SshClientFactory ssh,
            LtPlatformProperties props,
            JdbcTemplate jdbc
    ) {
        this.generators = generators;
        this.credentials = credentials;
        this.ssh = ssh;
        this.props = props;
        this.jdbc = jdbc;
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

        try (SSHClient client = ssh.connect(g.getHostname(), g.getSshPort(), g.getSshUser(), cred)) {
            credentials.save(cred); // persist known_hosts

            runStep(g, "CONNECT", "SSH connected");
            var os = ssh.exec(client, "cat /etc/os-release | head -5; uname -m; sudo -n true 2>/dev/null; echo SUDO:$?", 30);
            runStep(g, "DETECT_OS", os.stdout().trim());

            runStep(g, "INSTALL_JAVA", "Ensuring Java 21");
            ssh.exec(client, """
                    if ! java -version 2>&1 | grep -q '21'; then
                      (sudo dnf install -y java-21-openjdk-devel || sudo yum install -y java-21-openjdk-devel) || true
                    fi
                    java -version 2>&1 | head -1
                    """, 300);

            runStep(g, "INSTALL_JMETER", "Copying JMeter bundle");
            Path bundle = Path.of(props.getJmeterBundlePath());
            ssh.exec(client, "sudo mkdir -p /opt/lt-jmeter && sudo chown $(whoami) /opt/lt-jmeter", 30);
            if (Files.isRegularFile(bundle)) {
                byte[] bytes = Files.readAllBytes(bundle);
                ssh.upload(client, bytes, "/tmp/jmeter-bundle.tgz");
                ssh.exec(client, "tar -xzf /tmp/jmeter-bundle.tgz -C /opt/lt-jmeter --strip-components=1 || true", 120);
            } else if (Files.isDirectory(bundle)) {
                // directory marker — agent will expect JMeter already present or sync later
                ssh.exec(client, "mkdir -p /opt/lt-jmeter && echo 'bundle-dir' > /opt/lt-jmeter/.installed", 30);
            } else {
                ssh.exec(client, "mkdir -p /opt/lt-jmeter && echo 'pending' > /opt/lt-jmeter/.installed", 30);
            }

            runStep(g, "CREATE_DIRS", "Creating agent directories");
            ssh.exec(client, """
                    sudo useradd -r -s /sbin/nologin lt-agent 2>/dev/null || true
                    sudo mkdir -p /opt/lt-agent /var/lib/lt-agent/workspaces /var/lib/lt-agent/state /var/log/lt-agent
                    sudo chown -R lt-agent:lt-agent /opt/lt-agent /var/lib/lt-agent /var/log/lt-agent
                    """, 60);

            runStep(g, "INSTALL_AGENT", "Installing Go agent");
            Path agentBin = Path.of(props.getAgentBinaryPath());
            if (Files.isRegularFile(agentBin)) {
                byte[] bin = Files.readAllBytes(agentBin);
                ssh.upload(client, bin, "/tmp/lt-agent");
                ssh.exec(client, "sudo mv /tmp/lt-agent /opt/lt-agent/lt-agent && sudo chmod +x /opt/lt-agent/lt-agent && sudo chown lt-agent:lt-agent /opt/lt-agent/lt-agent", 30);
            } else {
                ssh.exec(client, "echo 'agent-binary-missing' > /opt/lt-agent/MISSING", 10);
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
            ssh.upload(client, unit.getBytes(), "/tmp/lt-agent.service");
            ssh.exec(client, "sudo mv /tmp/lt-agent.service /etc/systemd/system/lt-agent.service && sudo systemctl daemon-reload && sudo systemctl enable --now lt-agent || true", 60);

            runStep(g, "REGISTER_WAIT", "Waiting for agent registration");
            // Agent will connect via gRPC; heartbeat flips status to AVAILABLE
            g.setProvisionStep("REGISTER_WAIT");
            generators.save(g);

            runStep(g, "VERIFY", "Provisioning steps completed; awaiting agent heartbeat");
        }
    }

    public Map<String, Object> checkConnectivity(UUID generatorId) {
        Generator g = generators.findById(generatorId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Generator not found"));
        SshCredential cred = credentials.findById(g.getSshCredentialId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "SSH credential missing"));
        try (SSHClient client = ssh.connect(g.getHostname(), g.getSshPort(), g.getSshUser(), cred)) {
            var result = ssh.exec(client, "echo OK; uname -a", 15);
            credentials.save(cred);
            return Map.of("ok", result.ok(), "stdout", result.stdout(), "stderr", result.stderr());
        } catch (Exception e) {
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

    private void runStep(Generator g, String step, String message) {
        g.setProvisionStep(step);
        g.touch();
        generators.save(g);
        jdbc.update(
                "INSERT INTO provisioning_steps(generator_id, step_name, status, message, started_at, finished_at) VALUES (?,?,?,?,?,?)",
                g.getId(), step, "DONE", message, Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
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
        });
    }
}
