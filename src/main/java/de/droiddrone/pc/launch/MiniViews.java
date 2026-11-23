package de.droiddrone.pc.launch;

import de.droiddrone.common.launch.LaunchSequence;
import de.droiddrone.common.launch.LaunchWire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fleet PRD AL-20 / AL-21: the launch mini-views in the pilot view's bottom-left corner, as data (the OSD draws them).
 * <ul>
 *   <li>A reserve's view appears only once it has woken and connected - never before; until then it is only in the
 *       reserve list (CALLED, with the time to its check-in).</li>
 *   <li>It shows the drone's name, its state and a height bar up to the follower floor, and leaves when the drone
 *       passes the floor.</li>
 *   <li>A launch that failed (or a check that stopped it: MOVED, TIPPED ...) stays with its word and the last picture
 *       for {@link #FAILED_MS} ms, then becomes the alert in FLEET ({@link #takeAlerts}).</li>
 *   <li>Several at once stack, no limit; oldest at the bottom.</li>
 * </ul>
 * The stream itself is the small one (about 480x270 at 15 fps) that never pushes the piloted drone's video down a
 * tier (AL-21) - the video side's rule, not this class's.
 */
public final class MiniViews {
    public static final long FAILED_MS = 10_000;
    /** The size the mini stream asks for (AL-21). */
    public static final int WIDTH = 480, HEIGHT = 270, FPS = 15;

    /** One view: picture live or frozen (failed), the words, the bar's fill 0..1. */
    public record View(String name, String word, double heightM, double floorM, boolean failed, boolean livePicture) {
        public double barFill() {
            return floorM <= 0 ? 0 : Math.max(0, Math.min(1, heightM / floorM));
        }
    }

    private record Failed(String word, double heightM, double floorM, long since) {}

    private final Map<String, Failed> failed = new LinkedHashMap<>();
    private final List<String> alerts = new ArrayList<>();

    /**
     * The views now, from the queue's calls (all of them, done ones too: a failure must be seen).
     * @param floorM the follower floor now
     */
    public List<View> views(long now, List<ReserveQueue.Call> calls, double floorM) {
        List<View> out = new ArrayList<>();
        for (ReserveQueue.Call c : calls) {
            LaunchWire.Status s = c.status();
            if (s == null) continue;
            boolean wasFailure = s.state == LaunchSequence.LAUNCH_FAILED || s.state == LaunchSequence.NOT_LAUNCHED;
            if (wasFailure) {
                if (!failed.containsKey(c.name) && s.connected) {
                    failed.put(c.name, new Failed(LaunchSequence.stateWord(s.state) + reason(s), s.heightM, floorM, now));
                }
                continue;
            }
            boolean live = s.connected && !s.passedFloor
                    && (s.state == LaunchSequence.CHECKING || s.state == LaunchSequence.BEEPING
                    || s.state == LaunchSequence.ARMING || s.state == LaunchSequence.CLIMBING);
            if (live) {
                out.add(new View(c.name, LaunchSequence.stateWord(s.state), s.heightM, floorM, false, true));
            }
        }
        failed.entrySet().removeIf(e -> {
            if (now - e.getValue().since() < FAILED_MS) return false;
            alerts.add(e.getKey() + ": " + e.getValue().word());
            return true;
        });
        for (Map.Entry<String, Failed> e : failed.entrySet()) {
            Failed f = e.getValue();
            out.add(new View(e.getKey(), f.word(), f.heightM(), f.floorM(), true, false));
        }
        return out;
    }

    private static String reason(LaunchWire.Status s) {
        String r = LaunchSequence.reasonWord(s.reason);
        return r.isEmpty() ? "" : " - " + r;
    }

    /** Views that ran out: FLEET's alerts now (each given once). */
    public List<String> takeAlerts() {
        List<String> a = List.copyOf(alerts);
        alerts.clear();
        return a;
    }
}
