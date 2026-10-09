package com.ltplatform.reservation.service;

import com.ltplatform.common.ApiException;
import com.ltplatform.generator.domain.Generator;
import com.ltplatform.generator.domain.GeneratorStatus;
import com.ltplatform.generator.repo.GeneratorRepository;
import com.ltplatform.reservation.domain.GeneratorReservation;
import com.ltplatform.reservation.domain.GeneratorRole;
import com.ltplatform.reservation.domain.ReservationStatus;
import com.ltplatform.reservation.repo.GeneratorReservationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {
    private final GeneratorRepository generators;
    private final GeneratorReservationRepository reservations;

    public ReservationService(GeneratorRepository generators, GeneratorReservationRepository reservations) {
        this.generators = generators;
        this.reservations = reservations;
    }

    public record ReservationRequest(UUID generatorId, GeneratorRole role) {}

    public record ReservationResult(UUID generatorId, GeneratorRole role, long fencingToken) {}

    @Transactional
    public List<ReservationResult> acquire(UUID runId, List<ReservationRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "At least one generator required");
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (ReservationRequest r : requests) {
            if (!ids.add(r.generatorId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Duplicate generator in reservation: " + r.generatorId());
            }
        }
        long masters = requests.stream().filter(r -> r.role() == GeneratorRole.MASTER).count();
        if (masters != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Exactly one MASTER required");
        }

        List<Generator> locked = generators.findAllByIdForUpdate(ids);
        if (locked.size() != ids.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "One or more generators not found");
        }

        for (Generator g : locked) {
            if (g.getStatus() != GeneratorStatus.AVAILABLE) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Generator " + g.getId() + " not AVAILABLE (status=" + g.getStatus() + ")");
            }
            if (!reservations.findByGeneratorIdAndStatus(g.getId(), ReservationStatus.HELD).isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "Generator already reserved: " + g.getId());
            }
        }

        List<ReservationResult> results = new ArrayList<>();
        Instant now = Instant.now();
        for (ReservationRequest req : requests) {
            Generator g = locked.stream().filter(x -> x.getId().equals(req.generatorId())).findFirst().orElseThrow();
            long token = g.getFencingToken() + 1;
            g.setFencingToken(token);
            g.setStatus(GeneratorStatus.RESERVED);
            g.touch();
            generators.save(g);

            GeneratorReservation reservation = new GeneratorReservation();
            reservation.setId(UUID.randomUUID());
            reservation.setGeneratorId(g.getId());
            reservation.setRunId(runId);
            reservation.setRole(req.role());
            reservation.setFencingToken(token);
            reservation.setWindowStart(now);
            reservation.setStatus(ReservationStatus.HELD);
            reservations.save(reservation);
            results.add(new ReservationResult(g.getId(), req.role(), token));
        }
        return results;
    }

    @Transactional
    public void release(UUID runId) {
        List<GeneratorReservation> held = reservations.findByRunIdAndStatus(runId, ReservationStatus.HELD);
        Set<UUID> genIds = new HashSet<>();
        for (GeneratorReservation r : held) {
            r.setStatus(ReservationStatus.RELEASED);
            reservations.save(r);
            genIds.add(r.getGeneratorId());
        }
        if (!genIds.isEmpty()) {
            List<Generator> locked = generators.findAllByIdForUpdate(genIds);
            for (Generator g : locked) {
                if (g.getStatus() == GeneratorStatus.RESERVED || g.getStatus() == GeneratorStatus.RUNNING) {
                    g.setStatus(GeneratorStatus.AVAILABLE);
                    g.touch();
                    generators.save(g);
                }
            }
        }
    }

    @Transactional
    public void markRunning(UUID runId) {
        List<GeneratorReservation> held = reservations.findByRunIdAndStatus(runId, ReservationStatus.HELD);
        Set<UUID> genIds = new HashSet<>();
        held.forEach(r -> genIds.add(r.getGeneratorId()));
        if (genIds.isEmpty()) return;
        List<Generator> locked = generators.findAllByIdForUpdate(genIds);
        for (Generator g : locked) {
            g.setStatus(GeneratorStatus.RUNNING);
            g.touch();
            generators.save(g);
        }
    }

    @Transactional(readOnly = true)
    public List<GeneratorReservation> heldForRun(UUID runId) {
        return reservations.findByRunIdAndStatus(runId, ReservationStatus.HELD);
    }
}
