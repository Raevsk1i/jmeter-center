package com.ltplatform.provisioning.ssh;

import com.ltplatform.config.LtPlatformProperties;
import com.ltplatform.generator.domain.SshCredential;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder;
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps an SSH session open with a remote port-forward so the Go agent on the
 * generator can dial {@code 127.0.0.1:REMOTE_PORT} and reach this controller's
 * gRPC port without requiring a publicly reachable controller address.
 */
@Service
public class SshAgentTunnelService {
    private static final Logger log = LoggerFactory.getLogger(SshAgentTunnelService.class);

    /** Port bound on the generator (localhost only) and written into LT_CONTROLLER. */
    public static final int REMOTE_FORWARD_PORT = 19090;

    private final SshClientFactory ssh;
    private final LtPlatformProperties props;
    private final Map<UUID, Tunnel> tunnels = new ConcurrentHashMap<>();

    public SshAgentTunnelService(SshClientFactory ssh, LtPlatformProperties props) {
        this.ssh = ssh;
        this.props = props;
    }

    public record OpenedTunnel(SSHClient client, String agentControllerAddr) {}

    public OpenedTunnel open(UUID generatorId, String host, int port, String user, SshCredential cred)
            throws Exception {
        close(generatorId);
        SSHClient client = ssh.connect(host, port, user, cred);
        try {
            client.getConnection().getKeepAlive().setKeepAliveInterval(30);
            int localGrpc = props.getGrpc().getPort();
            client.getRemotePortForwarder().bind(
                    new RemotePortForwarder.Forward("127.0.0.1", REMOTE_FORWARD_PORT),
                    new SocketForwardingConnectListener(new InetSocketAddress("127.0.0.1", localGrpc))
            );
            String agentAddr = "127.0.0.1:" + REMOTE_FORWARD_PORT;
            tunnels.put(generatorId, new Tunnel(client, host, port, user, cred.getId(), agentAddr));
            log.info("SSH reverse tunnel up for generator {} → {}:{} (agent dials {})",
                    generatorId, host, port, agentAddr);
            return new OpenedTunnel(client, agentAddr);
        } catch (Exception e) {
            try { client.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    public boolean isOpen(UUID generatorId) {
        Tunnel t = tunnels.get(generatorId);
        return t != null && t.client().isConnected();
    }

    public void close(UUID generatorId) {
        Tunnel t = tunnels.remove(generatorId);
        if (t != null) {
            try {
                t.client().close();
            } catch (Exception e) {
                log.debug("Error closing tunnel for {}: {}", generatorId, e.getMessage());
            }
        }
    }

    private record Tunnel(
            SSHClient client,
            String host,
            int port,
            String user,
            UUID credentialId,
            String agentControllerAddr
    ) {}
}
