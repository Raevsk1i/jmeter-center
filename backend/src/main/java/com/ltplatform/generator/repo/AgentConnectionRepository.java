package com.ltplatform.generator.repo;

import com.ltplatform.generator.domain.AgentConnection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentConnectionRepository extends JpaRepository<AgentConnection, UUID> {
    Optional<AgentConnection> findByGeneratorId(UUID generatorId);
    Optional<AgentConnection> findByAgentId(String agentId);
}
