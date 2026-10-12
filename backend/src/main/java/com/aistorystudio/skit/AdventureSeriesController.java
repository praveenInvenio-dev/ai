package com.aistorystudio.skit;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Adventure micro-series: a locked hero, a bible, and 15-second episodes that continue from each other. */
@RestController
@RequestMapping("/api/adventure-series")
public class AdventureSeriesController {

    private final FunnySkitService service;

    public AdventureSeriesController(FunnySkitService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> create(@RequestParam(value = "character", required = false) MultipartFile character,
                                                      @RequestParam("premise") String premise,
                                                      @RequestParam(value = "language", required = false, defaultValue = "English") String language,
                                                      @RequestParam(value = "style", required = false, defaultValue = "adventure") String style,
                                                      @RequestParam(value = "characterId", required = false) UUID characterId,
                                                      @RequestParam(value = "referenceId", required = false) UUID referenceId,
                                                      @RequestParam(value = "narration", required = false, defaultValue = "true") boolean narration) {
        var s = service.createSeries(character, premise, language, style, characterId, referenceId, narration);
        service.writeBibleAsync(s.id);
        return ResponseEntity.accepted().body(Map.of("seriesId", s.id));
    }

    @GetMapping
    public List<AdventureSeriesStore.Series> list() {
        return service.listSeries();
    }

    @GetMapping("/{id}")
    public AdventureSeriesStore.Series get(@PathVariable String id) {
        return service.series(id);
    }

    /** Write + storyboard the next episode (it continues from the last finished one). */
    @PostMapping("/{id}/episodes")
    public ResponseEntity<Map<String, Object>> nextEpisode(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        var j = service.startEpisode(id, body == null ? null : body.get("direction"));
        service.generateAsync(j.getId(), j.getIdea(), j.getLanguage(), j.getTone(), null);
        return ResponseEntity.accepted().body(Map.of("jobId", j.getId(), "episode", j.getEpisodeNumber()));
    }

    @GetMapping("/{id}/episodes/{n}/video")
    public ResponseEntity<FileSystemResource> video(@PathVariable String id, @PathVariable int n) {
        var s = service.series(id);
        var ep = s.episodes.stream().filter(e -> e.number == n && e.videoPath != null).findFirst()
                .orElseThrow(() -> new IllegalStateException("Episode " + n + " has no video yet."));
        Path p = Path.of(ep.videoPath);
        if (!Files.exists(p)) throw new IllegalStateException("Episode video file is missing.");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4")).body(new FileSystemResource(p));
    }
}
