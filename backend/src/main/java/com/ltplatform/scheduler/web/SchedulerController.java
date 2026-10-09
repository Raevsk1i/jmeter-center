package com.ltplatform.scheduler.web;

import com.ltplatform.scheduler.service.SchedulerService;
import com.ltplatform.scheduler.service.SchedulerService.ScheduleRequest;
import com.ltplatform.scheduler.service.SchedulerService.ScheduleResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/schedules")
public class SchedulerController {
    private final SchedulerService scheduler;

    public SchedulerController(SchedulerService scheduler) {
        this.scheduler = scheduler;
    }

    @GetMapping
    public List<ScheduleResponse> list() {
        return scheduler.list();
    }

    @PostMapping
    public ScheduleResponse create(@RequestBody ScheduleRequest req) {
        return scheduler.create(req);
    }

    @DeleteMapping("/{id}")
    public ScheduleResponse cancel(@PathVariable UUID id) {
        return scheduler.cancel(id);
    }

    @PutMapping("/{id}")
    public ScheduleResponse reschedule(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return scheduler.reschedule(id, Instant.parse(body.get("fireAt")));
    }
}
