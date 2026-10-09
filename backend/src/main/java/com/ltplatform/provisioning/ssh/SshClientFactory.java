package com.ltplatform.provisioning.ssh;

import com.ltplatform.generator.domain.SshCredential;
import com.ltplatform.security.crypto.SecretBox;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.password.PasswordFinder;
import net.schmizz.sshj.userauth.password.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SshClientFactory {
    private static final Logger log = LoggerFactory.getLogger(SshClientFactory.class);

    private final SecretBox secretBox;

    public SshClientFactory(SecretBox secretBox) {
        this.secretBox = secretBox;
    }

    public SSHClient connect(String host, int port, String user, SshCredential credential) throws Exception {
        SSHClient client = new SSHClient();
        client.addHostKeyVerifier(new TrustOnFirstUseVerifier(credential));
        client.setConnectTimeout(15000);
        client.connect(host, port);
        String privateKey = normalizePem(secretBox.decryptString(credential.getPrivateKeyEnc()));
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

    /** Strip accidental leading spaces from PEM lines (common when pasting into forms). */
    public static String normalizePem(String pem) {
        if (pem == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : pem.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line.stripLeading());
        }
        return sb.toString().strip() + "\n";
    }

    public record ExecResult(int exitCode, String stdout, String stderr) {
        public boolean ok() { return exitCode == 0; }
    }

    /**
     * Trust-on-first-use per host:port. Same SSH credential can be reused across many generators
     * without failing host-key checks for newly seen hosts.
     */
    public static class TrustOnFirstUseVerifier implements HostKeyVerifier {
        private final SshCredential credential;

        public TrustOnFirstUseVerifier(SshCredential credential) {
            this.credential = credential;
        }

        @Override
        public boolean verify(String hostname, int port, PublicKey key) {
            String type = KeyType.fromKey(key).toString();
            String fingerprint = SecurityUtils.getFingerprint(key);
            String entryKey = hostname.toLowerCase(Locale.ROOT) + "|" + port;
            Map<String, String> known = parseKnownHosts(credential.getKnownHosts());
            String existing = known.get(entryKey);
            if (existing == null) {
                known.put(entryKey, type + " " + fingerprint);
                credential.setKnownHosts(serializeKnownHosts(known));
                log.info("TOFU: recorded SSH host key for {} port {} ({})", hostname, port, fingerprint);
                return true;
            }
            String expected = type + " " + fingerprint;
            if (existing.equals(expected) || existing.endsWith(" " + fingerprint) || existing.equals(fingerprint)) {
                return true;
            }
            log.warn("SSH host key mismatch for {} port {}: expected [{}] got [{}]",
                    hostname, port, existing, expected);
            return false;
        }

        @Override
        public List<String> findExistingAlgorithms(String hostname, int port) {
            String entryKey = hostname.toLowerCase(Locale.ROOT) + "|" + port;
            Map<String, String> known = parseKnownHosts(credential.getKnownHosts());
            String existing = known.get(entryKey);
            if (existing == null || existing.isBlank()) {
                return List.of();
            }
            int sp = existing.indexOf(' ');
            if (sp > 0) {
                return List.of(existing.substring(0, sp));
            }
            return List.of();
        }

        static Map<String, String> parseKnownHosts(String raw) {
            Map<String, String> map = new LinkedHashMap<>();
            if (raw == null || raw.isBlank()) {
                return map;
            }
            // New format: host|port type fingerprint  (one per line)
            for (String line : raw.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                String[] parts = trimmed.split("\\s+", 3);
                if (parts.length >= 2 && parts[0].contains("|")) {
                    String value = parts.length == 2 ? parts[1] : parts[1] + " " + parts[2];
                    map.put(parts[0].toLowerCase(Locale.ROOT), value);
                    continue;
                }
                // Legacy format from earlier MVP: "hostname alg:format" (single host only)
                if (parts.length >= 2 && !parts[0].contains("|")) {
                    map.put(parts[0].toLowerCase(Locale.ROOT) + "|22", parts[1]);
                }
            }
            return map;
        }

        static String serializeKnownHosts(Map<String, String> known) {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, String> e : known.entrySet()) {
                lines.add(e.getKey() + " " + e.getValue());
            }
            return String.join("\n", lines);
        }
    }
}
