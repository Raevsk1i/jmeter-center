package com.ltplatform.executionhistory.repo;

import com.ltplatform.executionhistory.domain.TestRun;
import com.ltplatform.executionhistory.domain.TestRunStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TestRunRepository extends JpaRepository<TestRun, UUID> {
    List<TestRun> findByStatusIn(List<TestRunStatus> statuses);

    @Query("select r from TestRun r order by r.createdAt desc")
    List<TestRun> findRecent();

    long countByStatus(TestRunStatus status);
}
