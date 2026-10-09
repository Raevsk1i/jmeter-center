package com.ltplatform.provisioning.ssh;

import com.ltplatform.generator.domain.SshCredential;
import com.ltplatform.security.crypto.SecretBox;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.password.PasswordFinder;
import net.schmizz.sshj.userauth.password.Resource;
import org.springframework.stereotype.Component;

@Component
public class SshClientFactory {
    private final SecretBox secretBox;

    public SshClientFactory(SecretBox secretBox) {
        this.secretBox = secretBox;
    }

    public SSHClient connect(String host, int port, String user, SshCredential credential) throws Exception {
        SSHClient client = new SSHClient();
        client.addHostKeyVerifier(new RecordingHostKeyVerifier(credential));
        client.setConnectTimeout(15000);
        client.connect(host, port);
        String privateKey = secretBox.decryptString(credential.getPrivateKeyEnc());
        PasswordFinder finder = null;
        if (credential.getPassphraseEnc() != null) {
            char[] pass = secretBox.decryptString(credential.getPassphraseEnc()).toCharArray();
            finder = new PasswordFinder() {
                @Override
                public char[] reqPassword(Resource<?> resource) {
                    return pass;
                }

                @Override
                public boolean shouldRetry(Resource<?> resource) {
                    return false;
                }
            };
        }
        KeyProvider keys = client.loadKeys(privateKey, null, finder);
        client.authPublickey(user, keys);
        return client;
    }

    public ExecResult exec(SSHClient client, String command, long timeoutSeconds) throws Exception {
        try (var session = client.startSession()) {
            var cmd = session.exec(command);
            cmd.join(timeoutSeconds, TimeUnit.SECONDS);
            String out = new String(cmd.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(cmd.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new ExecResult(cmd.getExitStatus() == null ? -1 : cmd.getExitStatus(), out, err);
        }
    }

    public void upload(SSHClient client, byte[] content, String remotePath) throws Exception {
        try (var sftp = client.newSFTPClient()) {
            sftp.put(new net.schmizz.sshj.xfer.InMemorySourceFile() {
                @Override public String getName() { return remotePath; }
                @Override public long getLength() { return content.length; }
                @Override public java.io.InputStream getInputStream() {
                    return new ByteArrayInputStream(content);
                }
            }, remotePath);
        }
    }

    public record ExecResult(int exitCode, String stdout, String stderr) {
        public boolean ok() { return exitCode == 0; }
    }

    private static class RecordingHostKeyVerifier implements HostKeyVerifier {
        private final SshCredential credential;

        RecordingHostKeyVerifier(SshCredential credential) {
            this.credential = credential;
        }

        @Override
        public boolean verify(String hostname, int port, PublicKey key) {
            String fingerprint = key.getAlgorithm() + ":" + key.getFormat();
            if (credential.getKnownHosts() == null || credential.getKnownHosts().isBlank()) {
                credential.setKnownHosts(hostname + " " + fingerprint);
                return true;
            }
            return credential.getKnownHosts().contains(hostname);
        }

        @Override
        public List<String> findExistingAlgorithms(String hostname, int port) {
            return List.of();
        }
    }
}
