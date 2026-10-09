package com.ltplatform.generator.repo;

import com.ltplatform.generator.domain.SshCredential;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SshCredentialRepository extends JpaRepository<SshCredential, UUID> {
}
