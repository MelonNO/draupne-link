package de.droiddrone.pc.gui;

import de.droiddrone.common.DetectionAreas;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.map.RadioHorizon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MARK on the video, off screen: a 1000 x 450 view with an 800 x 450 picture in its middle (black bars of 100 beside
 * it), over a drone 100 m up at (60, 10) with its camera 45 degrees down.
 */
class VideoMarkTest {
    private static final RadioHorizon.Terrain FLAT = (lat, lon) -> 0;
    private static final int DARK = 0x202020;

    /** The window's part, made up: what a click is cast from, where the pins go, what was said. */
    private static final class Host implements VideoMark.Host {
        Pins pins = new Pins(null);
        /** The camera: 100 m up, 45 degrees down; the cost test's looks far out over rolling ground instead. */
        CameraFootprint.Pose pose = new CameraFootprint.Pose(60, 10, 100, 0, 0, 0, -45, 90, 90);
        RadioHorizon.Terrain ground = FLAT;
        final List<double[]> looks = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        final List<Boolean> armed = new ArrayList<>();
        String off;
        boolean position = true, terrain = true, mirrored;
        Coordinates.Format format = Coordinates.Format.MGRS;

        public String off() {
            return off;
        }

        public PinDrop.Sight look(double u, double v) {
            looks.add(new double[]{u, v});
            if (!position) return new PinDrop.Sight(false, false, null, null, false, 0, 0, 0, Long.MAX_VALUE);
            return new PinDrop.Sight(true, true, pose, CameraFootprint.ray(pose, u, v, terrain ? ground : null, 0), false, 2, 10, 0, 200);
        }

        public Pins pins() {
            return pins;
        }

        public Coordinates.Format format() {
            return format;
        }

        public boolean onMap(Pins.Pin pin) {
            return !mirrored;
        }

        public void armed(boolean on) {
            armed.add(on);
        }

        public void log(String line) {
            log.add(line);
        }
    }

    private final Host host = new Host();
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final VideoView view = view();
    private final VideoMark mark = new VideoMark(view, host, clock::get);
    private long when = 5_000;

    {
        view.setMarkLayer(mark); // as the window does
    }

    /** A view with a picture on it, painted once so the picture has its place. */
    private static VideoView view() {
        VideoView v = new VideoView();
        v.setSize(1000, 450);
        v.videoStarted(1600, 900, false);
        int[] px = v.beginFrame(800, 450);
        java.util.Arrays.fill(px, DARK);
        v.presentFrame();
        paint(v);
        return v;
    }

    private static BufferedImage paint(VideoView v) {
        BufferedImage screen = new BufferedImage(v.getWidth(), v.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = screen.createGraphics();
        v.paintComponent(g);
        g.dispose();
        return screen;
    }

    /** One click of the left button, as the view's listeners get it: pressed, released, clicked. */
    private void click(int x, int y, int count) {
        for (int id : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
            view.dispatchEvent(new MouseEvent(view, id, when, id == MouseEvent.MOUSE_PRESSED ? InputEvent.BUTTON1_DOWN_MASK : 0,
                    x, y, count, false, MouseEvent.BUTTON1));
        }
        when += 120;
    }

    private static int brightPixels(BufferedImage img, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = img.getRGB(x, y);
                if (((c >> 16) & 255) > 120 && ((c >> 8) & 255) > 120) n++; // white or the warning yellow, edges too
            }
        }
        return n;
    }

    @Test
    void armedIsACrossCursorAndTheButtonAndEndsWithoutAPin() {
        assertEquals(new java.awt.Rectangle(100, 0, 800, 450), view.pictureRect());
        assertFalse(mark.isArmed());
        mark.toggle();
        assertTrue(mark.isArmed());
        assertEquals(Cursor.CROSSHAIR_CURSOR, view.getCursor().getType());
        assertEquals(List.of(true), host.armed, "the button is told");
        mark.disarm(); // Esc
        assertFalse(mark.isArmed());
        assertEquals(Cursor.DEFAULT_CURSOR, view.getCursor().getType());
        assertEquals(List.of(true, false), host.armed);
        mark.toggle();
        mark.toggle(); // the button or P again
        assertFalse(mark.isArmed());
        assertEquals(Cursor.DEFAULT_CURSOR, view.getCursor().getType());
        mark.disarm(); // Esc with nothing armed: nothing
        assertEquals(List.of(true, false, true, false), host.armed);
        assertTrue(host.pins.list().isEmpty());
        assertNull(mark.line());
    }

    @Test
    void aClickBesideThePictureDropsNothingAndStaysArmed() {
        mark.arm();
        int asked = host.looks.size();
        click(50, 200, 1); // the black bar left of the picture
        click(950, 440, 1);
        assertTrue(mark.isArmed());
        assertTrue(host.pins.list().isEmpty());
        assertEquals(asked, host.looks.size(), "no ray cast");
        assertNull(mark.line());
        assertEquals(Cursor.CROSSHAIR_CURSOR, view.getCursor().getType());
    }

    @Test
    void aClickOnThePictureDropsOnePinAndDisarms() {
        click(500, 225, 1);
        assertTrue(host.pins.list().isEmpty(), "not armed: a click is just a click");
        assertTrue(host.looks.isEmpty());
        mark.arm();
        click(700, 225, 1); // three quarters across, half way down
        List<Pins.Pin> pins = host.pins.list();
        assertEquals(1, pins.size());
        Pins.Pin pin = pins.get(0);
        assertEquals("Alpha", pin.name(), "named as the map names a new pin");
        assertFalse(pin.rough());
        assertTrue(pin.createdMs() > 0);
        assertEquals(100, (pin.lat() - 60) * 111_195, 1.5, "100 m ahead");
        assertEquals(70.7, (pin.lon() - 10) * 111_195 * Math.cos(Math.toRadians(60)), 1.5, "and 71 m to the right");
        assertFalse(mark.isArmed(), "one click, one pin");
        assertEquals(Cursor.DEFAULT_CURSOR, view.getCursor().getType());
        assertEquals(List.of(true, false), host.armed);
        assertEquals("PIN ALPHA " + Coordinates.format(Coordinates.Format.MGRS, pin.lat(), pin.lon()), mark.line());
        assertEquals(1, host.log.size());
        assertTrue(host.log.get(0).startsWith("Pin Alpha from the video: ") && host.log.get(0).endsWith("."), host.log.get(0));
        click(300, 300, 1);
        assertEquals(1, host.pins.list().size(), "the next click drops none");
    }

    @Test
    void onlyTheLeftButtonMarks() {
        mark.arm();
        view.dispatchEvent(new MouseEvent(view, MouseEvent.MOUSE_PRESSED, when, InputEvent.BUTTON3_DOWN_MASK, 500, 225, 1, true, MouseEvent.BUTTON3));
        assertTrue(mark.isArmed());
        assertTrue(host.pins.list().isEmpty());
    }

    @Test
    void aPinOnWeakGeometryIsRoughAndSaysSo() {
        host.terrain = false; // no terrain there: flat ground assumed
        mark.arm();
        click(500, 225, 1);
        Pins.Pin pin = host.pins.list().get(0);
        assertTrue(pin.rough());
        assertTrue(mark.line().startsWith("PIN ~ALPHA "), mark.line());
        assertTrue(host.log.get(0).contains("ROUGH (terrain unknown, flat ground assumed)"), host.log.get(0));
    }

    @Test
    void aRefusalIsALineAndNoPinAndStaysArmed() {
        host.position = false;
        mark.arm();
        click(500, 225, 1);
        assertTrue(host.pins.list().isEmpty());
        assertEquals("NO PIN - NO POSITION", mark.line());
        assertTrue(mark.isArmed(), "still armed: the next click may have one");
        host.position = true;
        click(500, 225, 1);
        assertEquals(1, host.pins.list().size());
        assertFalse(mark.isArmed());
    }

    @Test
    void aSpectatorWhoseMapShowsThePilotsPinsIsToldItsPinIsKept() {
        host.mirrored = true;
        mark.arm();
        click(500, 225, 1);
        assertEquals(1, host.pins.list().size());
        assertTrue(host.log.get(0).endsWith("Kept on this PC; the map shows the pilot PC's pins while watching."), host.log.get(0));
    }

    /** The video's own double-click (video only, leaving full screen) as the window binds it. */
    private int[] doubleClickCounter() {
        int[] toggled = {0};
        view.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || mark.claims(e)) return;
                toggled[0]++;
            }
        });
        return toggled;
    }

    @Test
    void anUnarmedDoubleClickIsStillTheVideosOwn() {
        int[] toggled = doubleClickCounter();
        click(500, 225, 1);
        click(500, 225, 2);
        assertEquals(1, toggled[0]);
        assertTrue(host.pins.list().isEmpty());
        mark.arm();
        mark.disarm();
        click(500, 225, 1);
        click(500, 225, 2);
        assertEquals(2, toggled[0], "armed and ended: as before");
    }

    @Test
    void aDoubleClickThatMarksDropsOnePinAndDoesNotToggleTheVideo() {
        int[] toggled = doubleClickCounter();
        mark.arm();
        click(500, 225, 1);
        click(500, 225, 2); // the same hand, 120 ms later
        assertEquals(1, host.pins.list().size());
        assertEquals(0, toggled[0], "the marking click's second half is not a double-click on the video");
        when += 2000;
        click(500, 225, 1);
        click(500, 225, 2);
        assertEquals(1, toggled[0], "a later one is");
        assertEquals(1, host.pins.list().size());
    }

    @Test
    void oneCrossCursorModeAtATime() {
        DetectionAreas[] areas = {DetectionAreas.NONE};
        DetectionAreaLayer areaLayer = new DetectionAreaLayer(view, () -> areas[0], a -> areas[0] = a);
        // the window's rule, as it gives it to MARK
        assertNull(VideoMark.off(false, false, false));
        assertEquals("NO MARK - END AREAS FIRST", VideoMark.off(false, false, true));
        assertEquals("NO MARK - OBSERVING", VideoMark.off(true, false, true));
        assertEquals("NO MARK - LATENCY TEST", VideoMark.off(false, true, false));

        areaLayer.setEditing(true);
        host.off = VideoMark.off(false, false, areaLayer.isEditing());
        assertFalse(mark.arm(), "the areas are being drawn: refused");
        assertFalse(mark.isArmed());
        assertEquals("NO MARK - END AREAS FIRST", mark.line());
        assertEquals(List.of("NO MARK - END AREAS FIRST."), host.log);
        assertEquals(Cursor.CROSSHAIR_CURSOR, view.getCursor().getType(), "the cross stays: it is the areas'");
        assertTrue(host.armed.isEmpty());

        areaLayer.setEditing(false);
        host.off = null;
        assertTrue(mark.arm());
        // the AREAS button: MARK ends, then the areas open
        mark.disarm();
        areaLayer.setEditing(true);
        assertFalse(mark.isArmed());
        assertEquals(Cursor.CROSSHAIR_CURSOR, view.getCursor().getType());
        areaLayer.setEditing(false);

        // and should the areas open any other way while MARK is armed: the click is theirs, no pin
        mark.arm();
        areaLayer.setEditing(true);
        host.off = VideoMark.off(false, false, true);
        click(500, 225, 1);
        assertTrue(host.pins.list().isEmpty());
        assertFalse(mark.isArmed());
    }

    @Test
    void anUpsideDownViewAsksTheSamePointOfThePictureAsShown() {
        mark.arm();
        host.looks.clear();
        click(700, 100, 1);
        double[] upright = host.looks.get(0);
        assertEquals(0.50125, upright[0], 1e-9);
        assertEquals(2 * 100.5 / 450 - 1, upright[1], 1e-9);
        Pins.Pin first = host.pins.list().get(0);

        view.setUpsideDown(true); // the camera mounted upside down: the view turns the picture upright
        paint(view);
        assertTrue(view.isUpsideDown() && view.pictureFlipX());
        mark.arm();
        host.looks.clear();
        click(700, 100, 1);
        assertArrayEquals(upright, host.looks.get(0), 1e-12, "what the screen shows there is the same place");
        Pins.Pin second = host.pins.list().get(1);
        assertEquals("Bravo", second.name());
        assertEquals(first.lat(), second.lat(), 1e-12);
        assertEquals(first.lon(), second.lon(), 1e-12);
    }

    @Test
    void thePinsLineAndMarkShowForThreeSecondsOnTheInjectedClock() {
        BufferedImage before = paint(view);
        assertEquals(0, brightPixels(before, 100, 0, 900, 450), "a dark picture, no OSD");
        mark.arm();
        assertEquals(0, brightPixels(paint(view), 100, 0, 900, 450), "armed: nothing drawn");
        click(300, 120, 1);
        BufferedImage shown = paint(view);
        int lineY = Math.round(450 * VideoMark.LINE_Y);
        int line = brightPixels(shown, 250, lineY - 22, 750, lineY + 6), sign = brightPixels(shown, 292, 110, 309, 131);
        assertTrue(line > 100, "the line, centred low on the picture: " + line);
        assertTrue(sign > 20, "the mark where the click was: " + sign);
        assertEquals(line + sign, brightPixels(shown, 0, 0, 1000, 450), "and nothing else - not on the bars either");

        clock.addAndGet(VideoMark.PIN_LINE_MS - 1);
        assertNotNull(mark.line());
        assertEquals(line, brightPixels(paint(view), 250, lineY - 22, 750, lineY + 6), "still there at 2.999 s");
        clock.addAndGet(1);
        assertNull(mark.line());
        assertEquals(0, brightPixels(paint(view), 100, 0, 900, 450), "gone at 3 s, line and mark");
    }

    /**
     * What a click costs the Swing thread (it also shows the video): the ray - here a long one, 2.8 km out over rolling
     * ground, some 480 steps - the decision, and the pin written to pins.txt beside 40 others. Measured 2026-11-14 on
     * the PC it was written on: 1.4 ms a click (2.4 the worst), nearly all of it the file - the ray and the decision
     * 0.06 ms; the budget is far above that, to catch a change that makes it many times dearer, not a slow runner.
     */
    @Test
    void aClickCostsTheSwingThreadLittle(@TempDir Path dir) {
        host.pins = new Pins(dir.resolve("pins.txt"));
        for (int i = 0; i < 40; i++) host.pins.add(null, 60 + i * 1e-4, 10, false);
        host.pose = new CameraFootprint.Pose(60, 10, 300, 40, -2, 3, -4, 70, 43);
        host.ground = (lat, lon) -> 20 * Math.sin(lat * 4000) * Math.cos(lon * 2500);
        int n = 30;
        long worst = 0, all = 0, rays = 0;
        for (int i = 0; i < n + 5; i++) {
            mark.arm();
            MouseEvent press = new MouseEvent(view, MouseEvent.MOUSE_PRESSED, when += 1000, InputEvent.BUTTON1_DOWN_MASK, 480 + i, 230, 1, false, MouseEvent.BUTTON1);
            long t0 = System.nanoTime();
            mark.mousePressed(press);
            long t = System.nanoTime() - t0;
            long r0 = System.nanoTime();
            PinDrop.decide(host.look(0, 0.02));
            long r = System.nanoTime() - r0;
            if (i < 5) continue; // warm-up
            all += t;
            rays += r;
            worst = Math.max(worst, t);
        }
        assertEquals(40 + n + 5, host.pins.list().size(), "every click a pin");
        assertTrue(host.pins.list().get(44).rough(), "2.8 km out: rough");
        System.out.printf("MARK, a click on the Swing thread: mean %.2f ms, worst %.2f ms; of it the ray and the decision %.3f ms%n",
                all / 1e6 / n, worst / 1e6, rays / 1e6 / n);
        assertTrue(all / 1e6 / n < 40, "a click: " + all / 1e6 / n + " ms");
    }

    @Test
    void aShortLineForAnyoneElseShowsForItsOwnTime() {
        mark.say("STILL SAVED", false, 2000);
        assertEquals("STILL SAVED", mark.line());
        int lineY = Math.round(450 * VideoMark.LINE_Y);
        int saved = brightPixels(paint(view), 350, lineY - 22, 650, lineY + 6);
        assertTrue(saved > 40, "the line: " + saved);
        clock.addAndGet(1999);
        assertEquals("STILL SAVED", mark.line());
        clock.addAndGet(1);
        assertNull(mark.line());
        assertEquals(0, brightPixels(paint(view), 100, 0, 900, 450));
        mark.say("STILL FAILED", true, 2000); // the warning colour: yellow, bright in red and green
        int failed = brightPixels(paint(view), 350, lineY - 22, 650, lineY + 6);
        assertTrue(failed > 40, "the line: " + failed);
    }
}
