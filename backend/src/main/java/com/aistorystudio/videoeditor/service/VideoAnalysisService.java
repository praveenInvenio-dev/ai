package com.aistorystudio.videoeditor.service;

import com.aistorystudio.videoeditor.config.VideoEditorProperties;
import com.aistorystudio.videoeditor.domain.*;
import com.aistorystudio.videoeditor.domain.enums.VideoEditorState;
import com.aistorystudio.videoeditor.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Phase 4: clip analysis.
 *
 * Delegates the actual work to the video-worker container (ffprobe,
 * PySceneDetect, OpenCV) and persists the results. Only file PATHS cross the
 * network - both containers mount the same volume, so no video is ever copied
 * between them.
 *
 * Per clip: probe metadata, build a 480p proxy, detect shots, score each shot.
 * Every later stage reads the proxy rather than the original, which is what
 * keeps analysis and preview in the seconds range on 4K source material.
 */
@Service
public class VideoAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisService.class);

    /** Generous: a long 4K clip legitimately takes minutes to proxy on CPU. */
    private static final Duration PROXY_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration ANALYZE_TIMEOUT = Duration.ofMinutes(20);
    private static final Duration PROBE_TIMEOUT = Duration.ofMinutes(3);

    private final WebClient worker;
    private final VideoClipRepository clips;
    private final VideoSceneRepository scenes;
    private final VideoAnalysisRepository analyses;
    private final VideoEditorStorageService storage;
    private final VideoEditorProjectService projectService;
    private final ProgressReporter progress;

    public VideoAnalysisService(WebClient.Builder builder,
                                VideoEditorProperties properties,
                                VideoClipRepository clips,
                                VideoSceneRepository scenes,
                                VideoAnalysisRepository analyses,
                                VideoEditorStorageService storage,
                                VideoEditorProjectService projectService,
                                ProgressReporter progress) {
        this.worker = builder.baseUrl(properties.getWorkerBaseUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.clips = clips;
        this.scenes = scenes;
        this.analyses = analyses;
        this.storage = storage;
        this.projectService = projectService;
        this.progress = progress;
    }

    /**
     * Analyses every clip in a project.
     *
     * NOT @Transactional: analysis is minutes of proxy encoding and shot
     * detection, and holding a pooled connection across it would exhaust the
     * pool. Each clip's writes are their own short transaction, which also
     * means an interrupted run keeps the clips it already finished instead of
     * rolling all of them back.
     *
     * Clips already marked analysed are skipped, so adding one clip to a
     * ten-clip project re-analyses one clip, not eleven.
     */
    public void analyzeProject(UUID projectId, UUID jobId) {
        List<VideoClip> all = clips.findByProjectIdOrderBySortOrderAsc(projectId);
        List<VideoClip> pending = all.stream().filter(c -> !c.isAnalyzed()).toList();

        if (pending.isEmpty()) {
            progress.report(jobId, "Analysis", 100, "All clips already analysed");
            projectService.transition(projectId, VideoEditorState.ANALYZED);
            return;
        }

        int done = 0;
        for (VideoClip clip : pending) {
            progress.report(jobId, "Analysing " + clip.getDisplayName(),
                    (int) (done * 100.0 / pending.size()), null);
            analyzeClip(projectId, clip);
            done++;
        }

        progress.report(jobId, "Analysis", 100, pending.size() + " clip(s) analysed");
        projectService.transition(projectId, VideoEditorState.ANALYZED);
    }

    @Transactional
    public void analyzeClip(UUID projectId, VideoClip clip) {
        Path source = storage.resolveWithin(storage.uploadsDir(projectId), clip.getStoredFilename());

        // 1) Metadata. Also the decodability check - a file ffprobe cannot read
        //    fails here with a clear message rather than at render time, when
        //    the user has already waited through everything else.
        JsonNode meta = call("/api/probe", Map.of("path", source.toString()), PROBE_TIMEOUT);
        applyMetadata(clip, meta);

        // 2) Proxy. Every later stage reads this instead of the original.
        String proxyName = clip.getId() + ".mp4";
        Path proxy = storage.resolveWithin(storage.proxiesDir(projectId), proxyName);
        call("/api/proxy", Map.of("source", source.toString(), "target", proxy.toString()),
                PROXY_TIMEOUT);
        clip.setProxyFilename(proxyName);

        // 3) Shots and per-shot measurements, from the proxy.
        JsonNode analysis = call("/api/analyze", Map.of("path", proxy.toString()), ANALYZE_TIMEOUT);
        persistScenes(clip, analysis);

        clip.setAnalyzed(true);
        clips.save(clip);
        log.info("Analysed clip {} ({}): {} shot(s)", clip.getId(), clip.getDisplayName(),
                analysis.path("scenes").size());
    }

    private void applyMetadata(VideoClip clip, JsonNode meta) {
        clip.setDurationSec(meta.path("durationSec").asDouble());
        int width = meta.path("width").asInt();
        int height = meta.path("height").asInt();

        // Phone footage is often stored landscape with a rotation tag. Swapping
        // here means the rest of the app sees the orientation a viewer would,
        // instead of cropping the wrong axis during reframing.
        int rotation = Math.abs(meta.path("rotation").asInt(0)) % 180;
        if (rotation == 90) {
            int swap = width; width = height; height = swap;
        }
        clip.setWidth(width);
        clip.setHeight(height);
        clip.setFps(meta.path("fps").asDouble());
        clip.setVideoCodec(text(meta, "videoCodec"));
        clip.setAudioCodec(text(meta, "audioCodec"));
        clip.setHasAudio(meta.path("hasAudio").asBoolean(false));
        if (clip.getSizeBytes() == null && meta.path("sizeBytes").asLong(0) > 0) {
            clip.setSizeBytes(meta.path("sizeBytes").asLong());
        }
    }

    private void persistScenes(VideoClip clip, JsonNode analysis) {
        // Replace rather than append: re-analysing a clip must not leave the
        // previous run's shots behind alongside the new ones.
        scenes.deleteByClipId(clip.getId());

        List<VideoScene> rows = new ArrayList<>();
        for (JsonNode s : analysis.path("scenes")) {
            VideoScene scene = new VideoScene();
            scene.setClipId(clip.getId());
            scene.setSceneIndex(s.path("index").asInt());
            scene.setStartSec(s.path("startSec").asDouble());
            scene.setEndSec(s.path("endSec").asDouble());
            scene.setMotionScore(s.path("motionScore").asDouble());
            scene.setBlurScore(s.path("blurScore").asDouble());
            scene.setBrightness(s.path("brightness").asDouble());
            scene.setAudioRms(s.path("audioRms").asDouble());
            scene.setQualityScore(s.path("qualityScore").asDouble());
            scene.setShotType(text(s, "shotType"));
            scene.setHasSpeech(s.path("hasAudioSignal").asBoolean(false));
            rows.add(scene);
        }
        scenes.saveAll(rows);

        analyses.deleteByClipId(clip.getId());
        VideoAnalysis record = new VideoAnalysis();
        record.setClipId(clip.getId());
        record.setPayloadJson(analysis.toString());
        record.setAnalyzer(text(analysis, "analyzer"));
        analyses.save(record);
    }

    private JsonNode call(String uri, Map<String, Object> body, Duration timeout) {
        try {
            JsonNode response = worker.post().uri(uri).bodyValue(body)
                    .retrieve().bodyToMono(JsonNode.class).block(timeout);
            if (response != null && response.hasNonNull("error")) {
                throw new IllegalStateException(response.path("error").asText());
            }
            return response == null ? mapperMissing() : response;
        } catch (org.springframework.web.reactive.function.client.WebClientRequestException e) {
            throw new IllegalStateException(
                    "The video-worker service is not reachable. Start it with "
                            + "'docker compose up -d video-worker'.", e);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            throw new IllegalStateException(
                    "Video analysis failed: " + e.getResponseBodyAsString(), e);
        }
    }

    private JsonNode mapperMissing() {
        throw new IllegalStateException("The video-worker returned an empty response.");
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }
}
