package com.ltplatform.scheduler.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltplatform.orchestration.service.TestOrchestrator;
import com.ltplatform.orchestration.service.TestOrchestrator.CreateRunRequest;
import com.ltplatform.scheduler.domain.ScheduledExecution;
import com.ltplatform.scheduler.repo.ScheduledExecutionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ScheduledTestJob implements Job {
    private static final Logger log = LoggerFactory.getLogger(ScheduledTestJob.class);

    @Autowired
    private ScheduledExecutionRepository schedules;
    @Autowired
    private TestOrchestrator orchestrator;
    @Autowired
    private ObjectMapper mapper;

    @Override
    public void execute(JobExecutionContext context) {
        UUID scheduleId = UUID.fromString(context.getMergedJobDataMap().getString("scheduleId"));
        ScheduledExecution schedule = schedules.findById(scheduleId).orElse(null);
        if (schedule == null || !"PENDING".equals(schedule.getStatus())) {
            return;
        }
        try {
            Map<String, Object> config = mapper.readValue(schedule.getConfiguration(), new TypeReference<>() {});
            UUID master = UUID.fromString(String.valueOf(config.get("masterGeneratorId")));
            @SuppressWarnings("unchecked")
            List<String> slaves = (List<String>) config.getOrDefault("slaveGeneratorIds", List.of());
            @SuppressWarnings("unchecked")
            Map<String, String> props = (Map<String, String>) config.getOrDefault("properties", Map.of());
            var run = orchestrator.create(new CreateRunRequest(
                    schedule.getTestDefinitionId(),
                    master,
                    slaves.stream().map(UUID::fromString).toList(),
                    props,
                    true
            ), "scheduler");
            schedule.setRunId(run.id());
            schedule.setStatus("FIRED");
            schedule.setUpdatedAt(Instant.now());
            schedules.save(schedule);
            log.info("Scheduled execution {} fired run {}", scheduleId, run.id());
        } catch (Exception e) {
            schedule.setStatus("FAILED");
            schedule.setUpdatedAt(Instant.now());
            schedules.save(schedule);
            log.error("Scheduled execution {} failed: {}", scheduleId, e.getMessage());
        }
    }
}
