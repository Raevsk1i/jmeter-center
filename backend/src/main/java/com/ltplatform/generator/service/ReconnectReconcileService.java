package com.ltplatform.generator.service;

import com.ltplatform.executionhistory.domain.TestRun;
import com.ltplatform.executionhistory.domain.TestRunStatus;
import com.ltplatform.executionhistory.repo.TestRunRepository;
import com.ltplatform.generator.domain.Generator;
import com.ltplatform.generator.domain.GeneratorStatus;
import com.ltplatform.generator.repo.GeneratorRepository;
import com.ltplatform.reservation.domain.GeneratorReservation;
import com.ltplatform.reservation.domain.ReservationStatus;
import com.ltplatform.reservation.repo.GeneratorReservationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconnectReconcileService {
    private final GeneratorRepository generators;
    private final GeneratorReservationRepository reservations;
    private final TestRunRepository runs;

    public ReconnectReconcileService(
            GeneratorRepository generators,
            GeneratorReservationRepository reservations,
            TestRunRepository runs
    ) {
        this.generators = generators;
        this.reservations = reservations;
        this.runs = runs;
    }

    @Transactional
    public void reconcile(UUID generatorId) {
        Generator g = generators.findById(generatorId).orElse(null);
        if (g == null || g.getStatus() != GeneratorStatus.OFFLINE) {
            return;
        }
        List<GeneratorReservation> held = reservations.findByGeneratorIdAndStatus(generatorId, ReservationStatus.HELD);
        if (held.isEmpty()) {
            g.setStatus(GeneratorStatus.AVAILABLE);
            g.touch();
            generators.save(g);
            return;
        }
        GeneratorReservation r = held.getFirst();
        TestRun run = runs.findById(r.getRunId()).orElse(null);
        if (run != null && run.getStatus() == TestRunStatus.RUNNING) {
            g.setStatus(GeneratorStatus.RUNNING);
        } else if (run != null && (run.getStatus() == TestRunStatus.PREPARING || run.getStatus() == TestRunStatus.SCHEDULED)) {
            g.setStatus(GeneratorStatus.RESERVED);
        } else {
            g.setStatus(GeneratorStatus.AVAILABLE);
        }
        g.touch();
        generators.save(g);
    }
}
