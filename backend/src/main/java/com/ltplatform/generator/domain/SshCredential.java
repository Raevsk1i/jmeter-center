package com.ltplatform.generator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ssh_credentials")
public class SshCredential {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "private_key_enc", nullable = false)
    private byte[] privateKeyEnc;

    @Column(name = "passphrase_enc")
    private byte[] passphraseEnc;

    @Column(name = "known_hosts")
    private String knownHosts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public byte[] getPrivateKeyEnc() { return privateKeyEnc; }
    public void setPrivateKeyEnc(byte[] privateKeyEnc) { this.privateKeyEnc = privateKeyEnc; }
    public byte[] getPassphraseEnc() { return passphraseEnc; }
    public void setPassphraseEnc(byte[] passphraseEnc) { this.passphraseEnc = passphraseEnc; }
    public String getKnownHosts() { return knownHosts; }
    public void setKnownHosts(String knownHosts) { this.knownHosts = knownHosts; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
