package de.droiddrone.pc.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Where the mobile masts are (1.0.44, 4G COVERAGE): OpenCelliD's open database of cells (CC BY-SA 4.0), 4G (LTE) and
 * 5G (NR) only. Its positions are estimates from phones' measurements, not the masts' own places: often a few hundred
 * metres off, towards where the phones were.
 * <p>
 * The area API ({@code /cell/getInArea}) takes a box of at most 4 km2 and answers 50 cells a request, out of 1,000
 * request credits a day per key. So the world is cut into fixed boxes ({@link Tile}, about 1.8 x 1.8 km, always
 * under the limit) and each box is fetched once, both radios, page by page, and kept on disk as a CSV for 30 days:
 * an area looked at once costs nothing the next time, and a box with no cells is remembered too (an empty file).
 * One request at a time, a pause between them. The other way in: the country file from OpenCelliD's downloads page
 * (242.csv.gz for Norway), imported once ({@link #importCsv}) - then the whole country is known without a request.
 */
public final class OpenCellId {
    public static final String ATTRIBUTION = "Cells: OpenCelliD, CC BY-SA 4.0";
    static final String API = "https://opencellid.org/cell/getInArea";
    /** The API's limit for a box (its error 3 "BBOX too big"). */
    public static final double MAX_AREA_M2 = 4_000_000;
    /** The API's most cells per answer. */
    public static final int PAGE = 50;
    /** Pages per radio per box: 400 cells, more than any 3 km2 has. */
    static final int MAX_PAGES = 8;
    /** A box's cells are fetched again after this. */
    public static final long MAX_AGE_MS = 30L * 24 * 3600 * 1000;
    /** The key's credits a day are 1,000; this app stops short of them so the key still works elsewhere today. */
    public static final int DAILY_BUDGET = 900;
    static final long GAP_MS = 300;
    /** A box's height in degrees of latitude: 1,781 m. Its width is a whole number of these in longitude, by the row. */
    static final double TILE_DEG = 0.016;
    /** What a box may be wide, in metres: under 4 km2 with room for how the server measures. */
    static final double TILE_MAX_WIDTH_M = 2100;
    private static final double M_PER_DEG = 111_320.0;
    static final String IMPORTED = "imported.csv";
    public static final List<String> RADIOS = List.of("LTE", "NR");

    /** One cell as OpenCelliD has it: its estimated place, the network (mcc, net = MNC), the range it was heard over. */
    public record Cell(String radio, int mcc, int net, double lat, double lon, int rangeM, int samples) {}

    /**
     * The Norwegian networks a SIM can be on (the pilot's is on one of them). The MNCs from the ITU list: Telenor 01
     * (12 and 17 also theirs), Telia 02 (ex NetCom; 05 and 08 are its virtual networks), Ice 14 (06 is its 450 MHz
     * data network). Other countries' cells count under ALL only.
     */
    public enum Operator {
        ALL("All networks"), TELENOR("Telenor"), TELIA("Telia"), ICE("Ice");

        public final String title;

        Operator(String title) {
            this.title = title;
        }

        /** The network a cell belongs to; null for one not on this list. */
        public static Operator of(int mcc, int net) {
            if (mcc != 242) return null;
            return switch (net) {
                case 1, 12, 17 -> TELENOR;
                case 2, 5, 8 -> TELIA;
                case 6, 14 -> ICE;
                default -> null;
            };
        }

        public boolean has(Cell c) {
            return this == ALL || of(c.mcc(), c.net()) == this;
        }

        /** The settings value; ALL for anything unknown. */
        public static Operator parse(String value) {
            for (Operator o : values()) if (o.name().equalsIgnoreCase(value == null ? "" : value.trim())) return o;
            return ALL;
        }
    }

    // ---------------------------------------------------------------- the boxes

    /** Columns of a row's boxes per {@link #TILE_DEG} of longitude: the further north, the more, so none is too wide. */
    static int lonSpan(int row) {
        double nearEquator = Math.min(Math.abs(row * TILE_DEG), Math.abs((row + 1) * TILE_DEG)); // a box is widest at its edge nearer the equator
        double width = TILE_DEG * M_PER_DEG * Math.cos(Math.toRadians(Math.min(89, nearEquator)));
        return Math.max(1, (int) Math.floor(TILE_MAX_WIDTH_M / width));
    }

    /** A box of the fixed grid: row by latitude, column by longitude within its row. */
    public record Tile(int row, int col) {
        public double south() {
            return row * TILE_DEG;
        }

        public double north() {
            return (row + 1) * TILE_DEG;
        }

        public double west() {
            return col * (TILE_DEG * lonSpan(row));
        }

        public double east() {
            return (col + 1) * (TILE_DEG * lonSpan(row));
        }

        /** The API's BBOX: latmin,lonmin,latmax,lonmax. */
        String bbox() {
            return String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", south(), west(), north(), east());
        }

        /** Its area on the ground, from its widest edge (over the sphere it is a little less). */
        double areaM2() {
            double nearEquator = Math.min(Math.abs(south()), Math.abs(north()));
            return (north() - south()) * M_PER_DEG * (east() - west()) * M_PER_DEG * Math.cos(Math.toRadians(nearEquator));
        }

        String fileName() {
            return row + "_" + col + ".csv";
        }

        public static Tile at(double lat, double lon) {
            int row = rowAt(lat);
            return new Tile(row, colAt(row, lon));
        }

        public boolean contains(double lat, double lon) {
            return at(lat, lon).equals(this);
        }
    }

    /** The row whose edges hold the latitude (the edges as Tile works them out: a division alone can land a hair off). */
    static int rowAt(double lat) {
        int row = (int) Math.floor(lat / TILE_DEG);
        if (row * TILE_DEG > lat) row--;
        else if ((row + 1) * TILE_DEG <= lat) row++;
        return row;
    }

    static int colAt(int row, double lon) {
        double span = TILE_DEG * lonSpan(row);
        int col = (int) Math.floor(lon / span);
        if (col * span > lon) col--;
        else if ((col + 1) * span <= lon) col++;
        return col;
    }

    /** The boxes covering an area, the one nearest its centre first (a capped fetch gets the middle). */
    public static List<Tile> tiles(double minLat, double maxLat, double minLon, double maxLon) {
        double cLat = (minLat + maxLat) / 2, cLon = (minLon + maxLon) / 2;
        List<Tile> out = new ArrayList<>();
        for (int row = rowAt(minLat); row <= rowAt(maxLat); row++) {
            for (int col = colAt(row, minLon); col <= colAt(row, maxLon); col++) out.add(new Tile(row, col));
        }
        double cos = Math.cos(Math.toRadians(cLat));
        out.sort(Comparator.comparingDouble(t -> Math.hypot(((t.west() + t.east()) / 2 - cLon) * cos, (t.south() + t.north()) / 2 - cLat)));
        return out;
    }

    // ---------------------------------------------------------------- reading the answers and files

    /** What the API said: the cells, or its error code (2 key unknown, 3 bad input, 5 server, 7 daily limit) and text. */
    public record Answer(List<Cell> cells, int errorCode, String error) {
        boolean ok() {
            return cells != null;
        }
    }

    /** The JSON answer: {"count":n,"cells":[...]} or {"error":"...","code":n} (code 1, nothing found, is an empty answer). */
    public static Answer parseJson(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return new Answer(null, 3, "Not an answer: " + shorten(json));
            JsonObject o = root.getAsJsonObject();
            if (o.has("error")) {
                int code = o.has("code") ? o.get("code").getAsInt() : 3;
                if (code == 1) return new Answer(List.of(), 0, null);
                return new Answer(null, code, o.get("error").getAsString());
            }
            List<Cell> cells = new ArrayList<>();
            JsonArray array = o.has("cells") && o.get("cells").isJsonArray() ? o.getAsJsonArray("cells") : new JsonArray();
            for (JsonElement e : array) {
                JsonObject c = e.getAsJsonObject();
                String radio = str(c, "radio");
                double lat = num(c, "lat"), lon = num(c, "lon");
                if (radio == null || Double.isNaN(lat) || Double.isNaN(lon)) continue;
                cells.add(new Cell(radio.toUpperCase(Locale.ROOT), (int) num(c, "mcc"), (int) num(c, "mnc"), lat, lon,
                        (int) Math.max(0, num(c, "range")), (int) Math.max(0, num(c, "samples"))));
            }
            return new Answer(cells, 0, null);
        } catch (RuntimeException e) {
            return new Answer(null, 3, "Not an answer: " + shorten(json));
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static double num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try {
            return e == null || e.isJsonNull() ? Double.NaN : e.getAsDouble();
        } catch (RuntimeException ex) {
            return Double.NaN;
        }
    }

    /**
     * OpenCelliD's CSV: the download files' columns (radio,mcc,net,area,cell,unit,lon,lat,range,samples,...), with or
     * without their header line, or any header naming the columns (the API's csv answer: lat,lon,mcc,mnc,...,radio).
     * 4G and 5G cells only (radios as {@link #RADIOS}); lines that don't read are skipped.
     */
    public static List<Cell> parseCsv(BufferedReader in) throws IOException {
        int radio = 0, mcc = 1, net = 2, lon = 6, lat = 7, range = 8, samples = 9;
        List<Cell> out = new ArrayList<>();
        boolean first = true;
        for (String line; (line = in.readLine()) != null; ) {
            if (line.isBlank()) continue;
            String[] f = line.split(",", -1);
            if (first) {
                first = false;
                List<String> head = new ArrayList<>();
                for (String s : f) head.add(s.trim().toLowerCase(Locale.ROOT));
                if (head.contains("lat") && head.contains("radio")) {
                    radio = head.indexOf("radio");
                    mcc = head.indexOf("mcc");
                    net = head.contains("net") ? head.indexOf("net") : head.indexOf("mnc");
                    lon = head.indexOf("lon");
                    lat = head.indexOf("lat");
                    range = head.indexOf("range");
                    samples = head.indexOf("samples");
                    continue;
                }
            }
            try {
                String r = f[radio].trim().toUpperCase(Locale.ROOT);
                if (!RADIOS.contains(r)) continue;
                out.add(new Cell(r, Integer.parseInt(f[mcc].trim()), Integer.parseInt(f[net].trim()), Double.parseDouble(f[lat].trim()),
                        Double.parseDouble(f[lon].trim()), range >= 0 ? intOr(f[range]) : 0, samples >= 0 ? intOr(f[samples]) : 0));
            } catch (RuntimeException e) {
                // a line that isn't a cell
            }
        }
        return out;
    }

    private static int intOr(String s) {
        try {
            return (int) Math.round(Double.parseDouble(s.trim()));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Cells in the download files' column order (so the importer reads its own files), with their header. */
    static String toCsv(List<Cell> cells) {
        StringBuilder b = new StringBuilder("radio,mcc,net,area,cell,unit,lon,lat,range,samples\n");
        for (Cell c : cells) {
            b.append(String.format(Locale.ROOT, "%s,%d,%d,0,0,0,%.6f,%.6f,%d,%d%n", c.radio(), c.mcc(), c.net(), c.lon(), c.lat(), c.rangeM(), c.samples()));
        }
        return b.toString();
    }

    /** A box fetched this long ago is still good. */
    public static boolean fresh(long fetchedMs, long nowMs) {
        return nowMs - fetchedMs < MAX_AGE_MS && fetchedMs <= nowMs + 60_000;
    }

    // ---------------------------------------------------------------- the store on disk and the fetching

    private final Path dir;
    private final String userAgent;
    private final boolean network;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private LocalDate creditsDay;
    private int creditsUsed;
    private LocalDate limitedOn; // the server said the day's credits are gone
    private long lastRequest;
    private volatile double[] importedBox; // {minLat, maxLat, minLon, maxLon} of the imported file; null: none
    private volatile List<Cell> imported = List.of();
    private boolean importLoaded;

    /** @param network false: disk only (tests) */
    public OpenCellId(Path dir, String userAgent, boolean network) {
        this(dir, userAgent, network, de.droiddrone.pc.PossiblyFlying::now);
    }

    /** @param held true while nothing may be fetched (this PC's PossiblyFlying flag) */
    OpenCellId(Path dir, String userAgent, boolean network, java.util.function.BooleanSupplier held) {
        this.dir = dir;
        this.userAgent = userAgent;
        this.network = network;
        this.held = held;
    }

    private final java.util.function.BooleanSupplier held;
    /** An Answer's code while a drone on this PC may fly (1.0.49): nothing asked, nothing counted. */
    public static final int HELD = 9;

    // ---- the day's count, kept on disk (1.0.49: a restart forgot it, and the key's 1000 a day were overrun)

    private boolean creditsLoaded;

    private Path creditsFile() {
        return dir.resolve("credits.txt");
    }

    /** "2026-11-07 412" or "2026-11-07 412 limited": the UTC day, the requests made that day, the server's no. */
    private synchronized void loadCredits() {
        if (creditsLoaded) return;
        creditsLoaded = true;
        try {
            Path f = creditsFile();
            if (!Files.isRegularFile(f)) return;
            String[] p = Files.readString(f, StandardCharsets.UTF_8).trim().split("\\s+");
            if (p.length < 2) return;
            LocalDate day = LocalDate.parse(p[0]);
            creditsDay = day;
            creditsUsed = Math.max(0, Integer.parseInt(p[1]));
            if (p.length > 2 && "limited".equals(p[2])) limitedOn = day;
        } catch (IOException | RuntimeException e) {
            // no count: from nothing, as before
        }
    }

    /** One request made today: counted, and the count kept on disk. */
    synchronized void countRequest() {
        loadCredits();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(creditsDay)) {
            creditsDay = today;
            creditsUsed = 0;
        }
        creditsUsed++;
        saveCredits();
    }

    private synchronized void saveCredits() {
        if (creditsDay == null) return;
        try {
            write(creditsFile(), (creditsDay + " " + creditsUsed + (creditsDay.equals(limitedOn) ? " limited" : "") + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // counted in memory still
        }
    }

    public OpenCellId(Path dir) {
        this(dir, "DRAUPNE-Link/" + de.droiddrone.pc.AppInfo.version() + " (+https://github.com/MelonNO/draupne-link)", true);
    }

    /** %APPDATA%\DraupneLink\opencellid: a CSV per box, and the imported country file. */
    public static Path defaultDir() {
        return de.droiddrone.pc.AppPaths.dataDir().resolve("opencellid");
    }

    private Path file(Tile t) {
        return dir.resolve("tiles").resolve(t.fileName());
    }

    /** The box's cells on disk, however old; null when it was never fetched. */
    public List<Cell> cached(Tile t) {
        Path f = file(t);
        if (!Files.isRegularFile(f)) return null;
        try (BufferedReader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            return parseCsv(in);
        } catch (IOException e) {
            return null;
        }
    }

    /** The box was fetched within {@link #MAX_AGE_MS}, or the imported file covers it. */
    public boolean isFresh(Tile t, long nowMs) {
        if (coveredByImport(t)) return true;
        try {
            Path f = file(t);
            return Files.isRegularFile(f) && fresh(Files.getLastModifiedTime(f).toMillis(), nowMs);
        } catch (IOException e) {
            return false;
        }
    }

    /** The imported file covers the box: no requests there. */
    public boolean coveredByImport(Tile t) {
        loadImported();
        double[] b = importedBox;
        return b != null && t.south() >= b[0] && t.north() <= b[1] && t.west() >= b[2] && t.east() <= b[3];
    }

    /** The imported cells within an area (none without an import). */
    public List<Cell> importedIn(double minLat, double maxLat, double minLon, double maxLon) {
        loadImported();
        List<Cell> out = new ArrayList<>();
        for (Cell c : imported) if (c.lat() >= minLat && c.lat() <= maxLat && c.lon() >= minLon && c.lon() <= maxLon) out.add(c);
        return out;
    }

    public boolean hasImport() {
        loadImported();
        return importedBox != null;
    }

    /** Requests left today by this app's own count (the server's count is the one that matters; it says so with code 7). */
    public synchronized int creditsLeft() {
        loadCredits();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (today.equals(limitedOn)) return 0;
        if (!today.equals(creditsDay)) return DAILY_BUDGET;
        return Math.max(0, DAILY_BUDGET - creditsUsed);
    }

    /**
     * Fetches a box's 4G and 5G cells, every page, and keeps them on disk. An error answer (key unknown, the day's
     * credits gone, no network: code 0) keeps nothing, so the box is asked for again another time. On the caller's thread.
     */
    public Answer fetch(Tile t, String key) {
        if (key == null || key.isBlank()) return new Answer(null, 2, "No OpenCelliD key");
        List<Cell> all = new ArrayList<>();
        for (String radio : RADIOS) {
            for (int page = 0; page < MAX_PAGES; page++) {
                if (creditsLeft() <= 0) return new Answer(null, 7, "Daily limit reached");
                String url = API + "?key=" + URLEncoder.encode(key.trim(), StandardCharsets.UTF_8) + "&BBOX=" + t.bbox()
                        + "&radio=" + radio + "&limit=" + PAGE + "&offset=" + page * PAGE + "&format=json";
                Answer a = request(url);
                if (!a.ok()) return a;
                all.addAll(a.cells());
                if (a.cells().size() < PAGE) break;
            }
        }
        try {
            write(file(t), toCsv(all).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // used this time anyway
        }
        return new Answer(all, 0, null);
    }

    private Answer request(String url) {
        if (held.getAsBoolean()) return new Answer(null, HELD, "Paused: a drone here may be flying");
        if (!network) return new Answer(null, 0, "Offline");
        synchronized (this) {
            long wait = lastRequest + GAP_MS - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Answer(null, 0, "Stopped");
                }
            }
            lastRequest = System.currentTimeMillis();
            countRequest();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", userAgent).header("Accept", "application/json").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Answer a = parseJson(response.body());
            if (a.ok() && response.statusCode() != 200) a = new Answer(null, response.statusCode() == 429 ? 7 : 5, "HTTP " + response.statusCode());
            if (a.errorCode() == 7) {
                synchronized (this) {
                    limitedOn = LocalDate.now(ZoneOffset.UTC);
                    if (!limitedOn.equals(creditsDay)) {
                        creditsDay = limitedOn;
                        creditsUsed = 0;
                    }
                    saveCredits();
                }
            }
            return a;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(null, 0, "Stopped");
        } catch (IOException | RuntimeException e) {
            return new Answer(null, 0, "No connection to opencellid.org");
        }
    }

    /**
     * The country file from OpenCelliD's downloads page (gzipped or not): its 4G and 5G cells are kept, and every box
     * inside the area they cover counts as known (no requests there). Replaces an earlier import. The cells kept.
     */
    public int importCsv(Path source) throws IOException {
        List<Cell> cells;
        try (InputStream raw = Files.newInputStream(source);
             InputStream in = source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz") ? new GZIPInputStream(raw, 1 << 16) : raw;
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            cells = parseCsv(reader);
        }
        if (cells.isEmpty()) throw new IOException("No mobile cells in " + source.getFileName());
        write(dir.resolve(IMPORTED), toCsv(cells).getBytes(StandardCharsets.UTF_8));
        synchronized (this) {
            importLoaded = false;
        }
        loadImported();
        return cells.size();
    }

    /** The imported file, read once when first wanted (not on the window's thread: it can be large). */
    private synchronized void loadImported() {
        if (importLoaded) return;
        importLoaded = true;
        Path f = dir.resolve(IMPORTED);
        if (!Files.isRegularFile(f)) return;
        try (BufferedReader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            List<Cell> cells = parseCsv(in);
            imported = cells;
            importedBox = box(cells);
        } catch (IOException e) {
            importedBox = null;
        }
    }

    /** The cells' bounding box, {minLat, maxLat, minLon, maxLon}; null for none. */
    static double[] box(List<Cell> cells) {
        if (cells.isEmpty()) return null;
        double[] b = {90, -90, 180, -180};
        for (Cell c : cells) {
            b[0] = Math.min(b[0], c.lat());
            b[1] = Math.max(b[1], c.lat());
            b[2] = Math.min(b[2], c.lon());
            b[3] = Math.max(b[3], c.lon());
        }
        return b;
    }

    /** For tests: a box's file as if fetched at a time. */
    void store(Tile t, List<Cell> cells, long fetchedMs) throws IOException {
        write(file(t), toCsv(cells).getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file(t), FileTime.fromMillis(fetchedMs));
    }

    static List<Cell> parseCsv(String text) {
        try {
            return parseCsv(new BufferedReader(new StringReader(text)));
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void write(Path file, byte[] data) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.write(tmp, data);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
