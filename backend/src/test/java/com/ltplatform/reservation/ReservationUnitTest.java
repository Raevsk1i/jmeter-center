package com.ltplatform.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ltplatform.common.ApiException;
import com.ltplatform.generator.domain.Generator;
import com.ltplatform.generator.domain.GeneratorStatus;
import com.ltplatform.generator.repo.GeneratorRepository;
import com.ltplatform.reservation.domain.GeneratorReservation;
import com.ltplatform.reservation.domain.GeneratorRole;
import com.ltplatform.reservation.domain.ReservationStatus;
import com.ltplatform.reservation.repo.GeneratorReservationRepository;
import com.ltplatform.reservation.service.ReservationService;
import com.ltplatform.reservation.service.ReservationService.ReservationRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationUnitTest {

    @Mock GeneratorRepository generators;
    @Mock GeneratorReservationRepository reservations;
    ReservationService service;

    @BeforeEach
    void setUp() {
        service = new ReservationService(generators, reservations);
    }

    @Test
    void acquireBumpsFencingTokenAndReserves() {
        UUID masterId = UUID.randomUUID();
        UUID slaveId = UUID.randomUUID();
        Generator master = gen(masterId, 3);
        Generator slave = gen(slaveId, 1);
        when(generators.findAllByIdForUpdate(any())).thenReturn(List.of(master, slave));
        when(reservations.findByGeneratorIdAndStatus(any(), any())).thenReturn(List.of());
        when(generators.save(any())).thenAnswer(i -> i.getArgument(0));
        when(reservations.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.acquire(UUID.randomUUID(), List.of(
                new ReservationRequest(masterId, GeneratorRole.MASTER),
                new ReservationRequest(slaveId, GeneratorRole.SLAVE)
        ));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).fencingToken()).isEqualTo(4);
        assertThat(master.getStatus()).isEqualTo(GeneratorStatus.RESERVED);
        assertThat(slave.getStatus()).isEqualTo(GeneratorStatus.RESERVED);

        ArgumentCaptor<GeneratorReservation> cap = ArgumentCaptor.forClass(GeneratorReservation.class);
        org.mockito.Mockito.verify(reservations, org.mockito.Mockito.times(2)).save(cap.capture());
        assertThat(cap.getAllValues()).allMatch(r -> r.getStatus() == ReservationStatus.HELD);
    }

    @Test
    void acquireRequiresExactlyOneMaster() {
        UUID id = UUID.randomUUID();
        org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, () ->
                service.acquire(UUID.randomUUID(), List.of(
                        new ReservationRequest(id, GeneratorRole.SLAVE)
                )));
    }

    private static Generator gen(UUID id, long token) {
        Generator g = new Generator();
        g.setId(id);
        g.setName(id.toString());
        g.setHostname("h");
        g.setStatus(GeneratorStatus.AVAILABLE);
        g.setFencingToken(token);
        return g;
    }
}
