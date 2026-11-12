package de.droiddrone.pc.gui;

import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.overwatch.AlertCard;
import de.droiddrone.pc.overwatch.ClipIndex;
import de.droiddrone.pc.overwatch.OverwatchHub;
import de.droiddrone.pc.video.Klv;
import de.droiddrone.pc.video.StreamRecorder;
import de.droiddrone.pc.video.TestStreams;
import de.droiddrone.pc.video.VideoPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.65 (the pilot: "one stream for each drone", the video "only ... when someone is in pilot UAV [or] when a drone is
 * in observation mode", an alert "live picture then clip - remember to account for if there comes a new detection or
 * the user changes mode while the clip is playing", several PCs: a warning): the streams' owner, with a window of its
 * own making.
 */
class StanagControllerTest {
    private static final class NoScreen implements VideoPipeline.FrameSink {
        public void videoStarted(int width, int height, boolean mirror) {}
        public int[] targetSize() { return new int[]{0, 0}; }
        public int[] beginFrame(int width, int height) { return new int[width * height]; }
        public void presentFrame() {}
    }

    /** The main window's part, as the test sets it. */
    private final class Window implements StanagController.Host {
        final List<FleetCards.Card> cards = new ArrayList<>();
        String live;
        boolean spectating;
        VideoPipeline liveVideo;
        final List<StanagController.Tile> tiles = new ArrayList<>();
        final List<OverwatchHub.View> views = new ArrayList<>();
        final Map<String, String> others = new HashMap<>();
        final Map<String, String> flownBy = new HashMap<>();
        final List<String> log = new ArrayList<>(), placed = new ArrayList<>();
        ClipIndex index;

        public List<FleetCards.Card> cards() { return new ArrayList<>(cards); }
        public String livePhone() { return live; }
        public boolean spectating() { return spectating; }
        public VideoPipeline liveVideo() { return liveVideo; }
        public StanagFeed.Osd liveOsd() { return null; }
        public LongFunction<Klv.Frame> liveKlv(String phone) { return null; }
        public List<StanagController.Tile> tiles() { return new ArrayList<>(tiles); }
        public List<OverwatchHub.View> views() { return new ArrayList<>(views); }
        public ClipIndex clips() { return index; }
        public StanagKlv.Place place(String phone, boolean overwatching) {
            if (overwatching) placed.add(phone);
            return StanagKlv.Place.UNKNOWN;
        }
        public String flownBy(String phone) { return flownBy.get(phone); }
        public boolean upsideDown(String phone) { return false; }
        public boolean virtual(String phone) { return false; }
        public OsdLayout osdLayout() { return OsdLayout.defaults(); }
        public Map<String, String> otherStreams() { return new HashMap<>(others); }
        public void log(String line) { log.add(line); }
        public void save(Map<String, String> settings) { config.apply(settings); }
    }

    @TempDir
    Path dir;
    private PcConfig config;
    private Window window;
    private StanagController stanag;
    private final List<VideoPipeline> pipelines = new ArrayList<>();

    @BeforeAll
    static void look() {
        Theme.install();
    }

    @BeforeEach
    void start() throws Exception {
        config = PcConfig.defaults();
        window = new Window();
        window.index = new ClipIndex(dir.resolve("clips"));
        stanag = new StanagController(config, window, Runnable::run);
        stanag.targetOf = (name, slot) -> dir.resolve(name + ".ts").toString();
        stanag.clipQuietMs = 0;
    }

    @AfterEach
    void stop() {
        stanag.close();
        for (VideoPipeline p : pipelines) p.close();
    }

    private static FleetCards.Card card(String name, String badge) {
        MapView.Kind kind = switch (badge) {
            case "OVERWATCH" -> MapView.Kind.OVERWATCH;
            case "ASLEEP" -> MapView.Kind.ASLEEP;
            case "STANDBY" -> MapView.Kind.STANDBY;
            case "UNKNOWN" -> MapView.Kind.UNKNOWN;
            default -> MapView.Kind.ACTIVE;
        };
        return StanagShowTest.card(name, badge, kind, "ASLEEP".equals(badge) ? 60_000 : -1, "ASLEEP".equals(badge) ? 5 : 0, 2000, false);
    }

    private void cards(String... nameBadge) {
        window.cards.clear();
        for (int i = 0; i < nameBadge.length; i += 2) window.cards.add(card(nameBadge[i], nameBadge[i + 1]));
    }

    private VideoPipeline pipeline() {
        VideoPipeline p = new VideoPipeline(new NoScreen(), () -> 40);
        pipelines.add(p);
        return p;
    }

    /** A second of video through the pipeline, as its link hands it over. */
    private static void play(VideoPipeline p, int frames) throws InterruptedException {
        TestStreams.Stream s = TestStreams.h264(320, 240, frames, 10);
        p.onVideoConfig(320, 240, false, false, s.codecConfig());
        short n = 0;
        for (TestStreams.Packet k : s.packets()) {
            byte[] d = k.data();
            p.onVideoFragment(k.keyFrame(), ++n, 0, d.length, d, 0, d.length);
            Thread.sleep(33);
        }
    }

    private static void await(BooleanSupplier what, long ms, String why) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!what.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("waited " + ms + " ms for: " + why);
            Thread.sleep(10);
        }
    }

    private String showing(String name) {
        for (StanagController.Row r : stanag.rows()) if (r.name().equals(name)) return r.showing();
        return null;
    }

    private static OverwatchHub.View view(String drone, long startedAt, AlertCard.Shown shown, boolean ended, BufferedImage picture, String clipLine) {
        OverwatchProtocol.Alert a = new OverwatchProtocol.Alert(7, 1, 0, 0, startedAt, 0.4f, 0.4f, 0.1f, 0.2f, 0, 70, 0, 0, 65535, 80, drone);
        long now = System.currentTimeMillis();
        return new OverwatchHub.View(drone, shown, ended, now - 5000, ended ? now - 1000 : -1, picture, a, null, clipLine, picture != null, null, 0, null);
    }

    /** The streams on with the drone's alert running, then its motion over (the hub's view of it from then on). */
    private void alertThenEnded(String drone, long startedAt, String clipLine) {
        window.views.add(view(drone, startedAt, AlertCard.Shown.CARD, false, null, null));
        stanag.setOn(true);
        window.views.set(0, view(drone, startedAt, AlertCard.Shown.CARD, true, null, clipLine));
        stanag.refresh();
    }

    /** The event's full-quality clip on this PC, as the hub files it. */
    private void fileClip(String drone, long startedAt, int frames) throws Exception {
        String id = ClipIndex.baseId(startedAt);
        Properties meta = new Properties();
        meta.setProperty("startedAt", Long.toString(startedAt));
        window.index.save(drone, id, meta);
        TestStreams.Stream s = TestStreams.h264(320, 240, frames, 15);
        try (StreamRecorder r = new StreamRecorder(window.index.folder(drone).resolve(id + ".mkv"), false, 320, 240, s.codecConfig())) {
            long ms = 1000;
            for (TestStreams.Packet p : s.packets()) {
                r.write(p.data(), p.keyFrame(), ms);
                ms += 33;
            }
        }
        Files.move(window.index.folder(drone).resolve(id + ".mkv"), window.index.folder(drone).resolve(id + ".mp4"));
    }

    // ------------------------------------------------------------------ one stream per drone, each its own port

    @Test
    void everyDroneGetsAStreamOnThePortOfItsLetter() {
        stanag.targetOf = null; // the real addresses (nobody listens: the packets go nowhere)
        config.apply(Map.of(StanagDialog.TARGET_KEY, "127.0.0.1:15000"));
        cards("MobileAlpha", "ACTIVE", "MobileCharlie", "STANDBY", "MobileZulu", "ASLEEP");
        stanag.setOn(true);
        Map<String, String> to = new HashMap<>();
        for (StanagController.Row r : stanag.rows()) to.put(r.name(), r.target());
        assertEquals(Map.of("MobileAlpha", "127.0.0.1:15000", "MobileCharlie", "127.0.0.1:15002", "MobileZulu", "127.0.0.1:15025"), to);
        assertTrue(window.log.stream().anyMatch(l -> l.equals("STANAG 4609 streams on: 3 to 127.0.0.1, from port 15000.")), window.log.toString());
    }

    @Test
    void aRenamedPhoneKeepsThePortItWasGivenAndAllMoveWithTheFirstPort() {
        assertEquals(0, StanagController.slot("MobileAlpha", k -> ""));
        assertEquals(7, StanagController.slot("MobileHotel", k -> "40"), "a name with a letter: its letter, whatever is kept");
        assertEquals(-1, StanagController.slot("Hawk", k -> ""));
        assertEquals(31, StanagController.slot("Hawk", k -> "31"));
        assertEquals(26, StanagController.giveSlot(List.of()));
        assertEquals(28, StanagController.giveSlot(List.of(26, 27)));

        stanag.targetOf = null;
        config.apply(Map.of(StanagDialog.TARGET_KEY, "127.0.0.1:15000"));
        cards("Hawk", "ACTIVE", "Eagle", "ACTIVE");
        stanag.setOn(true);
        assertEquals("26", config.getString(StanagController.SLOT_KEY + "Hawk"));
        assertEquals("27", config.getString(StanagController.SLOT_KEY + "Eagle"));
        // another start, the phones heard the other way round: the same ports
        stanag.setOn(false);
        cards("Eagle", "ACTIVE", "Hawk", "ACTIVE");
        config.apply(Map.of(StanagDialog.TARGET_KEY, "127.0.0.1:16000"));
        stanag.setOn(true);
        Map<String, String> to = new HashMap<>();
        for (StanagController.Row r : stanag.rows()) to.put(r.name(), r.target());
        assertEquals(Map.of("Hawk", "127.0.0.1:16026", "Eagle", "127.0.0.1:16027"), to);
    }

    /** The pilot: "make it possible for the pilot to change the ip and port". */
    @Test
    void aDroneGoesToAnAddressOfItsOwnWhereThePilotGaveItOne() {
        stanag.targetOf = null;
        config.apply(Map.of(StanagDialog.TARGET_KEY, "127.0.0.1:15000", StanagController.TO_KEY + "MobileBravo", "127.0.0.1:17777",
                StanagController.TO_KEY + "MobileCharlie", "not an address"));
        cards("MobileAlpha", "ACTIVE", "MobileBravo", "STANDBY", "MobileCharlie", "ASLEEP");
        // before they are on: every drone with the address it will have
        Map<String, StanagController.Row> off = new HashMap<>();
        for (StanagController.Row r : stanag.rows()) off.put(r.name(), r);
        assertEquals("127.0.0.1:15000", off.get("MobileAlpha").target());
        assertEquals("127.0.0.1:17777", off.get("MobileBravo").target());
        assertTrue(off.get("MobileBravo").own());
        assertEquals("OFF", off.get("MobileBravo").showing());
        assertEquals("127.0.0.1:15002", off.get("MobileCharlie").target(), "what is no address is not taken: the automatic one");
        assertEquals(2, off.get("MobileCharlie").slot());
        stanag.setOn(true);
        Map<String, StanagController.Row> on = new HashMap<>();
        for (StanagController.Row r : stanag.rows()) on.put(r.name(), r);
        assertEquals("127.0.0.1:17777", on.get("MobileBravo").target());
        assertTrue(on.get("MobileBravo").own());
        assertFalse(on.get("MobileAlpha").own());
        assertEquals("udp://127.0.0.1:17777?pkt_size=1316", stanag.feed("MobileBravo").url());
        assertTrue(window.log.contains("STANAG 4609 streams on: 3 to 127.0.0.1, from port 15000 (1 to an address of its own)."), window.log.toString());
        // the settings saved: the streams anew at their addresses, and said so
        config.apply(Map.of(StanagController.TO_KEY + "MobileBravo", "", StanagController.TO_KEY + "MobileAlpha", "udp://239.1.2.3:5004"));
        stanag.restart();
        assertEquals("udp://239.1.2.3:5004?pkt_size=1316&ttl=8", stanag.feed("MobileAlpha").url());
        assertEquals("udp://127.0.0.1:15001?pkt_size=1316", stanag.feed("MobileBravo").url());
        assertEquals(2, window.log.stream().filter(l -> l.startsWith("STANAG 4609 streams on: 3 ")).count(), window.log.toString());
    }

    @Test
    void theSettingsBoxesBecomeOwnAddressesOnlyWhereThePilotTypedOne() {
        StanagController.Row alpha = new StanagController.Row("MobileAlpha", "127.0.0.1:15000", "IDLE", 0, false),
                bravo = new StanagController.Row("MobileBravo", "127.0.0.1:17777", "STANDBY", 1, true),
                charlie = new StanagController.Row("MobileCharlie", "127.0.0.1:15002", "DEEP SLEEP", 2, false);
        String[] why = {null};
        java.util.function.Predicate<String> had = "MobileBravo"::equals;
        // nothing touched, SEND TO changed: the automatic ones follow it, the typed one stays
        assertEquals(Map.of(StanagController.TO_KEY + "MobileBravo", "127.0.0.1:17777"), StanagDialog.ownAddresses("10.0.0.5:20000",
                List.of(Map.entry(alpha, "127.0.0.1:15000"), Map.entry(bravo, "127.0.0.1:17777"), Map.entry(charlie, "127.0.0.1:15002")), had, why));
        // one typed over, one emptied (back to automatic), one typed as a URL
        assertEquals(Map.of(StanagController.TO_KEY + "MobileAlpha", "239.1.2.3:5004", StanagController.TO_KEY + "MobileBravo", "",
                        StanagController.TO_KEY + "MobileCharlie", "239.1.2.3:5006"),
                StanagDialog.ownAddresses("127.0.0.1:15000", List.of(Map.entry(alpha, " 239.1.2.3:5004 "), Map.entry(bravo, ""),
                        Map.entry(charlie, "udp://239.1.2.3:5006")), had, why));
        // the automatic address typed by hand is no address of its own
        assertEquals(Map.of(StanagController.TO_KEY + "MobileBravo", ""), StanagDialog.ownAddresses("127.0.0.1:15000",
                List.of(Map.entry(alpha, "127.0.0.1:15000"), Map.entry(bravo, "127.0.0.1:15001")), had, why));
        // what is no address is said, and nothing is saved
        assertNull(StanagDialog.ownAddresses("127.0.0.1:15000", List.of(Map.entry(alpha, "239.1.2.3")), had, why));
        assertTrue(why[0].startsWith("MobileAlpha: host:port"), why[0]);
        // two drones on one address: said
        assertNull(StanagDialog.ownAddresses("127.0.0.1:15000", List.of(Map.entry(alpha, "127.0.0.1:15001"), Map.entry(charlie, "127.0.0.1:15002"),
                Map.entry(bravo, "127.0.0.1:15002")), had, why));
        assertEquals("MobileCharlie and MobileBravo have the same address (127.0.0.1:15002).", why[0]);
    }

    @Test
    void aNewPhoneGetsAStreamAForgottenOneLosesItsAndOffClosesAll() {
        cards("MobileAlpha", "ACTIVE");
        stanag.setOn(true);
        assertNotNull(stanag.feed("MobileAlpha"));
        cards("MobileAlpha", "ACTIVE", "MobileBravo", "STANDBY");
        stanag.refresh();
        assertNotNull(stanag.feed("MobileBravo"));
        cards("MobileBravo", "STANDBY");
        stanag.refresh();
        assertNull(stanag.feed("MobileAlpha"), "forgotten in the name map");
        stanag.setOn(false);
        assertNull(stanag.feed("MobileBravo"));
        stanag.refresh();
        assertNull(stanag.feed("MobileBravo"), "off stays off");
        assertEquals("OFF", stanag.rows().get(0).showing(), "the settings still list the drones, as off");
    }

    // ------------------------------------------------------------------ the video only where this PC has it

    @Test
    void thePilotedAndTheObservedGetTheirVideoTheRestTheirPage() throws Exception {
        cards("MobileAlpha", "CONNECTED", "MobileBravo", "ACTIVE", "MobileCharlie", "STANDBY", "MobileDelta", "ASLEEP");
        VideoPipeline pilot = pipeline(), tile = pipeline();
        window.live = "MobileAlpha";
        window.liveVideo = pilot;
        window.tiles.add(new StanagController.Tile("MobileBravo", tile, () -> null, ms -> null));
        stanag.setOn(true);
        assertNotNull(stanag.feed("MobileAlpha").live());
        assertFalse(stanag.feed("MobileAlpha").live().tile());
        assertTrue(stanag.feed("MobileBravo").live().tile(), "an OBSERVATION tile's video");
        assertNull(stanag.feed("MobileCharlie").live());
        play(pilot, 20);
        play(tile, 20);
        await(() -> stanag.feed("MobileAlpha").showing() == StanagShow.Kind.VIDEO, 3000, "the piloted drone's video");
        await(() -> stanag.feed("MobileBravo").showing() == StanagShow.Kind.VIDEO, 3000, "the observed drone's video");
        await(() -> stanag.feed("MobileCharlie").sent(StanagShow.Kind.PAGE) > 0 && stanag.feed("MobileDelta").sent(StanagShow.Kind.PAGE) > 0, 3000, "pages");
        assertEquals("VIDEO", showing("MobileAlpha"));
        assertEquals("STANDBY", showing("MobileCharlie"));
        assertEquals("DEEP SLEEP", showing("MobileDelta"));
        assertEquals(0, stanag.feed("MobileCharlie").sent(StanagShow.Kind.VIDEO));

        // DISCONNECT and STOP OBSERVING: their pages again, at once
        window.live = null;
        window.liveVideo = null;
        window.tiles.clear();
        cards("MobileAlpha", "STANDBY", "MobileBravo", "ACTIVE", "MobileCharlie", "STANDBY", "MobileDelta", "ASLEEP");
        stanag.refresh();
        assertNull(stanag.feed("MobileAlpha").live());
        assertNull(stanag.feed("MobileBravo").live());
        await(() -> stanag.feed("MobileAlpha").showing() == StanagShow.Kind.PAGE, 3000, "its page after DISCONNECT");
        await(() -> stanag.feed("MobileBravo").showing() == StanagShow.Kind.PAGE, 3000, "its page after STOP OBSERVING");
        assertEquals("STANDBY", showing("MobileAlpha"));
        assertEquals("IDLE", showing("MobileBravo"));
    }

    @Test
    void aSpectatorsStreamFollowsThePhoneItsPilotFlies() throws Exception {
        cards("MobileAlpha", "ACTIVE", "MobileBravo", "ACTIVE");
        VideoPipeline watched = pipeline();
        window.spectating = true;
        window.live = "MobileAlpha";
        window.liveVideo = watched;
        stanag.setOn(true);
        assertNotNull(stanag.feed("MobileAlpha").live());
        window.live = "MobileBravo"; // the pilot now flies the other phone: the same video, another drone's
        stanag.refresh();
        assertNull(stanag.feed("MobileAlpha").live());
        assertNotNull(stanag.feed("MobileBravo").live());
        play(watched, 20);
        await(() -> stanag.feed("MobileBravo").showing() == StanagShow.Kind.VIDEO, 3000, "the video on the new drone's stream");
        assertEquals(0, stanag.feed("MobileAlpha").sent(StanagShow.Kind.VIDEO), "none of it on the old one's");
    }

    @Test
    void aDroneFlownFromAnotherPcShowsThatAndASleeperItsMode() {
        cards("MobileAlpha", "ACTIVE", "MobileBravo", "OVERWATCH");
        window.flownBy.put("MobileAlpha", "PCBravo");
        stanag.setOn(true);
        assertEquals("PILOTED", showing("MobileAlpha"));
        assertEquals("OVERWATCH", showing("MobileBravo"));
        assertEquals(List.of("MobileBravo"), window.placed, "an overwatching drone's sector is asked for, no other's");
    }

    // ------------------------------------------------------------------ an alert: the live picture, then the clip

    @Test
    void anAlertsPictureThenItsClipThenThePage() throws Exception {
        long event = 1_800_000_000_000L;
        cards("MobileGolf", "OVERWATCH");
        stanag.setOn(true);
        BufferedImage picture = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        window.views.add(view("MobileGolf", event, AlertCard.Shown.CARD, false, picture, null));
        stanag.refresh();
        await(() -> stanag.feed("MobileGolf").showing() == StanagShow.Kind.ALERT, 3000, "the alert's picture");
        assertEquals("ALERT", showing("MobileGolf"));
        // a newer picture between the refreshes
        BufferedImage newer = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        window.views.set(0, view("MobileGolf", event, AlertCard.Shown.CARD, false, newer, null));
        long sent = stanag.feed("MobileGolf").sent(StanagShow.Kind.ALERT);
        stanag.alertPictures();
        await(() -> stanag.feed("MobileGolf").sent(StanagShow.Kind.ALERT) > sent, 2000, "the newer picture");

        // the motion ended, its clip is on its way: the page meanwhile
        window.views.set(0, view("MobileGolf", event, AlertCard.Shown.CARD, true, newer, "Clip coming: 1 MB of 3 MB"));
        stanag.refresh();
        await(() -> stanag.feed("MobileGolf").showing() == StanagShow.Kind.PAGE, 3000, "the page while the clip comes");
        assertEquals("OVERWATCH", showing("MobileGolf"));
        // here and whole: it plays, once
        fileClip("MobileGolf", event, 30);
        window.views.set(0, view("MobileGolf", event, AlertCard.Shown.CARD, true, newer, null));
        stanag.refresh();
        await(() -> stanag.feed("MobileGolf").showing() == StanagShow.Kind.CLIP, 3000, "the clip");
        await(() -> stanag.feed("MobileGolf").showing() == StanagShow.Kind.PAGE, 6000, "the page after the clip");
        long played = stanag.feed("MobileGolf").sent(StanagShow.Kind.CLIP);
        stanag.refresh();
        Thread.sleep(500);
        assertEquals(played, stanag.feed("MobileGolf").sent(StanagShow.Kind.CLIP), "once");
    }

    @Test
    void anAlertWithoutAPictureIsThePageThatSaysSo() {
        cards("MobileGolf", "OVERWATCH");
        window.views.add(view("MobileGolf", 1_800_000_000_000L, AlertCard.Shown.CARD, false, null, null)); // held back: a drone here may be flying
        stanag.setOn(true);
        assertEquals("OVERWATCH - ALERT", showing("MobileGolf"));
    }

    @Test
    void aNewDetectionWhileTheClipPlaysShowsTheNewAlert() throws Exception {
        long first = 1_800_000_000_000L, second = first + 120_000;
        cards("MobileGolf", "OVERWATCH");
        fileClip("MobileGolf", first, 150); // five seconds
        alertThenEnded("MobileGolf", first, null);
        await(() -> stanag.feed("MobileGolf").showing() == StanagShow.Kind.CLIP, 4000, "the clip plays");
        BufferedImage picture = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        window.views.set(0, view("MobileGolf", second, AlertCard.Shown.CARD, false, picture, null));
        stanag.refresh();
        await(() -> stanag.feed("MobileGolf").showing() == StanagShow.Kind.ALERT, 2000, "the new alert's picture, at once");
        long clip = stanag.feed("MobileGolf").sent(StanagShow.Kind.CLIP);
        // that motion ends too, its clip not here yet: the page - the first clip is not taken up again
        window.views.set(0, view("MobileGolf", second, AlertCard.Shown.CARD, true, picture, null));
        stanag.refresh();
        await(() -> stanag.feed("MobileGolf").showing() == StanagShow.Kind.PAGE, 3000, "the page");
        Thread.sleep(400);
        assertEquals(clip, stanag.feed("MobileGolf").sent(StanagShow.Kind.CLIP));
    }

    @Test
    void aChangeOfModeWhileTheClipPlaysShowsTheNewModesPage() throws Exception {
        long event = 1_800_000_000_000L;
        cards("MobileGolf", "OVERWATCH");
        fileClip("MobileGolf", event, 150);
        alertThenEnded("MobileGolf", event, null);
        await(() -> stanag.feed("MobileGolf").showing() == StanagShow.Kind.CLIP, 4000, "the clip plays");
        cards("MobileGolf", "STANDBY"); // STANDBY from FLEET
        stanag.refresh();
        await(() -> stanag.feed("MobileGolf").showing() == StanagShow.Kind.PAGE, 2000, "its page");
        assertEquals("STANDBY", showing("MobileGolf"));
        cards("MobileGolf", "OVERWATCH"); // and back to overwatch: the old clip stays unplayed
        stanag.refresh();
        Thread.sleep(500);
        assertEquals(StanagShow.Kind.PAGE, stanag.feed("MobileGolf").showing());
    }

    @Test
    void pilotUavWhileTheClipPlaysShowsTheVideo() throws Exception {
        long event = 1_800_000_000_000L;
        cards("MobileGolf", "OVERWATCH");
        fileClip("MobileGolf", event, 150);
        alertThenEnded("MobileGolf", event, null);
        await(() -> stanag.feed("MobileGolf").showing() == StanagShow.Kind.CLIP, 4000, "the clip plays");
        VideoPipeline pilot = pipeline();
        window.live = "MobileGolf";
        window.liveVideo = pilot;
        cards("MobileGolf", "CONNECTED");
        stanag.refresh();
        long clip = stanag.feed("MobileGolf").sent(StanagShow.Kind.CLIP);
        play(pilot, 20);
        await(() -> stanag.feed("MobileGolf").showing() == StanagShow.Kind.VIDEO, 3000, "its video");
        assertTrue(stanag.feed("MobileGolf").sent(StanagShow.Kind.CLIP) <= clip + 1, "the clip stopped");
    }

    @Test
    void aDismissedAlertsClipAndAClipFromBeforeThisStartAreNotPlayed() throws Exception {
        long event = 1_800_000_000_000L;
        cards("MobileGolf", "OVERWATCH", "MobileHotel", "OVERWATCH");
        fileClip("MobileGolf", event, 30);
        fileClip("MobileHotel", event, 30); // on the disk, its alert never seen this run
        window.views.add(view("MobileGolf", event, AlertCard.Shown.CARD, false, null, null));
        stanag.setOn(true);
        window.views.set(0, view("MobileGolf", event, AlertCard.Shown.HIDDEN, true, null, null)); // DISMISS, then it ended
        stanag.refresh();
        Thread.sleep(700);
        assertEquals(0, stanag.feed("MobileGolf").sent(StanagShow.Kind.CLIP));
        assertEquals(0, stanag.feed("MobileHotel").sent(StanagShow.Kind.CLIP));
    }

    @Test
    void aClipWaitsUntilNothingMoreOfItIsComing() throws Exception {
        long event = 1_800_000_000_000L;
        stanag.clipQuietMs = 800;
        cards("MobileGolf", "OVERWATCH");
        fileClip("MobileGolf", event, 30);
        alertThenEnded("MobileGolf", event, "Clip coming: 2 MB of 3 MB"); // part 2 on its way
        Thread.sleep(400);
        stanag.refresh();
        assertEquals(0, stanag.feed("MobileGolf").sent(StanagShow.Kind.CLIP), "not while a part is still coming");
        window.views.set(0, view("MobileGolf", event, AlertCard.Shown.CARD, true, null, null));
        stanag.refresh();
        assertEquals(StanagShow.Kind.PAGE, stanag.feed("MobileGolf").showing(), "nor the moment it stops: the next part is announced within it");
        Thread.sleep(900);
        stanag.refresh();
        await(() -> stanag.feed("MobileGolf").showing() == StanagShow.Kind.CLIP, 3000, "then it plays");
    }

    // ------------------------------------------------------------------ several PCs: a warning

    @Test
    void anotherPcStreamingToTheSameAddressIsSaidOnceAndInTheSettings() {
        stanag.targetOf = null;
        config.apply(Map.of(StanagDialog.TARGET_KEY, "100.64.0.9:15000"));
        cards("MobileAlpha", "ACTIVE");
        stanag.setOn(true);
        assertEquals("100.64.0.9:15000", stanag.announced(), "what this PC tells the others");
        assertNull(stanag.warning());
        window.others.put("PCBravo", "100.64.0.9:15000");
        stanag.refresh();
        stanag.refresh();
        stanag.refresh();
        long said = window.log.stream().filter(l -> l.equals("PCBravo also streams STANAG to 100.64.0.9. Switch one off.")).count();
        assertEquals(1, said, window.log.toString());
        assertEquals("PCBravo also streams to 100.64.0.9. Switch one off.", stanag.warning());
        window.others.clear(); // it was switched off there
        stanag.refresh();
        assertNull(stanag.warning());
        window.others.put("PCBravo", "100.64.0.9:15000"); // and on again: said again
        stanag.refresh();
        assertEquals(2, window.log.stream().filter(l -> l.startsWith("PCBravo also streams")).count());
        stanag.setOn(false);
        assertNull(stanag.announced());
        assertNull(stanag.warning());
    }

    @Test
    void anotherAddressOrThisPcsOwnLoopbackIsNoClash() {
        assertEquals(List.of("PCBravo"), StanagController.clashing("100.64.0.9:15000", Map.of("PCBravo", "100.64.0.9:15010", "PCCharlie", "100.64.0.7:15000")));
        assertEquals(List.of(), StanagController.clashing("100.64.0.9:15000", Map.of("PCBravo", "100.64.0.9:16000")), "ports far apart: no drone's are the same");
        assertEquals(List.of(), StanagController.clashing("127.0.0.1:15000", Map.of("PCBravo", "127.0.0.1:15000")), "each PC's own loopback");
        assertEquals(List.of("PCBravo"), StanagController.clashing("239.1.2.3:15000", Map.of("PCBravo", "239.1.2.3:15000")), "one multicast group");
        // nothing said of a stream that stays on this PC
        config.apply(Map.of(StanagDialog.TARGET_KEY, "127.0.0.1:15000"));
        stanag.targetOf = null;
        cards("MobileAlpha", "ACTIVE");
        stanag.setOn(true);
        assertNull(stanag.announced());
    }

    @Test
    void anAddressFromBeforeThatIsNoHostAndPortFallsBackToThisPc() {
        config.apply(Map.of(StanagDialog.TARGET_KEY, "rtp://10.0.0.1:5004")); // 1.0.39 - 1.0.64 took a full URL for the one stream
        assertEquals(StanagDialog.DEFAULT_TARGET, StanagDialog.target(config));
        // a UDP address written as a URL is the address it names: the pilot's own multicast group goes on working
        config.apply(Map.of(StanagDialog.TARGET_KEY, "udp://238.255.255.255:5003"));
        assertEquals("238.255.255.255:5003", StanagDialog.target(config));
        assertEquals("239.1.2.3:5003", StanagDialog.plain(" UDP://@239.1.2.3:5003?ttl=4&pkt_size=1316 "));
        assertEquals("100.64.0.5:15000", StanagDialog.plain("100.64.0.5:15000"));
        assertTrue(StanagDialog.usable("100.64.0.5:15000"));
        assertFalse(StanagDialog.usable("100.64.0.5:65530"), "no room for a port per drone after it");
        assertFalse(StanagDialog.usable("100.64.0.5"));
        assertEquals(15000, StanagController.portOf("100.64.0.5:15000"));
        assertEquals("100.64.0.5", StanagController.hostOf("100.64.0.5:15000"));
    }
}
