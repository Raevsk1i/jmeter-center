package com.ltplatform.scheduler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltplatform.common.ApiException;
import com.ltplatform.scheduler.domain.ScheduledExecution;
import com.ltplatform.scheduler.job.ScheduledTestJob;
import com.ltplatform.scheduler.repo.ScheduledExecutionRepository;
import com.ltplatform.testmanagement.service.TestManagementService;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchedulerService {
    private final ScheduledExecutionRepository schedules;
    private final Scheduler quartz;
    private final TestManagementService tests;
    private final ObjectMapper mapper;

    public SchedulerService(
            ScheduledExecutionRepository schedules,
            Scheduler quartz,
            TestManagementService tests,
            ObjectMapper mapper
    ) {
        this.schedules = schedules;
        this.quartz = quartz;
        this.tests = tests;
        this.mapper = mapper;
    }

    public record ScheduleRequest(
            UUID testDefinitionId,
            Instant fireAt,
            String timezone,
            UUID masterGeneratorId,
            List<UUID> slaveGeneratorIds,
            Map<String, String> properties
    ) {}

    public record ScheduleResponse(
            UUID id, UUID testDefinitionId, Instant fireAt, String timezone,
            String status, UUID runId, Map<String, Object> configuration
    ) {}

    @Transactional
    public ScheduleResponse create(ScheduleRequest req) {
        tests.requireTest(req.testDefinitionId());
        if (req.fireAt() == null || req.fireAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "fireAt must be in the future");
        }
        ScheduledExecution s = new ScheduledExecution();
        s.setId(UUID.randomUUID());
        s.setTestDefinitionId(req.testDefinitionId());
        s.setFireAt(req.fireAt());
        s.setTimezone(req.timezone() != null ? req.timezone() : "UTC");
        s.setStatus("PENDING");
        Map<String, Object> config = Map.of(
                "masterGeneratorId", req.masterGeneratorId().toString(),
                "slaveGeneratorIds", req.slaveGeneratorIds() == null ? List.of() :
                        req.slaveGeneratorIds().stream().map(UUID::toString).toList(),
                "properties", req.properties() != null ? req.properties() : Map.of()
        );
        try {
            s.setConfiguration(mapper.writeValueAsString(config));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid configuration");
        }
        String jobKey = "schedule-" + s.getId();
        s.setQuartzJobKey(jobKey);
        schedules.save(s);
        try {
            JobDetail job = JobBuilder.newJob(ScheduledTestJob.class)
                    .withIdentity(jobKey, "lt-schedules")
                    .usingJobData("scheduleId", s.getId().toString())
                    .build();
            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(jobKey + "-trigger", "lt-schedules")
                    .startAt(Date.from(req.fireAt()))
                    .build();
            quartz.scheduleJob(job, trigger);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to schedule: " + e.getMessage());
        }
        return toResponse(s);
    }

    @Transactional
    public ScheduleResponse cancel(UUID id) {
        ScheduledExecution s = schedules.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Schedule not found"));
        if (!"PENDING".equals(s.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot cancel schedule in status " + s.getStatus());
        }
        try {
            quartz.deleteJob(JobKey.jobKey(s.getQuartzJobKey(), "lt-schedules"));
        } catch (Exception ignored) {
        }
        s.setStatus("CANCELLED");
        s.setUpdatedAt(Instant.now());
        schedules.save(s);
        return toResponse(s);
    }

    @Transactional
    public ScheduleResponse reschedule(UUID id, Instant fireAt) {
        ScheduledExecution s = schedules.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Schedule not found"));
        if (!"PENDING".equals(s.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot reschedule in status " + s.getStatus());
        }
        try {
            quartz.deleteJob(JobKey.jobKey(s.getQuartzJobKey(), "lt-schedules"));
        } catch (Exception ignored) {
        }
        s.setFireAt(fireAt);
        s.setUpdatedAt(Instant.now());
        schedules.save(s);
        try {
            JobDetail job = JobBuilder.newJob(ScheduledTestJob.class)
                    .withIdentity(s.getQuartzJobKey(), "lt-schedules")
                    .usingJobData("scheduleId", s.getId().toString())
                    .build();
            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(s.getQuartzJobKey() + "-trigger", "lt-schedules")
                    .startAt(Date.from(fireAt))
                    .build();
            quartz.scheduleJob(job, trigger);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to reschedule: " + e.getMessage());
        }
        return toResponse(s);
    }

    @Transactional(readOnly = true)
    public List<ScheduleResponse> list() {
        return schedules.findAll().stream().map(this::toResponse).toList();
    }

    private ScheduleResponse toResponse(ScheduledExecution s) {
        Map<String, Object> config;
        try {
            config = mapper.readValue(s.getConfiguration(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            config = Map.of();
        }
        return new ScheduleResponse(s.getId(), s.getTestDefinitionId(), s.getFireAt(), s.getTimezone(),
                s.getStatus(), s.getRunId(), config);
    }
}
