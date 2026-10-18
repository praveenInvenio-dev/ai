package com.aistorystudio.conceptexplainer.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RiveLibraryTest {
    private static Path library(String manifest, String... files) throws Exception {
        Path d = Files.createTempDirectory("rive-lib");
        for (String f : files) Files.write(d.resolve(f), new byte[] {1, 2, 3});
        Files.writeString(d.resolve("manifest.json"), manifest);
        return d;
    }

    @Test void aSceneIsMatchedByWholeWordKeywords() throws Exception {
        var lib = RiveLibrary.load(library("{\"assets\":[{\"file\":\"loading.riv\",\"keywords\":[\"waiting\",\"freeze\"]},{\"file\":\"mascot.riv\",\"keywords\":[\"mascot\",\"character\"]}]}", "loading.riv", "mascot.riv").toString());
        assertEquals("loading.riv", lib.match("The app seems to freeze while waiting for the download").orElseThrow());
        assertEquals("mascot.riv", lib.match("Meet our friendly mascot").orElseThrow());
        assertTrue(lib.match("Characters in a string").isEmpty());          // "characters" is not the keyword "character"
        assertTrue(lib.match("Nothing relevant here").isEmpty());
    }

    @Test void unsafeOrMissingFilesAreIgnored() throws Exception {
        var lib = RiveLibrary.load(library("{\"assets\":[{\"file\":\"../../etc/passwd\",\"keywords\":[\"secret\"]},{\"file\":\"missing.riv\",\"keywords\":[\"gone\"]},{\"file\":\"ok.riv\",\"keywords\":[\"fine\"]}]}", "ok.riv").toString());
        assertEquals(1, lib.size());
        assertTrue(lib.match("a secret that is gone").isEmpty());
        assertTrue(lib.resolve("../manifest.json").isEmpty());
        assertTrue(lib.resolve("ok.riv").isPresent());
    }

    @Test void noManifestOrABrokenOneMeansAnEmptyLibrary() throws Exception {
        assertTrue(RiveLibrary.load(Files.createTempDirectory("empty").toString()).isEmpty());
        assertTrue(RiveLibrary.load(library("{not json").toString()).isEmpty());
        assertTrue(RiveLibrary.load(null).isEmpty());
        assertFalse(RiveLibrary.empty().match("anything").isPresent());
    }
}
