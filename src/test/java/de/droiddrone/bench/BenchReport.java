package de.droiddrone.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A bench run's report: one page ({@code report/index.html} in the run's folder) with every suite's results, each
 * scenario's film, the bad-link tables, the long run's curves, what the pictures' own checks found, the count of
 * controls seen and used, what did not run - and a second page with every picture small, to look through quickly
 * ({@code report/pictures.html}). Reads only what the run left in its folder; {@code gradlew benchReport}.
 */
public final class BenchReport {
    private BenchReport() {
    }

    public static void main(String[] args) throws IOException {
        Path run = Path.of(args.length > 0 ? args[0] : System.getProperty("bench.root")).toAbsolutePath().normalize();
        Path out = Files.createDirectories(run.resolve("report"));
        StringBuilder h = new StringBuilder(head("DRAUPNE test bench - " + run.getFileName()));
        h.append("<h1>DRAUPNE test bench - run ").append(Film.esc(run.getFileName().toString())).append("</h1>");
        h.append("<p class=\"n\">").append(Film.esc(run.toString())).append("</p>");

        String info = read(run.resolve("run.txt"));
        if (!info.isBlank()) h.append("<pre>").append(Film.esc(info)).append("</pre>");

        // ---- results of every suite (JUnit's own files)
        record Case(String suite, String cls, String name, double seconds, String failure, boolean skipped) {
        }
        List<Case> cases = new ArrayList<>();
        Path results = run.resolve("results");
        if (Files.isDirectory(results)) {
            try (Stream<Path> files = Files.walk(results)) {
                for (Path f : files.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml")).sorted().toList()) {
                    String suite = results.relativize(f).getName(0).toString();
                    String xml = Files.readString(f, StandardCharsets.UTF_8);
                    Matcher m = Pattern.compile("<testcase name=\"([^\"]*)\" classname=\"([^\"]*)\" time=\"([^\"]*)\"(/>|>(.*?)</testcase>)", Pattern.DOTALL).matcher(xml);
                    while (m.find()) {
                        String body = m.group(5) == null ? "" : m.group(5);
                        String failure = null;
                        Matcher fm = Pattern.compile("<(failure|error) message=\"([^\"]*)\"").matcher(body);
                        if (fm.find()) failure = unxml(fm.group(2));
                        cases.add(new Case(suite, m.group(2).substring(m.group(2).lastIndexOf('.') + 1), unxml(m.group(1)), parse(m.group(3)), failure, body.contains("<skipped")));
                    }
                }
            }
        }
        Map<String, int[]> bySuite = new TreeMap<>();
        for (Case c : cases) {
            int[] n = bySuite.computeIfAbsent(c.suite(), k -> new int[3]);
            n[c.failure() != null ? 1 : c.skipped() ? 2 : 0]++;
        }
        h.append("<h2>Suites</h2><table><tr><th>suite</th><th>passed</th><th>failed</th><th>known / skipped</th></tr>");
        bySuite.forEach((suite, n) -> h.append("<tr><td>").append(suite).append("</td><td class=\"ok\">").append(n[0]).append("</td><td class=\"")
                .append(n[1] > 0 ? "bad" : "n").append("\">").append(n[1]).append("</td><td class=\"n\">").append(n[2]).append("</td></tr>"));
        h.append("</table>");
        String pending = read(run.resolve("pending.txt"));
        if (!pending.isBlank()) h.append("<h2>Did not run - saved to start later</h2><pre class=\"note\">").append(Film.esc(pending)).append("</pre>");

        List<Case> failed = cases.stream().filter(c -> c.failure() != null).toList();
        if (!failed.isEmpty()) {
            h.append("<h2 class=\"bad\">Failed</h2><ul>");
            for (Case c : failed) {
                h.append("<li><b>").append(Film.esc(c.cls() + " - " + c.name())).append("</b> <span class=\"n\">(").append(c.suite()).append(")</span><br><span class=\"bad\">")
                        .append(Film.esc(c.failure().length() > 1200 ? c.failure().substring(0, 1200) + " ..." : c.failure())).append("</span></li>");
            }
            h.append("</ul>");
        }

        // ---- the films
        Path films = run.resolve("film");
        StringBuilder pictures = new StringBuilder(head("Pictures - " + run.getFileName()));
        pictures.append("<h1>Every picture of the run</h1><p class=\"n\">Click a picture for its full size. <a href=\"index.html\">Back to the report</a></p>");
        List<String> looks = new ArrayList<>();
        if (Files.isDirectory(films)) {
            h.append("<h2>Scenarios (each with its film)</h2><table><tr><th>scenario</th><th>verdict</th><th>steps</th><th>time</th><th>to look at</th></tr>");
            try (Stream<Path> dirs = Files.list(films)) {
                for (Path d : dirs.filter(Files::isDirectory).sorted().toList()) {
                    String[] v = read(d.resolve("verdict.txt")).split("\n")[0].split("\t");
                    boolean ok = v.length > 0 && v[0].equals("passed");
                    boolean known = v.length > 0 && !v[0].isBlank();
                    List<String> look = lines(d.resolve("look.tsv"));
                    for (String l : look) looks.add(d.getFileName() + "\t" + l);
                    h.append("<tr><td><a href=\"../film/").append(d.getFileName()).append("/index.html\">").append(Film.esc(d.getFileName().toString())).append("</a></td><td class=\"")
                            .append(ok ? "ok" : "bad").append("\">").append(known ? v[0] : "did not finish").append("</td><td>").append(v.length > 2 ? v[2] : "")
                            .append("</td><td>").append(v.length > 3 ? v[3] : "").append("</td><td class=\"note\">").append(look.isEmpty() ? "" : look.size()).append("</td></tr>");
                    pictures.append("<h2>").append(Film.esc(d.getFileName().toString())).append(ok ? "" : " <span class=\"bad\">FAILED</span>").append("</h2><div class=\"grid\">");
                    for (String row : lines(d.resolve("steps.tsv"))) {
                        String[] s = row.split("\t", -1);
                        if (s.length < 5 || s[4].isBlank()) continue;
                        String src = "../film/" + d.getFileName() + "/" + s[4];
                        pictures.append("<a href=\"").append(src).append("\"><img loading=\"lazy\" src=\"").append(src).append("\"><span>").append(s[0]).append(". ")
                                .append(Film.esc(s[3])).append("</span></a>");
                    }
                    pictures.append("</div>");
                }
            }
            h.append("</table><p><a href=\"pictures.html\">Every picture of the run on one page</a></p>");
        }

        // ---- bad-link tables and other tab-separated results
        for (String dir : List.of("matrix", "edge", "phone")) {
            Path m = run.resolve(dir);
            if (!Files.isDirectory(m)) continue;
            try (Stream<Path> files = Files.list(m)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".tsv")).sorted().toList()) {
                    h.append("<h2>").append(dir).append(": ").append(Film.esc(f.getFileName().toString().replace(".tsv", ""))).append("</h2>").append(table(lines(f)));
                }
            }
        }

        // ---- long runs: the curves
        Path soak = run.resolve("soak");
        if (Files.isDirectory(soak)) {
            try (Stream<Path> files = Files.list(soak)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".tsv")).sorted().toList()) {
                    h.append("<h2>Long run: ").append(Film.esc(f.getFileName().toString().replace(".tsv", ""))).append("</h2>").append(curves(lines(f)));
                }
            }
        }

        // ---- what the pictures' own checks found
        if (!looks.isEmpty()) {
            h.append("<h2>Found on the pictures (to look at - not failures)</h2><table><tr><th>scenario</th><th>step</th><th>what</th></tr>");
            Map<String, String> once = new TreeMap<>();
            for (String l : looks) {
                String[] s = l.split("\t", 3);
                if (s.length == 3) once.putIfAbsent(s[2].replaceAll("\\d+", "#"), "<tr><td>" + Film.esc(s[0]) + "</td><td>" + s[1] + "</td><td>" + Film.esc(s[2]) + "</td></tr>");
            }
            once.values().forEach(h::append);
            h.append("</table>");
        }

        // ---- the count of controls
        Map<String, Boolean> census = new TreeMap<>();
        Path cd = run.resolve("census");
        if (Files.isDirectory(cd)) {
            try (Stream<Path> files = Files.list(cd)) {
                for (Path f : files.sorted().toList()) {
                    for (String l : lines(f)) {
                        int tab = l.indexOf('\t');
                        if (tab > 0) census.merge(l.substring(tab + 1), l.startsWith("used"), Boolean::logicalOr);
                    }
                }
            }
        }
        if (!census.isEmpty()) {
            long used = census.values().stream().filter(b -> b).count();
            h.append("<h2>Buttons, menu rows, lists and fields: ").append(used).append(" used of ").append(census.size()).append(" seen</h2>");
            h.append("<p class=\"n\">Seen = it was on a showing window while a scenario ran. What no scenario ever brought up is not in this count.</p>");
            h.append("<details><summary>Never used (").append(census.size() - used).append(")</summary><table><tr><th>window</th><th>kind</th><th>words</th></tr>");
            census.forEach((k, u) -> {
                if (!u) h.append("<tr><td>").append(Film.esc(k).replace("\t", "</td><td>")).append("</td></tr>");
            });
            h.append("</table></details><details><summary>Used (").append(used).append(")</summary><table><tr><th>window</th><th>kind</th><th>words</th></tr>");
            census.forEach((k, u) -> {
                if (u) h.append("<tr><td>").append(Film.esc(k).replace("\t", "</td><td>")).append("</td></tr>");
            });
            h.append("</table></details>");
        }

        // ---- every result, for the record
        h.append("<h2>Every test</h2><details><summary>").append(cases.size()).append(" tests</summary><table><tr><th>suite</th><th>class</th><th>test</th><th>s</th><th>result</th></tr>");
        for (Case c : cases) {
            h.append("<tr><td>").append(c.suite()).append("</td><td>").append(Film.esc(c.cls())).append("</td><td>").append(Film.esc(c.name())).append("</td><td>")
                    .append(String.format("%.1f", c.seconds())).append("</td><td class=\"").append(c.failure() != null ? "bad\">FAILED" : c.skipped() ? "n\">known / skipped" : "ok\">passed").append("</td></tr>");
        }
        h.append("</table></details></body></html>");
        Files.writeString(out.resolve("index.html"), h.toString(), StandardCharsets.UTF_8);
        pictures.append("</body></html>");
        Files.writeString(out.resolve("pictures.html"), pictures.toString(), StandardCharsets.UTF_8);

        int nFailed = failed.size();
        String line = run.getFileName() + "\t" + cases.size() + " tests\t" + nFailed + " failed\t" + census.size() + " controls seen\t" + out.resolve("index.html");
        System.out.println("Report: " + out.resolve("index.html"));
        System.out.println(line);
        Files.writeString(run.resolve("summary.txt"), line + "\n", StandardCharsets.UTF_8);
    }

    private static String head(String title) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + Film.esc(title) + "</title><style>"
                + "body{font:14px Segoe UI,sans-serif;background:#14181c;color:#d8dee4;margin:24px}h1{font-size:22px}h2{font-size:17px;margin-top:28px}"
                + "a{color:#79c0ff}.ok{color:#7bd88f}.bad{color:#ff7b72}.n{color:#8b949e}.note{color:#e3b341}"
                + "table{border-collapse:collapse}td,th{border:1px solid #30363d;padding:4px 9px;text-align:left;vertical-align:top}th{color:#8b949e;font-weight:600}"
                + "pre{background:#1c2228;padding:10px;border-radius:6px;white-space:pre-wrap}summary{cursor:pointer;margin:8px 0}"
                + ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:10px}.grid a{display:block;background:#1c2228;padding:6px;border-radius:6px;"
                + "text-decoration:none;color:#d8dee4;font-size:12px}.grid img{width:100%;display:block;margin-bottom:4px}"
                + "svg{background:#1c2228;border-radius:6px;margin:6px 12px 6px 0}"
                + "</style></head><body>";
    }

    private static String table(List<String> rows) {
        StringBuilder t = new StringBuilder("<table>");
        for (int i = 0; i < rows.size(); i++) {
            String[] cells = rows.get(i).split("\t", -1);
            t.append("<tr>");
            for (String c : cells) {
                String cls = c.equals("ok") ? " class=\"ok\"" : c.equals("WRONG") ? " class=\"bad\"" : c.startsWith("KNOWN") ? " class=\"note\"" : "";
                t.append(i == 0 ? "<th>" : "<td" + cls + ">").append(Film.esc(c)).append(i == 0 ? "</th>" : "</td>");
            }
            t.append("</tr>");
        }
        return t.append("</table>").toString();
    }

    /** A table whose first column is minutes: each other column as a small curve with its first, lowest, highest and last value. */
    private static String curves(List<String> rows) {
        if (rows.size() < 3) return table(rows);
        String[] names = rows.get(0).split("\t", -1);
        StringBuilder s = new StringBuilder();
        for (int col = 1; col < names.length; col++) {
            List<double[]> points = new ArrayList<>();
            for (String r : rows.subList(1, rows.size())) {
                String[] c = r.split("\t", -1);
                try {
                    points.add(new double[]{Double.parseDouble(c[0]), Double.parseDouble(c[col])});
                } catch (RuntimeException ignored) {
                }
            }
            if (points.size() < 2) continue;
            double x0 = points.get(0)[0], x1 = points.get(points.size() - 1)[0], lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (double[] p : points) {
                lo = Math.min(lo, p[1]);
                hi = Math.max(hi, p[1]);
            }
            double span = hi - lo == 0 ? 1 : hi - lo;
            StringBuilder line = new StringBuilder();
            for (double[] p : points) {
                line.append(String.format(java.util.Locale.ROOT, "%.1f,%.1f ", 10 + (p[0] - x0) / Math.max(1e-9, x1 - x0) * 380, 90 - (p[1] - lo) / span * 70));
            }
            s.append("<svg width=\"400\" height=\"110\"><text x=\"10\" y=\"14\" fill=\"#8b949e\" font-size=\"12\">").append(Film.esc(names[col]))
                    .append(String.format(java.util.Locale.ROOT, ": %.0f at the start, %.0f at the end (lowest %.0f, highest %.0f)", points.get(0)[1], points.get(points.size() - 1)[1], lo, hi))
                    .append("</text><polyline fill=\"none\" stroke=\"#79c0ff\" stroke-width=\"1.5\" points=\"").append(line).append("\"/></svg>");
        }
        return s.toString();
    }

    private static String read(Path f) {
        try {
            return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static List<String> lines(Path f) {
        try {
            return Files.exists(f) ? Files.readAllLines(f, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String unxml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#10;", "\n").replace("&#13;", "").replace("&amp;", "&");
    }
}
