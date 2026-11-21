package de.droiddrone.bench.hw;

import de.droiddrone.pc.AppPaths;
import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.CliWrite;
import de.droiddrone.pc.setup.EscFirmware;
import de.droiddrone.pc.setup.EscLink;
import de.droiddrone.pc.setup.SetupConfirm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Everything a Betaflight flight controller on USB holds, read and filed before a test campaign writes to it - and
 * read again afterwards to compare ({@link BoardRestore}). Reads only: nothing on the board is changed.
 * <pre>
 * gradlew boardSnapshot [-Pport=COM9] [-Pescs=true]
 * </pre>
 * <b>What is read.</b> Over MSP: the board's id (MSP_UID), API / variant / version / build, the feature mask, mixer
 * and props direction, motor output order, beeper config, rx map, mode ranges. Then ONE visit to the CLI - "diff all"
 * and "dump all" - because leaving the CLI restarts Betaflight every time. With {@code -Pescs=true} also each ESC's
 * firmware and the raw page of its settings, through the ESC passthrough: that needs the battery in (props off), and
 * the ESCs play their start-up tones once when it ends. No motor turns.
 * <p>
 * <b>Where it goes.</b> {@code %LOCALAPPDATA%\DraupneBench\board\<uid>\<stamp>\}: diff-all.txt, dump-all.txt, msp.txt,
 * with -Pescs escs.txt and esc&lt;n&gt;-settings.bin, and snapshot.txt with each file's SHA-256 (a file changed
 * afterwards is not loaded). The diff also goes into DRAUPNE Link's "Betaflight backups" folder, to paste into
 * Betaflight Configurator's CLI by hand if all else fails.
 * <p>
 * An armed board is left alone: Betaflight opens no CLI while armed, and leaving the CLI restarts the board.
 */
public final class BoardSnapshot {
    public static final String INDEX = "snapshot.txt", DIFF = "diff-all.txt", DUMP = "dump-all.txt", MSP_FACTS = "msp.txt", ESCS = "escs.txt";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String FAILED = "! ", BLHELI_S = "blheli_s";

    /**
     * @param escs   also read the ESCs (battery in; their start-up tones at the end)
     * @param label  after the stamp in the folder's name ("before-restore"), or ""
     * @param timing how long to wait for a board that restarts
     */
    public record Options(boolean escs, String label, BoardPort.Timing timing) {
    }

    /** One fact read over MSP: its name in msp.txt and the request that gives it. */
    private record Fact(String name, int code, boolean v2) {
    }

    private static final List<Fact> FACTS = List.of(new Fact("uid", Msp.UID, false), new Fact("api_version", Msp.API_VERSION, false),
            new Fact("fc_variant", Msp.FC_VARIANT, false), new Fact("fc_version", Msp.FC_VERSION, false),
            new Fact("build_info", Msp.BUILD_INFO, false), new Fact("features", Msp.FEATURE_CONFIG, false),
            new Fact("mixer_and_props_direction", Msp.MIXER_CONFIG, false), new Fact("motor_order", Msp.MOTOR_OUTPUT_REORDERING, true),
            new Fact("beeper", Msp.BEEPER_CONFIG, false), new Fact("rx_map", Msp.RX_MAP, false),
            new Fact("mode_ranges", Msp.MODE_RANGES, false));
    /** The facts that say which firmware this is: a restore is only for the same one. */
    static final List<String> FIRMWARE = List.of("api_version", "fc_variant", "fc_version", "build_info");

    /**
     * One ESC as read.
     * @param says       its firmware and beacon ("Bluejay 0.19 - beacon 10 min"), or ""
     * @param problem    why it has no page, or null
     * @param settingsAt where its settings are in its memory
     * @param blheliS    BLHeli_S or built on it (Bluejay, JESC, BLHeli_M): its page has BLHeli_S's layout
     * @param page       the raw page of its settings, or null
     */
    public record Esc(int index, String says, String problem, int settingsAt, boolean blheliS, byte[] page) {
    }

    private final LocalDateTime taken;
    private final String label, port, uid, version, battery, diffAll, dumpAll;
    private final Map<String, String> msp;
    private final List<Esc> escs;
    private Path folder;

    private BoardSnapshot(LocalDateTime taken, String label, String port, String uid, String version, String battery,
                          String diffAll, String dumpAll, Map<String, String> msp, List<Esc> escs) {
        this.taken = taken;
        this.label = label;
        this.port = port;
        this.uid = uid;
        this.version = version;
        this.battery = battery;
        this.diffAll = diffAll;
        this.dumpAll = dumpAll;
        this.msp = msp;
        this.escs = escs;
    }

    /** The board's id as hex (MSP_UID's twelve bytes; Betaflight's board info when it has none), or "" - no id at all. */
    public String uid() {
        return uid;
    }

    /** Betaflight's version line from the CLI: "Betaflight / STM32F405 (S405) 4.4.1 ...". */
    public String version() {
        return version;
    }

    public String diffAll() {
        return diffAll;
    }

    public String dumpAll() {
        return dumpAll;
    }

    /** The facts read over MSP, by name, as hex - or "! why" for one the board did not give. */
    public Map<String, String> msp() {
        return msp;
    }

    /** The ESCs, motor 1 first; null when they were not read. */
    public List<Esc> escs() {
        return escs;
    }

    /** Where it is filed; null until written. */
    public Path folder() {
        return folder;
    }

    // ------------------------------------------------------------------ reading the board

    /**
     * Reads the board on this port. The CLI visit at the end restarts Betaflight: whoever needs the board again
     * opens it with {@link BoardPort#open}, which waits for it.
     */
    public static BoardSnapshot take(BetaflightUsb.Port port, Options o, Consumer<String> log) throws IOException {
        Map<String, String> msp;
        String battery, diff, dump;
        List<Esc> escs = null;
        try (BetaflightUsb fc = BoardPort.open(port, o.timing(), log)) {
            msp = facts(fc);
            if (Msp.armed(fc.msp(Msp.STATUS, null))) {
                throw new IOException("The board is armed: Betaflight opens no CLI then, and leaving the CLI restarts it. Disarm it first.");
            }
            BetaflightUsb.BatteryState b = null;
            try {
                b = fc.batteryState();
            } catch (IOException e) {
                // an old Betaflight without it
            }
            battery = b == null ? "not known" : b.present() ? b.volts() + " V, " + b.cells() + " cells" : "not in (" + b.volts() + " V)";
            if (o.escs()) {
                escs = b != null && !b.present() ? noEscs("no battery: an ESC without power doesn't answer") : readEscs(fc, log);
                mspAgain(fc); // the port was with the ESCs: Betaflight must answer again before its CLI is asked for
            }
            log.accept("Reading the CLI (diff all, dump all); leaving it restarts Betaflight.");
            fc.enterCli();
            diff = fc.command("diff all", 20_000);
            dump = fc.command("dump all", 30_000);
        } // closed with the CLI open: "exit" is said, nothing was changed
        if (!CliWrite.usableBackup(diff)) throw new IOException("Betaflight's \"diff all\" didn't come back whole: " + CliWrite.brief(diff));
        if (!CliWrite.usableBackup(dump)) throw new IOException("Betaflight's \"dump all\" didn't come back whole: " + CliWrite.brief(dump));
        return new BoardSnapshot(LocalDateTime.now(), o.label() == null ? "" : o.label(), port.systemName(), boardId(msp),
                BetaflightPlan.version(diff), battery, unixLines(diff), unixLines(dump), msp, escs);
    }

    /** The facts MSP gives, by name, as hex; "! why" for one this Betaflight refuses or does not know (said, not guessed). */
    static Map<String, String> facts(BetaflightUsb fc) throws IOException {
        Map<String, String> msp = new LinkedHashMap<>();
        for (Fact f : FACTS) {
            try {
                msp.put(f.name(), Msp.hex(f.v2() ? fc.msp2(f.code(), null) : fc.msp(f.code(), null)));
            } catch (IOException e) {
                String why = String.valueOf(e.getMessage());
                if (why.contains("disconnected") || why.contains("Could not write")) throw e; // the board is gone: no snapshot
                msp.put(f.name(), FAILED + why);
            }
        }
        return msp;
    }

    /** As Drone setup names a board for its CONFIRM (SetupConfirm): the UID's hex; "" when the board has none. */
    static String boardId(Map<String, String> msp) {
        String uid = msp.get("uid");
        if (uid == null || uid.startsWith(FAILED)) return "";
        String id = SetupConfirm.boardId(Msp.unhex(uid), null);
        return id == null ? "" : id.substring(id.indexOf(':') + 1);
    }

    /** After the ESC passthrough: MSP answers again (a few tries), and the board is still disarmed. */
    private static void mspAgain(BetaflightUsb fc) throws IOException {
        IOException last = null;
        for (int i = 0; i < 5; i++) {
            try {
                if (Msp.armed(fc.msp(Msp.STATUS, null))) throw new IOException("The board is armed. Disarm it first.");
                return;
            } catch (IOException e) {
                if (String.valueOf(e.getMessage()).contains("armed")) throw e;
                last = e;
            }
        }
        throw new IOException("No MSP after the visit to the ESCs (" + last.getMessage() + "): unplug the board's USB and battery, plug in again.");
    }

    private static List<Esc> noEscs(String why) {
        return List.of(new Esc(0, "", why, 0, false, null));
    }

    /** Every ESC's firmware and the page of its settings, read twice (the two must agree). Reads only. */
    private static List<Esc> readEscs(BetaflightUsb fc, Consumer<String> log) {
        List<Esc> out = new ArrayList<>();
        log.accept("Reading the ESCs: their start-up tones sound once when it ends. No motor turns.");
        try (EscLink link = fc.escs(log)) {
            List<EscFirmware.Info> infos = link.infoAll();
            for (int i = 0; i < infos.size(); i++) {
                EscFirmware.Info info = infos.get(i);
                String says = EscFirmware.summary(List.of(info));
                if (info.kind() == EscFirmware.Kind.UNKNOWN && info.chip().isEmpty()) { // no answer, or a chip not known
                    out.add(new Esc(i, "", info.problem(), 0, false, null));
                    continue;
                }
                try {
                    EscLink.Device d = link.init(i);
                    EscFirmware.Chip chip = EscFirmware.chip(d.signature(), d.mode());
                    byte[] page = link.readBytes(chip.settingsAt(), chip.pageSize());
                    boolean same = Arrays.equals(page, link.readBytes(chip.settingsAt(), chip.pageSize()));
                    out.add(new Esc(i, says, same ? null : "its settings read differently twice", chip.settingsAt(), info.kind().blheliS(),
                            same ? page : null));
                } catch (IOException | RuntimeException e) {
                    out.add(new Esc(i, says, String.valueOf(e.getMessage()), 0, false, null));
                }
            }
        } catch (IOException e) {
            return noEscs(e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------ files

    /**
     * Files it under boardRoot\&lt;uid&gt;\&lt;stamp&gt;[-label]\ with each file's SHA-256 in snapshot.txt, and (backupsDir
     * not null) copies the diff there for a paste by hand.
     * @return the snapshot's folder
     */
    public Path write(Path boardRoot, Path backupsDir) throws IOException {
        String stamp = taken.format(STAMP);
        Path dir = fresh(boardRoot.resolve(uid.isEmpty() ? "no-id" : uid), stamp + (label.isEmpty() ? "" : "-" + label), "");
        Files.createDirectories(dir);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(DIFF, bytes(diffAll + "\n"));
        files.put(DUMP, bytes(dumpAll + "\n"));
        StringBuilder facts = new StringBuilder();
        msp.forEach((name, value) -> facts.append(name).append(" = ").append(value).append('\n'));
        files.put(MSP_FACTS, bytes(facts.toString()));
        if (escs != null) {
            StringBuilder list = new StringBuilder();
            for (Esc e : escs) {
                list.append("esc ").append(e.index() + 1).append(" = ").append(e.says().isEmpty() ? "-" : oneField(e.says())).append(" | ")
                        .append(e.problem() == null ? "ok" : oneField(e.problem())).append(" | ").append(String.format("%04X", e.settingsAt()))
                        .append(" | ").append(e.blheliS() ? BLHELI_S : "other").append('\n');
                if (e.page() != null) files.put(pageFile(e.index()), e.page());
            }
            files.put(ESCS, bytes(list.toString()));
        }
        StringBuilder index = new StringBuilder("# A flight controller's settings as BoardSnapshot read them. A file changed here no longer fits its\n"
                + "# SHA-256 below, and BoardRestore then refuses the snapshot.\n");
        index.append("taken = ").append(stamp).append('\n').append("label = ").append(label).append('\n')
                .append("port = ").append(port).append('\n').append("uid = ").append(uid).append('\n')
                .append("version = ").append(version).append('\n').append("battery = ").append(battery).append('\n');
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            Files.write(dir.resolve(f.getKey()), f.getValue());
            index.append("sha256 ").append(f.getKey()).append(" = ").append(sha256(f.getValue())).append('\n');
        }
        Files.write(dir.resolve(INDEX), bytes(index.toString()));
        if (backupsDir != null) {
            Files.createDirectories(backupsDir);
            Files.write(fresh(backupsDir, "betaflight-" + stamp, ".txt"), bytes("# " + version + "\n# saved by DRAUPNE Link's test bench before tests "
                    + "that write to this flight controller (" + dir + ").\n# To go back by hand: paste this into Betaflight Configurator's CLI "
                    + "tab and press Enter - its last line is save.\n\n" + diffAll + "\n"));
        }
        folder = dir;
        return dir;
    }

    /** A snapshot's folder read back. Refused when a file is missing or is not what its SHA-256 says. */
    public static BoardSnapshot load(Path dir) throws IOException {
        Path indexFile = dir.resolve(INDEX);
        if (!Files.isRegularFile(indexFile)) throw new IOException(dir + " is no snapshot: no " + INDEX + " in it.");
        Map<String, String> index = new LinkedHashMap<>(), hashes = new LinkedHashMap<>();
        for (String line : Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
            int eq = line.indexOf(" = ");
            if (line.startsWith("#") || eq < 0) continue;
            String key = line.substring(0, eq), value = line.substring(eq + 3);
            if (key.startsWith("sha256 ")) hashes.put(key.substring(7), value);
            else index.put(key, value);
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Map.Entry<String, String> h : hashes.entrySet()) {
            Path file = dir.resolve(h.getKey());
            if (!Files.isRegularFile(file)) throw new IOException("The snapshot's " + h.getKey() + " is missing: not used.");
            byte[] content = Files.readAllBytes(file);
            if (!sha256(content).equals(h.getValue())) {
                throw new IOException("The snapshot's " + h.getKey() + " is not as it was written (its SHA-256 differs): not used.");
            }
            files.put(h.getKey(), content);
        }
        for (String needed : List.of(DIFF, DUMP, MSP_FACTS)) {
            if (!files.containsKey(needed)) throw new IOException(dir + " is no whole snapshot: no " + needed + " in " + INDEX + ".");
        }
        Map<String, String> msp = new LinkedHashMap<>();
        for (String line : text(files.get(MSP_FACTS)).split("\n")) {
            int eq = line.indexOf(" = ");
            if (eq > 0) msp.put(line.substring(0, eq), line.substring(eq + 3));
        }
        List<Esc> escs = null;
        if (files.containsKey(ESCS)) {
            escs = new ArrayList<>();
            for (String line : text(files.get(ESCS)).split("\n")) {
                String[] p = line.split(" = | \\| ");
                if (p.length < 5 || !p[0].startsWith("esc ")) continue;
                int i = Integer.parseInt(p[0].substring(4)) - 1;
                escs.add(new Esc(i, p[1].equals("-") ? "" : p[1], p[2].equals("ok") ? null : p[2], Integer.parseInt(p[3], 16),
                        p[4].equals(BLHELI_S), files.get(pageFile(i))));
            }
        }
        BoardSnapshot s = new BoardSnapshot(LocalDateTime.parse(index.getOrDefault("taken", LocalDateTime.now().format(STAMP)), STAMP),
                index.getOrDefault("label", ""), index.getOrDefault("port", ""), index.getOrDefault("uid", ""),
                index.getOrDefault("version", ""), index.getOrDefault("battery", ""), stripEnd(text(files.get(DIFF))),
                stripEnd(text(files.get(DUMP))), msp, escs);
        s.folder = dir;
        return s;
    }

    private static String pageFile(int esc) {
        return "esc" + (esc + 1) + "-settings.bin";
    }

    /** Words for one field of an escs.txt line: nothing in them that ends the field or the line. */
    private static String oneField(String words) {
        return words.replace('|', '/').replace('=', ':').replaceAll("\\R", " ");
    }

    /** A name that is not taken yet: name, name-2, name-3 ... (two snapshots in one second). */
    private static Path fresh(Path dir, String name, String ending) {
        Path p = dir.resolve(name + ending);
        for (int n = 2; Files.exists(p); n++) p = dir.resolve(name + "-" + n + ending);
        return p;
    }

    private static String unixLines(String text) {
        return stripEnd(text.replace("\r\n", "\n").replace('\r', '\n'));
    }

    private static String stripEnd(String text) {
        return text.stripTrailing();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String sha256(byte[] content) {
        try {
            return Msp.hex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ comparing

    /**
     * A line Betaflight changes by itself: its flight statistics count up with every flight. Left out of a comparison.
     */
    static boolean changesByItself(String line) {
        return line.startsWith("set stats_");
    }

    /**
     * Every difference between this snapshot and the board as read now: "diff all" and "dump all" line by line
     * (blank lines and the lines that change by themselves left out), and each MSP fact. Empty: the same. The ESCs
     * are compared apart ({@link #escDifferences}).
     */
    public List<String> differences(BoardSnapshot now) {
        List<String> out = new ArrayList<>();
        compareText(out, "diff all", diffAll, now.diffAll);
        compareText(out, "dump all", dumpAll, now.dumpAll);
        Set<String> names = new LinkedHashSet<>(msp.keySet());
        names.addAll(now.msp.keySet());
        for (String name : names) {
            if (!Objects.equals(msp.get(name), now.msp.get(name))) {
                out.add("MSP " + name + ": " + msp.get(name) + " in the snapshot, " + now.msp.get(name) + " now");
            }
        }
        return out;
    }

    /** What differs between this snapshot's firmware and the other's (the MSP facts that name it, the CLI's line). */
    public List<String> firmwareDifferences(BoardSnapshot now) {
        List<String> out = new ArrayList<>();
        for (String name : FIRMWARE) {
            if (!Objects.equals(msp.get(name), now.msp.get(name))) out.add(name + " " + msp.get(name) + " -> " + now.msp.get(name));
        }
        if (!version.equals(now.version)) out.add("\"" + version + "\" -> \"" + now.version + "\"");
        return out;
    }

    /**
     * The ESCs' settings pages against the ones read now. Empty when this snapshot has none, or all are the same; a
     * page that could not be read now is a difference too - it cannot be said to be the same.
     */
    public List<String> escDifferences(BoardSnapshot now) {
        List<String> out = new ArrayList<>();
        if (escs == null) return out;
        if (now.escs == null) {
            out.add("ESCs: not read now - not compared");
            return out;
        }
        for (Esc was : escs) {
            if (was.page() == null) continue; // nothing kept of it to compare with
            Esc is = now.escs.stream().filter(e -> e.index() == was.index()).findFirst().orElse(null);
            String name = "ESC " + (was.index() + 1);
            if (is == null || is.page() == null) {
                Esc why = is != null ? is : now.escs.isEmpty() ? null : now.escs.get(0); // "no battery" stands in the first
                out.add(name + ": not read now (" + (why == null ? "no ESC answered" : why.problem()) + ") - not compared");
            } else if (!Arrays.equals(was.page(), is.page())) {
                List<String> where = new ArrayList<>();
                int n = Math.max(was.page().length, is.page().length), count = 0;
                for (int i = 0; i < n; i++) {
                    int a = i < was.page().length ? was.page()[i] & 0xff : -1, b = i < is.page().length ? is.page()[i] & 0xff : -1;
                    if (a == b) continue;
                    if (count++ < 8) where.add(String.format("%s %02X -> %02X", offsetName(was.blheliS(), i), a, b));
                }
                out.add(name + ": its settings changed (" + count + " byte" + (count == 1 ? "" : "s") + ": " + String.join(", ", where)
                        + (count > 8 ? ", ..." : "") + ") - from " + was.says() + " to " + is.says());
            }
        }
        return out;
    }

    /** A byte of a settings page: by its name too, where the page is BLHeli_S's and this app knows the byte. */
    private static String offsetName(boolean blheliS, int offset) {
        if (blheliS && offset == EscFirmware.MOTOR_DIRECTION) return "0x0B (motor direction)";
        if (blheliS && offset == EscFirmware.BEACON_DELAY) return "0x1D (beacon delay)";
        return String.format("0x%02X", offset);
    }

    private record Line(String section, String text) {
        String shown() {
            return section.isEmpty() ? text : "[" + section + "] " + text;
        }
    }

    /** The lines that count, each with the heading it stands under ("# master", "# profile 1"). */
    private static List<Line> lines(String text) {
        List<Line> out = new ArrayList<>();
        String section = "";
        for (String raw : text.split("\\R")) {
            String t = raw.strip();
            if (t.isEmpty() || changesByItself(t)) continue;
            boolean heading = t.startsWith("#");
            if (heading) section = t.substring(1).strip();
            out.add(new Line(heading ? "" : section, t));
        }
        return out;
    }

    /** The lines only one of the two has, in order: the longest run both share is taken as the same. */
    private static void compareText(List<String> out, String what, String was, String now) {
        List<Line> a = lines(was), b = lines(now);
        int head = 0, endA = a.size(), endB = b.size();
        while (head < endA && head < endB && a.get(head).text().equals(b.get(head).text())) head++;
        while (endA > head && endB > head && a.get(endA - 1).text().equals(b.get(endB - 1).text())) {
            endA--;
            endB--;
        }
        int n = endA - head, m = endB - head;
        int[][] common = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                common[i][j] = a.get(head + i).text().equals(b.get(head + j).text()) ? common[i + 1][j + 1] + 1
                        : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a.get(head + i).text().equals(b.get(head + j).text())) {
                i++;
                j++;
            } else if (j >= m || i < n && common[i + 1][j] >= common[i][j + 1]) {
                out.add(what + ": only in the snapshot: " + a.get(head + i++).shown());
            } else {
                out.add(what + ": only on the board now: " + b.get(head + j++).shown());
            }
        }
    }

    // ------------------------------------------------------------------ by hand

    /** %LOCALAPPDATA%\DraupneBench\board: every board's snapshots, a folder a board. */
    public static Path boardRoot() {
        String local = System.getenv("LOCALAPPDATA");
        Path base = local != null && !local.isBlank() ? Path.of(local) : Path.of(System.getProperty("user.home"), "AppData", "Local");
        return base.resolve("DraupneBench").resolve("board");
    }

    /** args: the port ("" = the one flight controller there is), "true" to read the ESCs too. */
    public static void main(String[] args) {
        Path backups = null;
        try {
            backups = AppPaths.betaflightBackupsDir();
        } catch (IOException e) {
            System.out.println("No copy of the diff for a paste by hand (" + e.getMessage() + "): the snapshot's own folder has it.");
        }
        System.exit(byHand(args, System.out, boardRoot(), backups, BoardPort.Timing.REAL));
    }

    /** What main does, said to out: 0 when the snapshot is filed and Betaflight is back after the CLI visit's restart. */
    static int byHand(String[] args, java.io.PrintStream out, Path boardRoot, Path backupsDir, BoardPort.Timing timing) {
        String wanted = args.length > 0 ? args[0] : "";
        boolean escs = args.length > 1 && args[1].equalsIgnoreCase("true");
        Consumer<String> log = s -> out.println("  " + s);
        BetaflightUsb.Port port;
        Path dir;
        try {
            port = BoardPort.find(wanted, timing);
            out.println("Snapshot of the flight controller on " + port + ". Nothing on it is changed.");
            out.println("Sound to expect: " + (escs ? "the ESCs' start-up tones, once. No motor turns." : "none."));
            BoardSnapshot s = take(port, new Options(escs, "", timing), log);
            dir = s.write(boardRoot, backupsDir);
            out.println("Board " + (s.uid().isEmpty() ? "(no id)" : s.uid()) + ": " + s.version());
            out.println("Battery: " + s.battery);
            s.msp().forEach((name, value) -> out.println("  " + name + " = " + value));
            if (s.escs() != null) {
                for (Esc e : s.escs()) {
                    out.println("  ESC " + (e.index() + 1) + ": " + (e.problem() != null ? e.problem() : e.says() + ", " + e.page().length + " bytes of settings kept"));
                }
            }
            out.println("diff all: " + s.diffAll().lines().count() + " lines; dump all: " + s.dumpAll().lines().count() + " lines.");
            out.println("SNAPSHOT: " + dir);
            out.println("To put it back: gradlew boardRestore -Psnapshot=\"" + dir + "\"");
        } catch (IOException e) {
            out.println("NO SNAPSHOT: " + e.getMessage());
            return 1;
        }
        return BoardPort.isBack(port, timing, out) ? 0 : 1; // the CLI visit restarted it
    }
}
