package com.ltplatform.generator.repo;

import com.ltplatform.generator.domain.Generator;
import com.ltplatform.generator.domain.GeneratorStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GeneratorRepository extends JpaRepository<Generator, UUID> {

    List<Generator> findByStatus(GeneratorStatus status);

    long countByStatus(GeneratorStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Generator g where g.id in :ids order by g.id")
    List<Generator> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);

    @Query("select g from Generator g where g.lastHeartbeatAt < :threshold and g.status <> 'OFFLINE' and g.status <> 'ERROR' and g.status <> 'PREPARING'")
    List<Generator> findStaleHeartbeats(@Param("threshold") java.time.Instant threshold);
}
