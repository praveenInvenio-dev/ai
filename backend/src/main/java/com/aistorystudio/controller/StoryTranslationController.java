package com.aistorystudio.controller;

import com.aistorystudio.domain.Episode;
import com.aistorystudio.service.StoryTranslationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** "Same story in another language": copy + translate, images reused. */
@RestController
@RequestMapping("/api/episodes")
public class StoryTranslationController {

    private final StoryTranslationService service;

    public StoryTranslationController(StoryTranslationService service) {
        this.service = service;
    }

    @PostMapping("/{id}/translate")
    public ResponseEntity<Map<String, Object>> translate(@PathVariable UUID id, @RequestParam("language") String language) {
        Episode copy = service.translate(id, language);
        return ResponseEntity.ok(Map.of("id", copy.getId(), "title", copy.getTitle(), "language", copy.getLanguage()));
    }
}
