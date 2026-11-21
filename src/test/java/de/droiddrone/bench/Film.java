package de.droiddrone.bench;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A scenario's film strip: every step the bench took (what it pressed, what it waited for), each with a picture of
 * the app at that moment, written as it goes - a scenario that hangs or is killed still leaves what it did. One
 * folder per scenario under the run's {@code film} folder: step-NNN.png, steps.tsv and an index.html to look through.
 */
public final class Film {
    public record Step(int number, long atMs, String action, String picture, String mode, String note) {
    }

    private final Path dir;
    private final String scenario;
    private final List<Step> steps = new ArrayList<>();
    private final List<String> remarks = new ArrayList<>();
    private final long startMs = System.currentTimeMillis();

    public Film(Path dir, String scenario) throws IOException {
        this.dir = Files.createDirectories(dir);
        this.scenario = scenario;
    }

    public Path dir() {
        return dir;
    }

    public synchronized List<Step> steps() {
        return List.copyOf(steps);
    }

    /** One step: what was done and how the app looked right after. picture may be null (nothing to show). */
    public synchronized Step add(String action, BufferedImage picture, String mode, String note) {
        int n = steps.size() + 1;
        String file = null;
        if (picture != null) {
            file = String.format("step-%03d.png", n);
            try {
                ImageIO.write(picture, "png", dir.resolve(file).toFile());
            } catch (IOException e) {
                note = (note == null ? "" : note + "; ") + "picture not saved: " + e.getMessage();
                file = null;
            }
        }
        Step s = new Step(n, System.currentTimeMillis(), action, file, mode, note == null ? "" : note);
        steps.add(s);
        append(s);
        return s;
    }

    /** An extra picture beside the last step's (what the component painted, a detail). */
    public synchronized String extra(String suffix, BufferedImage picture) {
        String file = String.format("step-%03d-%s.png", steps.size(), suffix);
        try {
            ImageIO.write(picture, "png", dir.resolve(file).toFile());
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    /** Something on a picture that looks wrong ({@link Look}): for a person to look at, never a failure by itself. */
    public synchronized void look(int step, String text) {
        try {
            Files.writeString(dir.resolve("look.tsv"), step + "\t" + text.replace('\t', ' ').replace('\n', ' ') + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
        looks.add("step " + step + ": " + text);
    }

    private final List<String> looks = new ArrayList<>();

    /** A line for the report that is no step: what was checked, what was seen. */
    public synchronized void remark(String text) {
        remarks.add(text);
        try {
            Files.writeString(dir.resolve("remarks.txt"), text.replace("\n", " ") + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private void append(Step s) {
        try {
            Files.writeString(dir.resolve("steps.tsv"), s.number() + "\t" + (s.atMs() - startMs) + "\t" + s.mode() + "\t"
                            + s.action().replace('\t', ' ').replace('\n', ' ') + "\t" + (s.picture() == null ? "" : s.picture()) + "\t"
                            + s.note().replace('\t', ' ').replace('\n', ' ') + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    /** The page to look through, and the verdict file the run's report reads. */
    public synchronized void close(boolean passed, List<String> problems) {
        StringBuilder h = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>").append(esc(scenario)).append("</title><style>")
                .append("body{font:14px Segoe UI,sans-serif;background:#14181c;color:#d8dee4;margin:24px}h1{font-size:20px}")
                .append(".ok{color:#7bd88f}.bad{color:#ff7b72}.step{margin:18px 0;padding:12px;background:#1c2228;border-radius:6px}")
                .append(".step img{max-width:100%;border:1px solid #333;margin-top:8px}.n{color:#8b949e}.note{color:#e3b341}li{margin:4px 0}")
                .append("</style></head><body><h1>").append(esc(scenario)).append(" - <span class=\"").append(passed ? "ok\">passed" : "bad\">FAILED")
                .append("</span></h1>");
        if (!problems.isEmpty()) {
            h.append("<ul class=\"bad\">");
            for (String p : problems) h.append("<li>").append(esc(p)).append("</li>");
            h.append("</ul>");
        }
        if (!remarks.isEmpty()) {
            h.append("<ul>");
            for (String r : remarks) h.append("<li>").append(esc(r)).append("</li>");
            h.append("</ul>");
        }
        if (!looks.isEmpty()) {
            h.append("<p>To look at (found on the pictures, not failures):</p><ul class=\"note\">");
            for (String r : looks) h.append("<li>").append(esc(r)).append("</li>");
            h.append("</ul>");
        }
        for (Step s : steps) {
            h.append("<div class=\"step\"><span class=\"n\">").append(s.number()).append(" &middot; ").append((s.atMs() - startMs) / 1000.0)
                    .append(" s &middot; ").append(esc(s.mode())).append("</span> ").append(esc(s.action()));
            if (!s.note().isEmpty()) h.append(" <span class=\"note\">").append(esc(s.note())).append("</span>");
            if (s.picture() != null) h.append("<br><img loading=\"lazy\" src=\"").append(s.picture()).append("\">");
            h.append("</div>");
        }
        h.append("</body></html>");
        try {
            Files.writeString(dir.resolve("index.html"), h.toString(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("verdict.txt"), (passed ? "passed" : "FAILED") + "\t" + scenario + "\t" + steps.size() + " steps\t"
                    + (System.currentTimeMillis() - startMs) / 1000 + " s\n" + String.join("\n", problems) + (problems.isEmpty() ? "" : "\n"), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
