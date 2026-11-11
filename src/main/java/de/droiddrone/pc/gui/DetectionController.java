package de.droiddrone.pc.gui;

import de.droiddrone.common.DetectionAreas;
import de.droiddrone.common.MotionRecord;
import de.droiddrone.pc.CameraView;
import de.droiddrone.pc.Connection;
import de.droiddrone.pc.DetectionMarks;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.MotionBoxes;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.FlightTrack;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Motion detection (1.0.46) out of MainWindow: each drone's own level in the settings, and the detections' marks on
 * the maps - a line along each one's direction from the camera, its estimated ground point.
 */
final class DetectionController {
    static final String DETECTION_KEY = "detection";
    static final String[] DETECTION_LEVELS = {"OFF", "LOW", "NORMAL", "HIGH"};
    /** 1.0.58: each drone's detection areas (DetectionAreas.toText), "detectionAreas.<phone>". */
    static final String AREAS_KEY = "detectionAreas";

    private final PcConfig config;
    private final CameraView cameraView;
    private final Predicate<Map<String, String>> save;
    private final Consumer<String> log;
    /** The motion detection's marks on the map: a line along each detection's direction, its estimated ground point. */
    private final DetectionMarks detectionMarks = new DetectionMarks();
    private long motionIdsSeen, motionSaidAt;

    /**
     * @param save writes settings (false when it couldn't)
     * @param log a line for the log
     */
    DetectionController(PcConfig config, CameraView cameraView, Predicate<Map<String, String>> save, Consumer<String> log) {
        this.config = config;
        this.cameraView = cameraView;
        this.save = save;
        this.log = log;
    }

    /** The phone's own motion detection setting (0 off .. 3 high); never set: NORMAL. */
    int levelFor(String phone) {
        String v = phone == null || phone.isEmpty() ? "" : config.getString(DETECTION_KEY + "." + phone);
        if (v.isEmpty()) v = config.getString(DETECTION_KEY);
        for (int i = 0; i < DETECTION_LEVELS.length; i++) if (DETECTION_LEVELS[i].equalsIgnoreCase(v.trim())) return i;
        return 2;
    }

    /**
     * VIDEO > Motion detection: saved for the chosen phone (the drone), told to it within a second.
     * @param c this PC's link, or null
     */
    void set(int level, Connection c) {
        String phone = config.getString(Names.PHONE_NAME_KEY);
        save.test(Map.of(phone.isEmpty() ? DETECTION_KEY : DETECTION_KEY + "." + phone, DETECTION_LEVELS[level].toLowerCase(Locale.ROOT)));
        if (c != null && !c.getLink().isSpectator()) {
            c.getLink().setDetection(level);
            if (!c.getLink().detectionSupported() && c.getLink().getTelemetry().getPhonePcBuild() >= 0) {
                log.accept("Motion detection needs phone app 2.3.0-pc.35: update it.");
                return;
            }
        }
        log.accept(level == 0 ? "Motion detection off." : "Motion detection " + DETECTION_LEVELS[level] + " (while disarmed).");
    }

    /** The drone's detection areas (1.0.58), in its camera's own picture; none set: none. */
    DetectionAreas areasFor(String phone) {
        String v = phone == null || phone.isEmpty() ? config.getString(AREAS_KEY) : config.getString(AREAS_KEY + "." + phone);
        return DetectionAreas.parse(v);
    }

    /**
     * VIDEO > Detection areas: saved for the chosen phone (the drone), told to it within a second.
     * @param c this PC's link, or null
     */
    void setAreas(DetectionAreas areas, Connection c) {
        String phone = config.getString(Names.PHONE_NAME_KEY);
        save.test(Map.of(phone.isEmpty() ? AREAS_KEY : AREAS_KEY + "." + phone, areas.toText()));
        if (c != null && !c.getLink().isSpectator()) c.getLink().setDetectionAreas(areas);
    }

    private boolean noPictureSaid;
    private long noPictureSince = -1;
    /**
     * 1.0.63: a change of video size rebuilds the camera's session - the phone says "no picture" for a moment. Said
     * only when it lasts this long (it was said at every SHARP step, with "update the phone app").
     */
    static final long NO_PICTURE_SAY_MS = 3000;

    /**
     * pc.43: the phone says its camera gives the detection no picture (up to pc.42 every 16:9 video on a phone with no
     * small 16:9 size): said once, again only after a picture came. Unseen before - detection just never found anything.
     */
    void sayNoPicture(MotionRecord.Report r) {
        sayNoPicture(r, MonoClock.ms());
    }

    void sayNoPicture(MotionRecord.Report r, long now) {
        if (r == null || !r.active()) return;
        boolean none = (r.flags & MotionRecord.FLAG_NO_PICTURE) != 0;
        if (!none) {
            noPictureSince = -1;
            noPictureSaid = false;
            return;
        }
        if (noPictureSince < 0) noPictureSince = now;
        if (!noPictureSaid && now - noPictureSince >= NO_PICTURE_SAY_MS) {
            noPictureSaid = true;
            log.accept("Motion detection: no picture from this camera. Update the phone app.");
        }
    }

    /** The marks as they stand (no link: no new ones), fading as they age. */
    List<DetectionMarks.Mark> marks(long now) {
        return detectionMarks.marks(now);
    }

    /**
     * The boxes of the phone's latest DETECTION record onto the map: each one's ray from the camera's pose now (the
     * picture turned as the screen shows it), its mark following it. A new thing seen goes to the log (once in 10 s).
     */
    List<DetectionMarks.Mark> marks(FcTelemetry t, FlightTrack track, boolean flipX, boolean flipY, long now) {
        MotionBoxes motion = t.getMotion();
        CameraFootprint.Pose pose = null;
        for (MotionBoxes.Shown s : motion.shown(now)) {
            if (now - s.seenAt() > MotionBoxes.SOLID_MS) continue; // only what the latest record had
            if (pose == null) pose = cameraView.sittingPose(t, track);
            if (pose == null) break; // no position or heading: no line to draw
            double[] uv = DetectionMarks.pictureUv(s.box(), flipX, flipY);
            detectionMarks.put(s.box().id, s.box().light(), pose.lat(), pose.lon(), cameraView.ray(t, track, uv[0], uv[1]), now);
        }
        sayNoPicture(motion.last());
        long ids = motion.newIds();
        if (ids > motionIdsSeen) {
            if (now - motionSaidAt > 10_000) {
                log.accept("MOTION: " + (ids - motionIdsSeen) + " new - boxed on the video and map.");
                motionSaidAt = now;
            }
            motionIdsSeen = ids;
        }
        return detectionMarks.marks(now);
    }
}
