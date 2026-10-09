package com.ltplatform.testmanagement.repo;

import com.ltplatform.testmanagement.domain.TestGroup;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestGroupRepository extends JpaRepository<TestGroup, UUID> {
}
