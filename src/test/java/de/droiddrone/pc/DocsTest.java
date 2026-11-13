package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * docs/HANDOFF.md is what every new work session reads first, so it stays short (2026-11-14: it had grown to 186 KB
 * with lines of 7000 characters - its release-by-release notes moved to docs/history/HANDOFF-history.md). What is
 * new goes into the short "Where things stand"; the long story goes to the CHANGELOG.
 */
class DocsTest {
    private static final long HANDOFF_MAX_BYTES = 60 * 1024;
    private static final int STATUS_MAX_LINE = 400;
    private static final String STATUS_HEADING = "## Where things stand";

    /** A file of the repository, from its root or from the tests' working folder (build/test-work). */
    private static Path repo(String file) {
        Path p = Path.of(file);
        return Files.exists(p) ? p : Path.of("../..").resolve(file);
    }

    @Test
    void theHandoffStaysShort() throws IOException {
        long size = Files.size(repo("docs/HANDOFF.md"));
        assertTrue(size < HANDOFF_MAX_BYTES, "docs/HANDOFF.md is " + size + " bytes (under " + HANDOFF_MAX_BYTES
                + " wanted): old notes belong in docs/history/HANDOFF-history.md and the CHANGELOG");
    }

    @Test
    void whereThingsStandHasNoEndlessLines() throws IOException {
        List<String> lines = Files.readAllLines(repo("docs/HANDOFF.md"), StandardCharsets.UTF_8);
        int start = -1;
        for (int i = 0; i < lines.size() && start < 0; i++) if (lines.get(i).startsWith(STATUS_HEADING)) start = i;
        assertTrue(start >= 0, "docs/HANDOFF.md has its \"" + STATUS_HEADING + "\" section");
        int checked = 0;
        for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("## "); i++) {
            String line = lines.get(i);
            assertTrue(line.length() <= STATUS_MAX_LINE, "docs/HANDOFF.md line " + (i + 1) + " in \"Where things stand\" is "
                    + line.length() + " characters (at most " + STATUS_MAX_LINE + "): " + line.substring(0, Math.min(80, line.length())) + "...");
            if (!line.isBlank()) checked++;
        }
        assertTrue(checked > 0, "the section says something");
    }

    @Test
    void theOldNotesAreKeptAsHistory() throws IOException {
        Path history = repo("docs/history/HANDOFF-history.md");
        assertTrue(Files.isRegularFile(history), "docs/history/HANDOFF-history.md is there");
        assertTrue(Files.size(history) > 0, "and not empty");
        assertTrue(Files.readString(repo("docs/HANDOFF.md"), StandardCharsets.UTF_8).contains("history/HANDOFF-history.md"),
                "docs/HANDOFF.md says where the history is");
    }

    @Test
    void theThirdPartyListNamesOpenH264() throws IOException {
        assertTrue(Files.readString(repo("THIRD-PARTY.md"), StandardCharsets.UTF_8).contains("OpenH264"),
                "THIRD-PARTY.md names OpenH264 (the H.264 encoder inside the bundled FFmpeg)");
    }
}
