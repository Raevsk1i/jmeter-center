package com.ltplatform.testmanagement.repo;

import com.ltplatform.testmanagement.domain.TestDefinition;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestDefinitionRepository extends JpaRepository<TestDefinition, UUID> {
    List<TestDefinition> findBySystemId(UUID systemId);
    List<TestDefinition> findByGroupId(UUID groupId);
}
