package de.droiddrone.pc.setup.ardu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fleet PRD AP-7 / AS-2: an ArduPilot parameter file (.param) as Mission Planner and QGroundControl read it - one
 * "NAME,VALUE" a line, '#' starts a comment. Written as the backup before every setup write, read back by RESTORE.
 */
public final class ParamFile {
    private ParamFile() {
    }

    public static void write(Path file, Map<String, Float> params, String header) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        List<String> l = new ArrayList<>();
        if (header != null) for (String h : header.split("\\R")) l.add("# " + h);
        for (Map.Entry<String, Float> e : params.entrySet()) l.add(e.getKey() + "," + value(e.getValue()));
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.write(tmp, l, StandardCharsets.US_ASCII);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** The parameters in a file, in its order; lines that aren't NAME,VALUE (or NAME VALUE / NAME\tVALUE) are skipped. */
    public static Map<String, Float> read(Path file) throws IOException {
        Map<String, Float> out = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash).strip();
            if (line.isEmpty()) continue;
            String[] p = line.split("[,\\s]+");
            if (p.length < 2 || !p[0].matches("[A-Z0-9_]{1,16}")) continue;
            try {
                out.put(p[0], Float.parseFloat(p[1]));
            } catch (NumberFormatException ignored) {
                // not a value
            }
        }
        return out;
    }

    /** "ArduPilot sysid 3 2026-11-23 14-05-31.param" in the backups folder. */
    public static Path backupName(Path dir, int sysId, LocalDateTime when) {
        return dir.resolve("ArduPilot sysid " + sysId + " " + when.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")) + ".param");
    }

    static String value(float v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e7) return Long.toString((long) v);
        String s = Float.toString(v);
        return s.contains("E") ? new java.math.BigDecimal(v).toPlainString() : s;
    }
}
