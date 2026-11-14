package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * docs/HANDOFF.md is what every new work session reads first, so it stays short (2026-11-14: it had grown to 186 KB
 * with lines of 7000 characters - its release-by-release notes moved to docs/history/HANDOFF-history.md). What is
 * new goes into the short "Where things stand"; the long story goes to the CHANGELOG.
 * <p>
 * And the wording of what the user reads: the README and the texts in the main sources say "mobile network".
 */
class DocsTest {
    private static final long HANDOFF_MAX_BYTES = 60 * 1024;
    private static final int STATUS_MAX_LINE = 400;
    private static final String STATUS_HEADING = "## Where things stand";
    /** The mobile network named by its generation, as a word of its own (spelled apart: this file doesn't say it either). */
    private static final String GENERATION = "4" + "G";
    private static final Pattern NAMES_THE_GENERATION = Pattern.compile("\\b" + GENERATION + "\\b");

    /** A text between quotes in a Java source, quotes included, and the line it starts on. */
    record Quoted(int line, String text) {
    }

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

    /**
     * The pilot's wording: what the user reads says "mobile network", not the network's generation (the command line's
     * help still said it in 1.0.66). Read here: the README and the example settings file (init-config copies it for
     * the user, comments and all), and in the main sources every text between quotes - help, labels, tool tips, log
     * lines. Comments in the sources may say it (they are the work's notes, as docs/ is); so may a feature the pilot
     * names so, which then gets its exception here.
     */
    @Test
    void whatTheUserReadsSaysMobileNetwork() throws IOException {
        List<String> found = new ArrayList<>();
        for (String doc : List.of("README.md", "src/main/resources/draupne-link.example.properties")) {
            List<String> lines = Files.readAllLines(repo(doc), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (NAMES_THE_GENERATION.matcher(lines.get(i)).find()) found.add(doc + ":" + (i + 1) + ": " + lines.get(i).strip());
            }
        }
        Path main = repo("src/main/java");
        int files = 0, texts = 0;
        try (Stream<Path> sources = Files.walk(main)) {
            for (Path f : sources.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                files++;
                for (Quoted q : quoted(Files.readString(f, StandardCharsets.UTF_8))) {
                    texts++;
                    if (!NAMES_THE_GENERATION.matcher(q.text()).find()) continue;
                    String start = q.text().strip().replaceAll("\\s+", " ");
                    found.add(main.relativize(f).toString().replace('\\', '/') + ":" + q.line() + ": "
                            + start.substring(0, Math.min(100, start.length())));
                }
            }
        }
        assertTrue(files > 100 && texts > 1000, "the main sources were read: " + files + " files, " + texts + " texts under " + main);
        assertEquals(List.of(), found, "texts the user reads that name the network's generation (\"mobile network\" is the word)");
    }

    /** The reading above takes a source's texts, text blocks too, and none of its comments or character literals. */
    @Test
    void theTextsOfASourceAreFoundAndItsCommentsAreNot() {
        String java = String.join("\n",
                "// a comment: \"over " + GENERATION + "\"",
                "/* another, over",
                "   two lines: " + GENERATION + " */",
                "class A {",
                "    char quote = '\"', tick = '\\'';",
                "    String help = \"fly \\\"over\\\" " + GENERATION + "\"; // " + GENERATION,
                "    String block = \"\"\"",
                "            fly over " + GENERATION,
                "            \"\"\";",
                "    String url = \"http://example\"; // the slashes in a text start no comment",
                "}");
        List<Quoted> texts = quoted(java);
        assertEquals(List.of(6, 7, 10), texts.stream().map(Quoted::line).toList(), "the lines the three texts start on");
        assertEquals("\"fly \\\"over\\\" " + GENERATION + "\"", texts.get(0).text());
        assertTrue(NAMES_THE_GENERATION.matcher(texts.get(1).text()).find(), "the text block's lines are its text");
        assertEquals("\"http://example\"", texts.get(2).text());
        assertFalse(NAMES_THE_GENERATION.matcher("a 14" + GENERATION + "B card, " + GENERATION + "s").find(), "only as a word of its own");
    }

    /**
     * The string literals and text blocks of a Java source in the order they stand, comments and character literals
     * passed over: what the program can show or write, as far as reading its source tells.
     */
    static List<Quoted> quoted(String java) {
        List<Quoted> out = new ArrayList<>();
        int line = 1;
        for (int i = 0; i < java.length(); ) {
            int end;
            if (java.startsWith("//", i)) {
                end = java.indexOf('\n', i);
                if (end < 0) end = java.length();
            } else if (java.startsWith("/*", i)) {
                end = java.indexOf("*/", i + 2);
                end = end < 0 ? java.length() : end + 2;
            } else if (java.startsWith("\"\"\"", i)) {
                end = closed(java, i + 3, "\"\"\"");
                out.add(new Quoted(line, java.substring(i, end)));
            } else if (java.charAt(i) == '"') {
                end = closed(java, i + 1, "\"");
                out.add(new Quoted(line, java.substring(i, end)));
            } else if (java.charAt(i) == '\'') {
                end = closed(java, i + 1, "'");
            } else {
                end = i + 1;
            }
            for (int k = i; k < end; k++) if (java.charAt(k) == '\n') line++;
            i = end;
        }
        return out;
    }

    /** Where a text opened before {@code from} is closed: after the next {@code quote} no backslash stands before. */
    private static int closed(String java, int from, String quote) {
        int i = from;
        while (i < java.length() && !java.startsWith(quote, i)) i += java.charAt(i) == '\\' ? 2 : 1;
        return Math.min(java.length(), i + quote.length());
    }
}
