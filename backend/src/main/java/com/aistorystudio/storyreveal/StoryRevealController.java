package com.aistorystudio.storyreveal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Classic storyboard video (images + narration, no video model), animated layer by layer. */
@RestController
@RequestMapping("/api/storyboard")
public class StoryRevealController {

    private final StoryRevealService service;

    public StoryRevealController(StoryRevealService service) {
        this.service = service;
    }

    public record Request(String voice, Boolean captions) { }

    @PostMapping("/episodes/{episodeId}/classic-video")
    public ResponseEntity<Map<String, UUID>> start(@PathVariable UUID episodeId, @RequestBody(required = false) Request r) {
        StoryRevealService.Job job = service.start(episodeId);
        service.renderAsync(job.id, r == null ? null : r.voice(), r == null || r.captions() == null || r.captions());
        return ResponseEntity.accepted().body(Map.of("jobId", job.id));
    }

    @GetMapping("/classic-video-jobs/{jobId}")
    public StoryRevealService.JobView status(@PathVariable UUID jobId) {
        return service.view(jobId);
    }
}
