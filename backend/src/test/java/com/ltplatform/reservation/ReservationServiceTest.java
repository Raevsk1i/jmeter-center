package com.ltplatform.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ltplatform.common.ApiException;
import com.ltplatform.generator.domain.Generator;
import com.ltplatform.generator.domain.GeneratorStatus;
import com.ltplatform.generator.repo.GeneratorRepository;
import com.ltplatform.reservation.domain.GeneratorRole;
import com.ltplatform.reservation.service.ReservationService;
import com.ltplatform.reservation.service.ReservationService.ReservationRequest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
class ReservationServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ltplatform")
            .withUsername("ltplatform")
            .withPassword("ltplatform");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("ltplatform.grpc.port", () -> "19090");
    }

    @Autowired
    ReservationService reservations;
    @Autowired
    GeneratorRepository generators;

    @Test
    void acquireIsExclusiveUnderConcurrency() throws Exception {
        Generator g1 = saveAvailable("g1");
        Generator g2 = saveAvailable("g2");

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID runId = UUID.randomUUID();
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    reservations.acquire(runId, List.of(
                            new ReservationRequest(g1.getId(), GeneratorRole.MASTER),
                            new ReservationRequest(g2.getId(), GeneratorRole.SLAVE)
                    ));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    conflict.incrementAndGet();
                } catch (Exception e) {
                    conflict.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(7);
        assertThat(generators.findById(g1.getId()).orElseThrow().getStatus()).isEqualTo(GeneratorStatus.RESERVED);
    }

    @Test
    void rejectsNonAvailable() {
        Generator g = saveAvailable("busy");
        g.setStatus(GeneratorStatus.RUNNING);
        generators.save(g);
        assertThatThrownBy(() -> reservations.acquire(UUID.randomUUID(),
                List.of(new ReservationRequest(g.getId(), GeneratorRole.MASTER))))
                .isInstanceOf(ApiException.class);
    }

    private Generator saveAvailable(String name) {
        Generator g = new Generator();
        g.setId(UUID.randomUUID());
        g.setName(name);
        g.setHostname(name + ".local");
        g.setStatus(GeneratorStatus.AVAILABLE);
        return generators.save(g);
    }
}
