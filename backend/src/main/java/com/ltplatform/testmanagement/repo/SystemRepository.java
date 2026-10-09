package com.ltplatform.testmanagement.repo;

import com.ltplatform.testmanagement.domain.SystemEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemRepository extends JpaRepository<SystemEntity, UUID> {
    Optional<SystemEntity> findByBitbucketBranch(String branch);
}
