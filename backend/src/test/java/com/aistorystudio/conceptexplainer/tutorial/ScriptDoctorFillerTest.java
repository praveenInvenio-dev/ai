package com.aistorystudio.conceptexplainer.tutorial;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScriptDoctorFillerTest {
    private static ScriptDoctor.Report review(String... lines) {
        return ScriptDoctor.review(List.of(new ScriptDoctor.SceneInput(1, "CONCEPT", List.of(lines), null)), 0, 0, 155, 0, true);
    }

    @Test void emptyPhrasesAreFlaggedAndFixable() {
        var r = review("Save threads for heavy lifting.", "That's where the real magic happens.");
        assertTrue(r.has("filler-phrase"));
        assertTrue(r.issues().stream().filter(i -> i.code().equals("filler-phrase")).allMatch(ScriptDoctor.Issue::fixable));
    }

    @Test void plainExplanationsAreNotFlagged() {
        assertFalse(review("The operating system runs each thread on a CPU core.", "Two threads can run at the same time.").has("filler-phrase"));
    }
}
