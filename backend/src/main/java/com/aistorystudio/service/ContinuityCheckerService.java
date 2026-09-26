package com.aistorystudio.service;

import com.aistorystudio.domain.Scene;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight heuristic continuity checker (spec section 58). Flags scenes where a
 * previously mentioned prop/object for a character disappears from later scene text
 * without an explicit removal cue. Meant as an assistive warning surfaced in the
 * Scene Editor ([Accept] / [Fix Automatically]), not a hard gate.
 */
@Service
public class ContinuityCheckerService {

    private static final Pattern OBJECT_PATTERN = Pattern.compile(
            "\\b(backpack|hat|scarf|umbrella|crown|cape|glasses|bag|shoes|boots|jacket|coat)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REMOVAL_PATTERN = Pattern.compile(
            "\\b(removes?|takes? off|drops?|loses?|without (his|her|their))\\b", Pattern.CASE_INSENSITIVE);

    public record ContinuityWarning(int sceneNumber, String message) {}

    public List<ContinuityWarning> check(List<Scene> orderedScenes) {
        List<ContinuityWarning> warnings = new ArrayList<>();
        Map<String, Integer> lastSeenScene = new HashMap<>();

        for (Scene scene : orderedScenes) {
            String text = ((scene.getAction() == null ? "" : scene.getAction()) + " "
                    + (scene.getNarration() == null ? "" : scene.getNarration())).toLowerCase();

            Matcher objMatcher = OBJECT_PATTERN.matcher(text);
            List<String> mentioned = new ArrayList<>();
            while (objMatcher.find()) mentioned.add(objMatcher.group(1).toLowerCase());

            boolean explicitRemoval = REMOVAL_PATTERN.matcher(text).find();

            for (String obj : mentioned) {
                lastSeenScene.put(obj, scene.getSceneNumber());
            }

            // if an object was present two scenes ago and vanished without a removal cue, flag it
            for (Map.Entry<String, Integer> entry : lastSeenScene.entrySet()) {
                String obj = entry.getKey();
                int seenAt = entry.getValue();
                if (seenAt == scene.getSceneNumber() - 2 && !mentioned.contains(obj) && !explicitRemoval) {
                    warnings.add(new ContinuityWarning(scene.getSceneNumber(),
                            "Potential continuity issue: '" + obj + "' present in scene " + seenAt
                                    + " is not mentioned here, with no explicit removal cue."));
                }
            }
        }
        return warnings;
    }
}
