package com.ltplatform.security.crypto;

import com.ltplatform.config.LtPlatformProperties;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Internal CA material for agent mTLS bootstrap.
 * MVP stores PEM-like client credentials under certs-dir/agents/{agentId}.
 */
@Component
public class CertificateAuthority {
    private static final Logger log = LoggerFactory.getLogger(CertificateAuthority.class);

    private final Path certsDir;
    private byte[] caCertPem;
    private boolean ready;

    public CertificateAuthority(LtPlatformProperties props) {
        this.certsDir = Path.of(props.getGrpc().getCertsDir());
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(certsDir);
            Path caKey = certsDir.resolve("ca.key");
            Path caCert = certsDir.resolve("ca.crt");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair ca = gen.generateKeyPair();
            if (!Files.exists(caKey)) {
                Files.write(caKey, ca.getPrivate().getEncoded());
                Files.write(caCert, ca.getPublic().getEncoded());
            }
            this.caCertPem = Files.readAllBytes(caCert);
            this.ready = true;
            log.info("Certificate authority initialized at {}", certsDir);
        } catch (Exception e) {
            log.warn("CA init degraded mode: {}", e.getMessage());
            this.ready = false;
        }
    }

    public IssuedCert issueClientCert(String agentId) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair kp = gen.generateKeyPair();
            String fingerprint = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(kp.getPublic().getEncoded())
            ).substring(0, 32);
            byte[] certPem = ("-----BEGIN CERTIFICATE-----\nAGENT:" + agentId + "\n-----END CERTIFICATE-----\n")
                    .getBytes();
            byte[] keyPem = ("-----BEGIN PRIVATE KEY-----\n" + fingerprint + "\n-----END PRIVATE KEY-----\n")
                    .getBytes();
            Path agentDir = certsDir.resolve("agents").resolve(agentId);
            Files.createDirectories(agentDir);
            Files.write(agentDir.resolve("client.crt"), certPem);
            Files.write(agentDir.resolve("client.key"), keyPem);
            return new IssuedCert(certPem, keyPem, fingerprint);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue client cert for " + agentId, e);
        }
    }

    public byte[] getCaCertPem() {
        return caCertPem != null ? caCertPem : new byte[0];
    }

    public boolean isReady() {
        return ready;
    }

    public record IssuedCert(byte[] certPem, byte[] keyPem, String fingerprint) {}
}
