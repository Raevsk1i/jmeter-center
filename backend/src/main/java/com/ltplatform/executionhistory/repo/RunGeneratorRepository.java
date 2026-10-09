package com.ltplatform.executionhistory.repo;

import com.ltplatform.executionhistory.domain.RunGenerator;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunGeneratorRepository extends JpaRepository<RunGenerator, UUID> {
    List<RunGenerator> findByRunId(UUID runId);
}
