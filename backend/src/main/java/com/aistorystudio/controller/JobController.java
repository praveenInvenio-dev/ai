package com.aistorystudio.controller;

import com.aistorystudio.dto.JobStatusResponse;
import com.aistorystudio.service.JobEventService;
import com.aistorystudio.service.JobService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;
    private final JobEventService jobEventService;

    public JobController(JobService jobService, JobEventService jobEventService) {
        this.jobService = jobService;
        this.jobEventService = jobEventService;
    }

    @GetMapping("/{jobId}")
    public JobStatusResponse status(@PathVariable UUID jobId) {
        return jobService.getStatus(jobId);
    }

    @GetMapping(value = "/{jobId}/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable UUID jobId) {
        return jobEventService.subscribe(jobId);
    }
}
