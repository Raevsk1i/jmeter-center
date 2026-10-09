package com.ltplatform.provisioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ltplatform.generator.domain.SshCredential;
import com.ltplatform.provisioning.ssh.SshClientFactory.TrustOnFirstUseVerifier;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SshHostKeyVerifierTest {

    @Test
    void trustsNewHostsIndependentlyForSameCredential() throws Exception {
        SshCredential cred = new SshCredential();
        cred.setId(UUID.randomUUID());
        TrustOnFirstUseVerifier verifier = new TrustOnFirstUseVerifier(cred);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair a = kpg.generateKeyPair();
        KeyPair b = kpg.generateKeyPair();

        assertTrue(verifier.verify("161.104.60.84", 22, a.getPublic()));
        assertTrue(verifier.verify("161.104.54.103", 22, b.getPublic()),
                "reusing credential on another host must TOFU, not fail");
        assertTrue(cred.getKnownHosts().contains("161.104.60.84|22"));
        assertTrue(cred.getKnownHosts().contains("161.104.54.103|22"));

        // same host + same key still ok
        assertTrue(verifier.verify("161.104.60.84", 22, a.getPublic()));
        // same host + different key rejected
        assertFalse(verifier.verify("161.104.60.84", 22, b.getPublic()));
    }

    @Test
    void normalizePemStripsLeadingSpaces() {
        String messy = """
                -----BEGIN OPENSSH PRIVATE KEY-----
                   abcdef
                   ghijkl
                -----END OPENSSH PRIVATE KEY-----
                """;
        String normalized = com.ltplatform.provisioning.ssh.SshClientFactory.normalizePem(messy);
        assertEquals("""
                -----BEGIN OPENSSH PRIVATE KEY-----
                abcdef
                ghijkl
                -----END OPENSSH PRIVATE KEY-----
                """, normalized);
    }
}
