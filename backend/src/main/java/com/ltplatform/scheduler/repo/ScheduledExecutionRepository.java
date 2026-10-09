package com.ltplatform.scheduler.repo;

import com.ltplatform.scheduler.domain.ScheduledExecution;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledExecutionRepository extends JpaRepository<ScheduledExecution, UUID> {
    List<ScheduledExecution> findByStatusOrderByFireAtAsc(String status);
}
