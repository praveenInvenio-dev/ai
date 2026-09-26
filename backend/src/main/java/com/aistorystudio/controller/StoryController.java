package com.aistorystudio.controller;

import com.aistorystudio.dto.CreateStoryRequest;
import com.aistorystudio.dto.StoryDraftResponse;
import com.aistorystudio.service.ContinuityCheckerService;
import com.aistorystudio.service.StoryEngineService;
import com.aistorystudio.repository.SceneRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Stage A: idea -> draft. Never triggers expensive image/audio generation. */
@RestController
@RequestMapping("/api/stories")
public class StoryController {

    private final StoryEngineService storyEngineService;
    private final ContinuityCheckerService continuityCheckerService;
    private final SceneRepository sceneRepository;

    public StoryController(StoryEngineService storyEngineService, ContinuityCheckerService continuityCheckerService,
                            SceneRepository sceneRepository) {
        this.storyEngineService = storyEngineService;
        this.continuityCheckerService = continuityCheckerService;
        this.sceneRepository = sceneRepository;
    }

    @PostMapping("/draft")
    public StoryDraftResponse createDraft(@Valid @RequestBody CreateStoryRequest req) {
        return storyEngineService.createDraft(req);
    }

    @PostMapping("/{episodeId}/regenerate")
    public StoryDraftResponse regenerate(@PathVariable UUID episodeId, @RequestBody(required = false) List<UUID> characterIds) {
        return storyEngineService.regenerateDraft(episodeId, characterIds);
    }

    @GetMapping("/{episodeId}/continuity-warnings")
    public List<ContinuityCheckerService.ContinuityWarning> continuityWarnings(@PathVariable UUID episodeId) {
        var scenes = sceneRepository.findByEpisodeIdOrderByOrderIndexAsc(episodeId);
        return continuityCheckerService.check(scenes);
    }
}
