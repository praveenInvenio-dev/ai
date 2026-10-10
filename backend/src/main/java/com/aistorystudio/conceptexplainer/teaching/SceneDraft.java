package com.aistorystudio.conceptexplainer.teaching;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One scene as the planner wrote it, reduced to what validation needs (no JSON types, so the
 * validator can be tested on its own). The service converts JSON <-> SceneDraft.
 */
public final class SceneDraft {
    public int number;
    public String sectionType;
    public String objective;
    public String template;
    public String title;
    public List<String> narration = new ArrayList<>();
    public String visualDescription;
    public String imagePrompt;
    public String emotion, pace, delivery;
    /** Words shown on the slide (labels, titles, cells, items ...) - used to check the slide matches the narration. */
    public Set<String> slideTerms = new LinkedHashSet<>();
    /** Ordered node labels of a flow diagram. */
    public List<String> flowLabels = new ArrayList<>();
    /** How many of each slide element are present: nodes, items, rows, columns, callouts, parts, mappings, left, right, text, code, statement, box. */
    public Map<String, Integer> counts = new TreeMap<>();

    public int count(String k) { return counts.getOrDefault(k, 0); }

    public SceneDraft() { }

    public String narrationText() { return String.join(" ", narration); }
}
