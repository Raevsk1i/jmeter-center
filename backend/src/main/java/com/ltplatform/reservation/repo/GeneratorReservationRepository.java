package com.ltplatform.reservation.repo;

import com.ltplatform.reservation.domain.GeneratorReservation;
import com.ltplatform.reservation.domain.ReservationStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeneratorReservationRepository extends JpaRepository<GeneratorReservation, UUID> {
    List<GeneratorReservation> findByRunIdAndStatus(UUID runId, ReservationStatus status);
    List<GeneratorReservation> findByGeneratorIdAndStatus(UUID generatorId, ReservationStatus status);
}
