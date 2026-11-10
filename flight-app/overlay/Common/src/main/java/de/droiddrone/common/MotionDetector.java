/*
 *  This file is part of DRAUPNE Link's build of DRAUPNE Flight.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Motion detection while the drone sits disarmed (DRAUPNE Link 1.0.46, phone app pc.35): small pictures from the
 * camera (a few a second) in, boxes round what travels through the picture out - anything that moves, but not leaves
 * and branches in the wind. Pure Java with no Android in it: the phone runs it (MotionWatch), and the PC's build
 * compiles this folder too, so its tests run it on made-up pictures (MotionDetectorTest).
 *
 * <p>The pipeline, per picture (the luma averaged down to at most {@link #WORK_MAX_WIDTH} wide):
 * <ol>
 * <li>Wind shake: the whole picture's shift against the background is found (a search on a quarter-size copy, refined
 * to a fraction of a pixel, both compared by their fine detail so a change of light doesn't move it) and taken out.
 * A picture where more than {@link #GLOBAL_CHANGE_FRACTION} changes at once (a shove, a cloud's shadow) is not judged:
 * the background learns it fast, the tracks start again after it, and after {@link #RELEARN_AFTER_S} the background
 * starts again from the picture.</li>
 * <li>Exposure: the picture's brightness is matched to the background's; a step (the camera's auto exposure) moves
 * the background to the new level at once, and that picture is not judged. The exposure is not locked on the phone:
 * the video in the air must not inherit a lock.</li>
 * <li>Background: per pixel a running mean and variance. A pixel is foreground when it is further from the mean of
 * every pixel round it (3 x 3) than k standard deviations plus a margin - a picture a fraction of a pixel off still
 * matches at edges. The variance of a pixel that keeps changing grows (6 s) and falls back slowly (20 s), so leaves
 * that sway build a wide range and stop showing; something passing once leaves little. Where the picture follows the
 * background's texture, only darker or brighter (correlation over {@link #LIGHT_CHANGE_CORRELATION}), it is the same
 * ground in other light, not foreground.</li>
 * <li>Blobs: foreground pixels with fewer than two foreground neighbours are dropped (sensor noise), the rest grown
 * by a pixel and joined into blobs.</li>
 * <li>Tracks: blobs are matched to tracks (nearest to where each was heading). A track counts only when it TRAVELS:
 * at least {@link #MIN_AGE_S} seconds old (longer than half a slow branch's swing), seen in most pictures since,
 * its net way over the last 2 s at least {@link #MIN_TRAVEL} (and half its own size), nearly straight, both halves
 * of it the same way, not back towards where it started, and not leaving the ground changed behind it (the edge of a
 * shadow does; something passing uncovers what was there - a slower background remembers it). Vegetation sways round
 * a spot and fails this.</li>
 * <li>Vegetation or not: no trained model can be had offline here, so a score from plain statistics - how often the
 * pixels round the blob were foreground in the last ~20 s (leaves flicker in place; something passing does so once),
 * how much it turns back and forth or wanders, and how textured the background under it is next to the picture's
 * average. No colour (1.0.53): other cameras have none, and people and vehicles wear the surroundings' colours.
 * A track over {@link #MAX_VEGETATION} is never reported. For
 * {@link #YOUNG_MS} after the warm-up the flicker map is half known: a longer way and a lower score are asked.</li>
 * <li>Coming straight at the camera (1.0.53): someone walking head-on hardly moves in the picture - he grows - and the
 * background above learns a thing that stays in one place within seconds. {@link Looming} looks at the same aligned
 * pictures slowly: what stays changed against a slow reference and grows steadily where it stands is reported too.</li>
 * <li>Close to the camera (1.0.56, the pilot filming himself found nothing): someone a metre or two off crosses the
 * picture in a second, or stands in it and moves in place, and fills much of it - the travel rules take neither, the
 * camera's exposure follows them, and the change looked like the picture changing as a whole. Now: the exposure is the
 * most common factor over the pixels (not their sums) and the background follows it picture by picture; the camera's
 * shift is measured on the room, not on what moves; a big change packed in one piece with the room clean round it is
 * judged; and a big solid thing where the picture was calm, hiding the ground's detail (a shadow keeps it), is reported
 * within 0.6 s whichever way it moves ({@link #NEAR_AREA}). With the exposure now right, a shadow's edge shows as a local
 * change that travels: it is told by the ground behind it - darker or brighter, its detail still there.</li>
 * </ol>
 * Night: a dark picture (mean luma under {@link #NIGHT_ENTER}) keeps the detector on, but only for points brighter than
 * the background (headlights, torches) - a larger margin, and a blob must also stand {@link #NIGHT_PEAK_OVER_MEAN} over
 * the picture's mean. Sensor noise does not form tracks: it is scattered single pixels, gone by step 4, and never
 * travels.
 *
 * <p>HIGH (1.0.58, the pilot: the sentry by the camouflaged BTR and the infantry on patrol were never found - "real
 * life does not have a white background"): a man some 20 m off is two working pixels wide, and averaged down his
 * camouflage is the net's or the treeline's. On HIGH, with the camera steady, what changed is also judged on the fine
 * picture (the input at twice the working size): a working pixel with {@link #FINE_OF_FOUR} of its four fine ones
 * changed is changed. A pixel is also tested against its own background shifted by at most half a pixel (not only
 * against any neighbour's). The camera's shift is measured on calm pixels only (leaves swaying below the foreground
 * line pulled it a fifth of a pixel off, enough to hide him). The fine background's variance grows slowly from a one-off excursion (someone passing once:
 * the next one passing there is still seen), as before once it comes again within {@link #ONE_OFF_REPEAT_MS} (leaves).
 * A confirmed track that went far one way for {@link #LEG_MS} without coming back is proven (a sentry stops and turns at
 * the end of his beat), and a confirmed track's own pixels don't count as flicker. The price: leaves that a gust starts
 * swinging after a calm look like someone walking for a few seconds - areas ({@link #setAreas}) take them out.
 *
 * <p>Areas (1.0.58): boxes on the picture, each with its own sensitivity (OFF, LOW, NORMAL, HIGH; a later one over an
 * earlier one), the rest at the sensitivity set. What a pixel is tested by is its area's; a blob's and a track's rules
 * are those where it is; nothing is reported from an OFF area.
 *
 * <p>By brightness alone (1.0.53): the chroma passed in is not used - other cameras have none, and people and vehicles
 * wear the surroundings' colours.
 *
 * <p>Not thread safe: one thread feeds it. About 1.5 ms a 320 x 180 picture on a PC (MotionDetectorPerfTest), a third
 * of it the approach watch; no allocation per picture beyond the blobs and tracks found.
 */public final class MotionDetector {
    public static final int OFF = 0, LOW = 1, NORMAL = 2, HIGH = 3;
    /** The working picture is at most this wide: the input is averaged down by a whole factor. */
    public static final int WORK_MAX_WIDTH = 160;
    /** The most detections a result carries (the telemetry record's limit). */
    public static final int MAX_DETECTIONS = 8;

    // per sensitivity, indexed LOW..HIGH (index 0 unused)
    /** Standard deviations from the background's mean for a foreground pixel. */
    static final float[] K_SIGMA = {0, 3f, 2.5f, 2.2f};
    /** Grey levels on top of that (of 255), by day. */
    static final float[] MIN_DIFF_DAY = {0, 12, 8, 6};
    /** At night, brighter than the background by this much at least. */
    static final float[] MIN_DIFF_NIGHT = {0, 45, 35, 25};
    /** Fewest foreground pixels of a blob, in the working picture (160 wide). */
    static final int[] MIN_AREA = {0, 6, 4, 3};
    /** The least net way a track must travel in its last 2 s, working pixels. */
    static final float[] MIN_TRAVEL = {0, 9, 7, 5};
    /** Its net way over the way it went (1 = a straight line). */
    static final float[] MIN_STRAIGHT = {0, 0.7f, 0.6f, 0.5f};
    /** The youngest a track can count, seconds: longer than half a slow branch's swing (0.3 - 0.4 Hz) and its pause. */
    static final float[] MIN_AGE_S = {0, 2.5f, 2.0f, 1.6f};
    /** The vegetation score above which a track is never reported. */
    static final float[] MAX_VEGETATION = {0, 0.4f, 0.45f, 0.55f};

    /**
     * The vegetation score's parts (1.0.53: no colour any more - a grey camera has none, and a soldier in green
     * camouflage scored as leaves): how often the ground round it flickered, how it turns back and forth or wanders,
     * how textured the background under it is.
     */
    static final float VEG_FLICKER = 0.35f, VEG_WAY = 0.3f, VEG_TEXTURE = 0.05f;
    /** Mean luma under this: night (light mode); back to day over NIGHT_LEAVE. */
    static final float NIGHT_ENTER = 35, NIGHT_LEAVE = 45;
    /** A blob at night must peak this much over the picture's mean luma. */
    static final float NIGHT_PEAK_OVER_MEAN = 60;
    /** More of the picture foreground than this: the picture as a whole changed (shake, a shadow) - not judged. */
    static final float GLOBAL_CHANGE_FRACTION = 0.25f;
    /** Changed as a whole for this long: the background starts again from the picture. */
    static final float RELEARN_AFTER_S = 3;
    /** An exposure step bigger than this (the brightness ratio's distance from 1) moves the background at once. */
    static final float EXPOSURE_STEP = 0.08f;
    /** Learning time after the first picture (or a restart), when nothing is judged. */
    static final long WARM_UP_MS = 3000;
    /** A track's place this long ago is checked for what it left behind (leavesChange). */
    static final long WAKE_AFTER_MS = 800;
    /** More of that place than this still changed: a shadow's or a light's edge, not something passing. */
    static final float WAKE_CHANGED = 0.5f;
    /** ... by this many grey levels at least (or 3 standard deviations) from the slow background. */
    static final float WAKE_MIN_DIFF = 12;
    /** ... or the ground behind is this much darker or brighter (a factor's distance from 1), its detail correlating this well with what was there (1.0.56). */
    static final float WAKE_LIGHT_STEP = 0.15f, WAKE_LIGHT_DETAIL = 0.3f;
    /** For this long after the warm-up the background is young (the flicker map half known): a longer way is asked. */
    static final long YOUNG_MS = 20_000;
    /** A track must have been seen in this share of the pictures since it began. */
    static final float MIN_SEEN = 0.6f;
    /** A track not matched for this long ends. */
    static final long TRACK_LOST_MS = 1000;
    /** A confirmed track is reported while it was matched within this. */
    static final long REPORT_COAST_MS = 600;
    /** The window a track's travel is judged over. */
    static final long TRAVEL_WINDOW_MS = 2000;
    /** The coarse shift search: +-this in quarter-size pixels (+-16 working pixels). */
    static final int COARSE_RANGE = 4;
    /** Picture and background this well correlated (over a blob, or 3 x 3 round a pixel): the ground in other light. */
    static final double LIGHT_CHANGE_CORRELATION = 0.8;
    /** ... where both vary by at least this (standard deviation, grey levels; the picture's by half of it). */
    static final double LIGHT_CHANGE_MIN_SD = 3;
    /** Background texture (mean luma gradient) under this: no shift is estimated (a dark or flat picture). */
    static final float MIN_TEXTURE_FOR_SHIFT = 1.5f;
    /**
     * A confirmed track that has gone this many times its travel threshold from where it began is PROVEN (DRAUPNE
     * Link 1.0.52): a vehicle that turns round or drives a loop keeps its box - the straight-line rules that keep
     * swaying leaves out took it away at every turn. Leaves never get this far from their spot.
     */
    static final float PROVEN_TRAVEL = 3;
    /**
     * Other blobs this close (working pixels) to a confirmed track's last box are pieces of the same thing (a vehicle
     * whose middle matches the ground): the box takes them in instead of shrinking to one piece.
     */
    static final int JOIN_GAP = 3;
    /** The camera moved less than this (working pixels) against the background: steady, the own-pixel test runs too. */
    static final float STEADY_SHIFT = 0.35f;
    /** The own-pixel test's margin, times the 3 x 3 test's. */
    static final float OWN_MARGIN = 2.5f;
    /** Other light changes a 3 x 3 patch by one factor: its pixels' ratios to the background within this of each other. */
    static final float LIGHT_RATIO_SPREAD = 1.5f;
    /**
     * A track that clearly drives through the picture counts early (1.0.52: a car was found half way across): from this
     * age (s), when it went FAST_TRAVEL of the picture's width in a nearly straight line, seen in most pictures - whatever
     * the sensitivity: a big branch swings a tenth of the width one way before it comes back.
     */
    static final float[] FAST_AGE_S = {0, 1.0f, 0.9f, 0.8f};
    static final float FAST_TRAVEL = 0.15f;
    static final float FAST_STRAIGHT = 0.85f;
    static final float FAST_SEEN = 0.75f;
    /** The confidence a loomer is reported with (Looming: it grew steadily where it stands). */
    static final float LOOM_CONFIDENCE = 0.7f;
    /** A proven track not matched for this long ends (an unproven one after TRACK_LOST_MS): a vehicle turning head-on shrinks to nothing for a moment. */
    static final long PROVEN_LOST_MS = 3000;
    /**
     * Something close to the camera (DRAUPNE Link 1.0.56, the pilot: "filming myself and moving" found nothing): a big
     * solid thing where the picture had been calm. It crosses the picture in a second or two, or stays and moves in place
     * (an arm waving) - the travel rules, made for leaves 30 m off, take neither. At least this share of the picture,
     */
    static final float NEAR_AREA = 0.01f;
    /** ... its box at least this full, */
    static final float NEAR_FILL = 0.3f;
    /** ... seen in this share of the pictures since it began, for this long at least (s), */
    static final float NEAR_SEEN = 0.8f, NEAR_AGE_S = 0.6f;
    /**
     * ... where it began and round it the picture flickered at most this often (freq, scaled by how much of the flicker
     * map is learnt): leaves flicker in place for ever, a wall doesn't,
     */
    static final float NEAR_CALM = 0.04f;
    /** ... a ring this wide round its box (working pixels) for that, */
    static final int NEAR_RING = 4;
    /**
     * ... and not the same ground in other light: its brightness against the background's varies across it by at least
     * this much (a coefficient of variation; a shadow over flat ground darkens it by one factor).
     */
    static final float NEAR_RATIO_SPREAD = 0.2f;
    /**
     * ... or, where the ground has fine detail (NEAR_DETAIL_MIN_SD), that detail no longer shows through it: the
     * picture's and the background's correlate under this. A shadow, a patch of sun - however soft - keep it.
     */
    static final float NEAR_DETAIL_SHOWS = 0.2f, NEAR_DETAIL_MIN_SD = 1.5f;
    /** The confidence a close thing is reported with. */
    static final float NEAR_CONFIDENCE = 0.6f;
    /**
     * A picture where more than GLOBAL_CHANGE_FRACTION changed is still judged when the change is one thing in front of
     * the camera: in grid cells of this many working pixels, at least NEAR_IN_FULL of the change lies in cells it fills
     * (NEAR_FULL_CELL of their pixels), and at least NEAR_CLEAN_CELLS of the cells are clean (under NEAR_CLEAN_CELL) - the
     * room behind it. A shake's or a misjudged exposure's change spreads thin over every cell.
     */
    static final int NEAR_CELL = 10;
    static final float NEAR_FULL_CELL = 0.4f, NEAR_IN_FULL = 0.6f, NEAR_CLEAN_CELL = 0.05f, NEAR_CLEAN_CELLS = 0.08f;
    /** HIGH (1.0.58): a working pixel with this many of its four fine pixels changed is changed. */
    static final int FINE_OF_FOUR = 2;
    /** HIGH, the camera steady: the half-pixel own test's margin, times the day margin. */
    static final float HALF_MARGIN = 0.4f;
    /** HIGH: an excursion is a pixel this many standard deviations off its fine background. */
    static final float EXCURSION_K = 2;
    /** ... one within this of the one before, or lasting this long, grows the variance as before; a one-off this much as fast. */
    static final long ONE_OFF_REPEAT_MS = 3000, ONE_OFF_LASTS_MS = 1500;
    static final float ONE_OFF_GROWTH = 0.1f;
    /** HIGH: a confirmed track that went this many times its way from where it began, this long, never back, is proven. */
    static final float LEG_FAR = 2;
    static final long LEG_MS = 3000;

    /** A box round something that travels, in the input picture's own coordinates, 0..1. */
    public static final class Detection {
        public final int id;
        public final float x, y, w, h;
        public final float confidence, vegetation;
        /** Found in the dark: a light that travels. */
        public final boolean light;

        public Detection(int id, float x, float y, float w, float h, float confidence, float vegetation, boolean light) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.confidence = confidence;
            this.vegetation = vegetation;
            this.light = light;
        }

        public float centreX() {
            return x + w / 2;
        }

        public float centreY() {
            return y + h / 2;
        }
    }

    /** One picture's verdict: what travels (most confident first), night or day, and whether it was judged at all. */
    public static final class Result {
        public final List<Detection> detections;
        public final boolean night;
        /** The picture changed as a whole, the exposure stepped, or the background is still learning: not judged. */
        public final boolean settling;
        /** Tracks being followed, confirmed or not (for the log). */
        public final int tracks;

        Result(List<Detection> detections, boolean night, boolean settling, int tracks) {
            this.detections = detections;
            this.night = night;
            this.settling = settling;
            this.tracks = tracks;
        }
    }

    private static final class Blob {
        int count, minX, minY, maxX, maxY;
        float sumX, sumY, freq, texture, peak;
        /** Sums of picture (a) and background (m) values over the blob, for their correlation. */
        double sa, sm, saa, smm, sam;
        /** Sums of the picture's brightness over the background's ((a + 4) / (m + 4)), for its spread (1.0.56). */
        double sq, sqq;
        /** Pixels deep inside it (interior): the detail's sums are theirs - at the edge the band sees the ground round it. */
        int interior;
        /** Sums of the fine detail of picture (a) and background (m), for their correlation (1.0.56). */
        double da, dm, daa, dmm, dam;
        int label;
        Track track;

        float cx() {
            return sumX / count;
        }

        float cy() {
            return sumY / count;
        }
    }

    private static final class Track {
        final int id;
        final long first;
        /** The judged picture it began in (frames counts them). */
        final long firstFrame;
        long lastSeen;
        int hits;
        /** Positions: time ms, x, y, then the box (min x, min y, max x, max y; working pixels), oldest first, the last TRAVEL_WINDOW_MS + 1 s. */
        final ArrayList<float[]> history = new ArrayList<>();
        float ox, oy, maxFromOrigin;
        /** When it was furthest from where it began (1.0.58). */
        long farAt;
        int minX, minY, maxX, maxY;
        float flicker, textureRatio, vegetation, confidence;
        boolean confirmed, light, proven;
        /** 1.0.56: confirmed as a thing close to the camera (NEAR_AREA): kept while seen, unless it turns out leaves. */
        boolean near;
        /** The flicker where it began (the calm before it), and its blob's last area, fill and brightness spread. */
        float startFlicker = -1, area, fill, ratioSpread;
        /** How well the ground's fine detail still shows through it (correlation; NaN: the ground has none to show). */
        float detail = Float.NaN;
        /** It once came back towards where it began (leaves do, at every swing): the slow way never counts for it. */
        boolean cameBack;
        /** The last judgement's figures (describeTracks). */
        String why = "";

        Track(int id, long now, long frame, Blob b) {
            this.id = id;
            this.first = now;
            this.firstFrame = frame;
            ox = b.cx();
            oy = b.cy();
        }
    }

    private int sensitivity;
    private int inWidth, inHeight, factor, w, h;
    private float[] cur, aligned, mean, var, sd, freq;
    /** A slower background: what the ground looked like before the last few seconds (leavesChange). */
    private float[] slow;
    /** The most any pixel within 2 of each flickered (freq): leaves at the far end of a swing sit next to ones that flicker often. */
    private float[] near;
    /** changed: the picture differs from the background here, whatever the cause (raw: and it isn't other light). */
    private byte[] valid, changed, raw, clean, grown;
    /** 1.0.56: pixels not foreground in the last picture (the room), where the camera's shift is measured. */
    private byte[] still, stillSmall;
    /** Summed table of clean (w + 1 by h + 1), for interior. */
    private int[] cleanSum;
    private int[] labels, stack;
    private float[] smallCur, smallMean, hpSmallCur, hpSmallMean, hpCur, hpMean, tmp, hpAligned, hpBack, bandA, hpSlow;
    private boolean started, night;
    /** The areas (1.0.58) and each working pixel's sensitivity from them (0 = OFF). */
    private DetectionAreas areas = DetectionAreas.NONE;
    private byte[] levelAt;
    /** Some pixel is HIGH: the fine picture is kept (fineNow), learnt since (fineLive). */
    private boolean anyHigh, fineNow, fineLive;
    /** The fine picture (1.0.58): the input averaged by half the factor, twice the working size each way; 0 wide when the input is too small. */
    private int fw, fh, fineFactor;
    private float[] fcur, faligned, fmean, fvar, fsd;
    private byte[] fvalid, fraw, fchanged;
    /** Per fine pixel: when its last excursion began, since when it lasts, whether it is in one, whether that came soon after another. */
    private long[] excAt, excSince;
    private byte[] inExc, excAgain;
    private long lastMs, warmUntil;
    /**
     * A track's history keeps its times as floats counted from here (DRAUPNE Link 1.0.50): a float holds a clock's
     * raw milliseconds only to 2^24 - the PC's wall clock (1.8e12) to 131 s, a phone up for a week to 64 ms - and
     * every track then failed its travel test. Moved up to the present while no track holds a time from before.
     */
    private long epoch = Long.MIN_VALUE;
    /** The epoch moves on after this long (only while there is no track). */
    static final long REBASE_MS = 3_600_000;
    /** Pictures judged so far (not the ones settling). */
    private long frames;
    private float settledFor;
    private float textureMean;
    private final ArrayList<Track> tracks = new ArrayList<>();
    /** Blobs under MIN_AREA (2 pixels at least): only a proven track may take one (a far vehicle turning head-on); never a track of their own. */
    private final ArrayList<Blob> small = new ArrayList<>();
    private int nextId = 1;
    /** Things coming straight at the camera: a slow second look at the same pictures (1.0.53). */
    private Looming loom;
    /** Tracks keep the figures they were judged by, for describeTracks. */
    boolean debug;
    /** The last shift found, working pixels (for the tests and the log). */
    private float shiftX, shiftY;

    public MotionDetector(int sensitivity) {
        setSensitivity(sensitivity);
    }

    /** LOW, NORMAL or HIGH; anything else is NORMAL. Takes at once, the background kept. */
    public void setSensitivity(int sensitivity) {
        int s = sensitivity >= LOW && sensitivity <= HIGH ? sensitivity : NORMAL;
        if (s == this.sensitivity) return;
        this.sensitivity = s;
        buildLevels();
    }

    /** Areas with a sensitivity of their own (null: none). Takes at once, the background kept. */
    public void setAreas(DetectionAreas areas) {
        DetectionAreas a = areas == null ? DetectionAreas.NONE : areas;
        if (a.equals(this.areas)) return;
        this.areas = a;
        buildLevels();
    }

    public DetectionAreas getAreas() {
        return areas;
    }

    /** levelAt from the sensitivity and the areas, a later area over an earlier one. */
    private void buildLevels() {
        if (levelAt == null) return;
        java.util.Arrays.fill(levelAt, (byte) sensitivity);
        for (DetectionAreas.Area a : areas.list()) {
            int x0 = Math.max(0, (int) Math.floor(a.x * w)), x1 = Math.min(w, (int) Math.ceil((a.x + a.w) * w));
            int y0 = Math.max(0, (int) Math.floor(a.y * h)), y1 = Math.min(h, (int) Math.ceil((a.y + a.h) * h));
            for (int yy = y0; yy < y1; yy++) java.util.Arrays.fill(levelAt, yy * w + x0, yy * w + Math.max(x0, x1), (byte) a.level);
        }
        anyHigh = false;
        for (byte l : levelAt) if (l == HIGH) anyHigh = true;
    }

    /** The sensitivity at a working pixel (clamped into the picture). */
    private int levelAt(float x, float y) {
        int xx = Math.max(0, Math.min(w - 1, (int) x)), yy = Math.max(0, Math.min(h - 1, (int) y));
        return levelAt[yy * w + xx];
    }

    public int getSensitivity() {
        return sensitivity;
    }

    /** Forgets the background and the tracks: the next picture starts the learning again. */
    public void reset() {
        started = false;
        tracks.clear();
    }

    /** The tracks as text, for a test that fails (id, hits, age, where, the judges' figures). */
    String describeTracks(long now) {
        StringBuilder s = new StringBuilder();
        for (Track t : tracks) {
            float[] p = t.history.get(t.history.size() - 1);
            s.append(String.format(java.util.Locale.ROOT, "#%d hits %d age %.1f at %.0f,%.0f size %dx%d flick %.2f tex %.2f veg %.2f conf %.2f %s%n",
                    t.id, t.hits, (now - t.first) / 1000f, p[1], p[2], t.maxX - t.minX + 1, t.maxY - t.minY + 1, t.flicker,
                    t.textureRatio, t.vegetation, t.confidence, t.confirmed ? "CONFIRMED " + t.why : t.why));
        }
        if (loom != null) s.append(loom.describe());
        return s.toString();
    }

    /** A new id for a track or a loomer (they wrap at 65535). */
    private int newId() {
        int id = nextId;
        nextId = nextId >= 65535 ? 1 : nextId + 1;
        return id;
    }

    /** Tracks confirmed as close to the camera so far (1.0.56; for the tests). */
    int nearMade;

    /** How many tracks were started so far (the ids wrap at 65535). */
    int tracksMade() {
        return nextId - 1;
    }

    float shiftX() {
        return shiftX;
    }

    float shiftY() {
        return shiftY;
    }

    /**
     * One picture.
     * @param y      the luma, width x height, rows packed
     * @param u      the chroma (half size each way), or null: not used since 1.0.53 - the detection is by brightness alone
     * @param v      as u
     * @param timeMs when it was taken (any clock that only goes forward)
     */
    public Result process(byte[] y, int width, int height, byte[] u, byte[] v, long timeMs) {
        if (epoch == Long.MIN_VALUE || tracks.isEmpty() && timeMs - epoch > REBASE_MS) epoch = timeMs;
        if (width != inWidth || height != inHeight || cur == null) allocate(width, height);
        fineNow = fw > 0 && anyHigh;
        if (!fineNow) fineLive = false;
        downsample(y);
        float dt = !started ? 0.125f : Math.max(0.01f, Math.min(1f, (timeMs - lastMs) / 1000f));
        lastMs = timeMs;
        float lum = average(cur);
        night = night ? lum < NIGHT_LEAVE : lum < NIGHT_ENTER;
        if (!started) {
            restart(cur, timeMs);
            return new Result(Collections.<Detection>emptyList(), night, true, 0);
        }
        if (fineNow && !fineLive) seedFine();

        // 1. wind shake: the shift against the background, taken out
        textureMean = texture(mean);
        if (textureMean >= MIN_TEXTURE_FOR_SHIFT) {
            estimateShift();
        } else {
            shiftX = 0;
            shiftY = 0;
        }
        int validCount = warp(shiftX, shiftY);
        if (fineNow) warpFine(2 * shiftX, 2 * shiftY);
        if (validCount < w * h / 3) { // shifted too far to compare: start again from this picture
            restart(cur, timeMs);
            return new Result(Collections.<Detection>emptyList(), night, true, 0);
        }

        // 2. exposure: the picture's brightness matched to the background's
        float gain = gain();
        boolean settling = timeMs < warmUntil;
        if (gain < 0.33f || gain > 3f) { // more than a step: a new scene
            restart(aligned, timeMs);
            return new Result(Collections.<Detection>emptyList(), night, true, 0);
        }
        if (Math.abs(gain - 1) > EXPOSURE_STEP) {
            for (int i = 0; i < w * h; i++) { // the background takes the new exposure now
                mean[i] /= gain;
                slow[i] /= gain;
            }
            if (fineNow) for (int i = 0; i < fw * fh; i++) fmean[i] /= gain;
            loom.exposure(gain);
            settling = true;
        } else if (gainFromPeak) {
            // 1.0.56: the background follows the exposure picture by picture. Matching only the picture to it, the
            // camera following someone close by (2 - 3 % a picture) added up to a "step" within a second, and every step
            // threw the tracks away - the one on them too. Now only a jump between two pictures is a step.
            for (int i = 0; i < w * h; i++) {
                mean[i] /= gain;
                slow[i] /= gain;
            }
            if (fineNow) for (int i = 0; i < fw * fh; i++) fmean[i] /= gain;
            loom.exposure(gain);
        } else {
            for (int i = 0; i < w * h; i++) aligned[i] *= gain;
            if (fineNow) for (int i = 0; i < fw * fh; i++) faligned[i] *= gain;
        }

        // 3. foreground
        // the detail of picture and background a few pixels across (1.0.56): a shadow keeps the ground's, a thing in
        // front hides it (a band, not the finest grain: a shaken picture is aligned to a fraction of a pixel only)
        if (!night) {
            band(aligned, hpAligned);
            band(mean, hpBack);
            band(slow, hpSlow);
        }
        int fg = foreground();
        lastFg = fg / (float) Math.max(1, validCount);
        // more than a share changed: the picture as a whole (shake, a shadow) - unless it is one thing in front of the
        // camera with the room still there round it (1.0.56)
        if (fg > validCount * GLOBAL_CHANGE_FRACTION && (night || !oneThingInFront(fg))) settling = true;
        if (settling) {
            settledFor += timeMs < warmUntil ? 0 : dt;
            if (settledFor > RELEARN_AFTER_S) {
                restart(aligned, timeMs);
                return new Result(Collections.<Detection>emptyList(), night, true, 0);
            }
            learn(dt, null, true);
            // the tracks' way so far spans a picture that changed as a whole: they start again after it
            if (timeMs >= warmUntil) {
                tracks.clear();
                loom.unsettled();
            }
            return new Result(Collections.<Detection>emptyList(), night, true, 0);
        }
        settledFor = 0;

        // 4. blobs, 5. tracks, then the background learns this picture (the confirmed tracks' pixels slowly)
        frames++;
        List<Blob> blobs = blobs();
        follow(blobs, timeMs);
        if (!small.isEmpty()) { // a small blob a proven track took learns as slowly as the track's
            blobs = new ArrayList<>(blobs);
            for (Blob b : small) if (b.track != null) blobs.add(b);
        }
        learn(dt, blobs, false);
        if (!night) loom.update(aligned, valid, sd, near, dt, timeMs, rel(timeMs), levelAt);
        return new Result(report(timeMs), night, false, tracks.size());
    }

    // ------------------------------------------------------------------ the pictures

    private void allocate(int width, int height) {
        inWidth = width;
        inHeight = height;
        factor = Math.max(1, (width + WORK_MAX_WIDTH - 1) / WORK_MAX_WIDTH);
        w = Math.max(8, width / factor);
        h = Math.max(8, height / factor);
        int n = w * h;
        cur = new float[n];
        aligned = new float[n];
        mean = new float[n];
        var = new float[n];
        sd = new float[n];
        freq = new float[n];
        slow = new float[n];
        near = new float[n];
        changed = new byte[n];
        valid = new byte[n];
        raw = new byte[n];
        clean = new byte[n];
        grown = new byte[n];
        labels = new int[n];
        stack = new int[n];
        loom = new Looming(w, h, this::newId);
        loom.debug = debug;
        smallCur = new float[(w / 4) * (h / 4)];
        smallMean = new float[(w / 4) * (h / 4)];
        hpSmallCur = new float[(w / 4) * (h / 4)];
        hpSmallMean = new float[(w / 4) * (h / 4)];
        hpCur = new float[n];
        hpMean = new float[n];
        hpAligned = new float[n];
        bandA = new float[n];
        hpSlow = new float[n];
        still = new byte[n];
        cleanSum = new int[(w + 1) * (h + 1)];
        stillSmall = new byte[(w / 4) * (h / 4)];
        hpBack = new float[n];
        tmp = new float[n];
        levelAt = new byte[n];
        buildLevels();
        // the fine picture: only when the input is an even number of times the working picture
        fw = fh = 0;
        fineLive = false;
        if (factor >= 2 && factor % 2 == 0) {
            fineFactor = factor / 2;
            fw = w * 2;
            fh = h * 2;
            int fn = fw * fh;
            fcur = new float[fn];
            faligned = new float[fn];
            fmean = new float[fn];
            fvar = new float[fn];
            fsd = new float[fn];
            fvalid = new byte[fn];
            fraw = new byte[fn];
            fchanged = new byte[fn];
            excAt = new long[fn];
            excSince = new long[fn];
            inExc = new byte[fn];
            excAgain = new byte[fn];
        }
        started = false;
        tracks.clear();
    }

    /** The luma averaged over factor x factor blocks. */
    private void downsample(byte[] y) {
        if (fineNow) {
            // the fine picture, and the working one from it (the same block averages)
            int f = fineFactor;
            float scale = 1f / (f * f);
            for (int fy = 0; fy < fh; fy++) {
                for (int fx = 0; fx < fw; fx++) {
                    int sum = 0;
                    for (int dy = 0; dy < f; dy++) {
                        int row = (fy * f + dy) * inWidth + fx * f;
                        for (int dx = 0; dx < f; dx++) sum += y[row + dx] & 0xff;
                    }
                    fcur[fy * fw + fx] = sum * scale;
                }
            }
            for (int wy = 0; wy < h; wy++) {
                for (int wx = 0; wx < w; wx++) {
                    int i = 2 * wy * fw + 2 * wx;
                    cur[wy * w + wx] = (fcur[i] + fcur[i + 1] + fcur[i + fw] + fcur[i + fw + 1]) / 4;
                }
            }
            return;
        }
        int f = factor;
        float scale = 1f / (f * f);
        for (int wy = 0; wy < h; wy++) {
            for (int wx = 0; wx < w; wx++) {
                int sum = 0;
                for (int dy = 0; dy < f; dy++) {
                    int row = (wy * f + dy) * inWidth + wx * f;
                    for (int dx = 0; dx < f; dx++) sum += y[row + dx] & 0xff;
                }
                cur[wy * w + wx] = sum * scale;
            }
        }
    }

    private static float average(float[] a) {
        double s = 0;
        for (float x : a) s += x;
        return (float) (s / a.length);
    }

    /** The mean luma gradient of a picture: how much texture there is to find a shift by. */
    private float texture(float[] a) {
        double s = 0;
        int n = 0;
        for (int yy = 1; yy < h - 1; yy++) {
            for (int xx = 1; xx < w - 1; xx++) {
                int i = yy * w + xx;
                s += Math.abs(a[i + 1] - a[i - 1]) + Math.abs(a[i + w] - a[i - w]);
                n++;
            }
        }
        return n == 0 ? 0 : (float) (s / n);
    }

    private void restart(float[] picture, long timeMs) {
        if (fineNow) {
            System.arraycopy(picture == cur ? fcur : faligned, 0, fmean, 0, fw * fh);
            java.util.Arrays.fill(fvar, 4 * 4);
            java.util.Arrays.fill(excAt, Long.MIN_VALUE / 2);
            java.util.Arrays.fill(inExc, (byte) 0);
            fineLive = true;
        }
        System.arraycopy(picture, 0, mean, 0, w * h);
        System.arraycopy(picture, 0, slow, 0, w * h);
        java.util.Arrays.fill(var, 4 * 4);
        java.util.Arrays.fill(freq, 0);
        tracks.clear();
        loom.restart(picture);
        started = true;
        warmUntil = timeMs + WARM_UP_MS;
        settledFor = 0;
        shiftX = 0;
        shiftY = 0;
    }

    /** HIGH came on with the background learnt: the fine one starts from it (each fine pixel its working pixel's). */
    private void seedFine() {
        for (int fy = 0; fy < fh; fy++) {
            for (int fx = 0; fx < fw; fx++) {
                int i = fy * fw + fx, parent = (fy / 2) * w + fx / 2;
                fmean[i] = mean[parent];
                fvar[i] = var[parent];
            }
        }
        java.util.Arrays.fill(excAt, Long.MIN_VALUE / 2);
        java.util.Arrays.fill(inExc, (byte) 0);
        fineLive = true;
    }

    // ------------------------------------------------------------------ 1. the shift

    /**
     * The shift s with cur(x + s) ~ mean(x): a search of +-COARSE_RANGE on quarter-size copies, then +-3 working
     * pixels round it, then the costs either side for the fraction. Both pictures are compared by
     * their fine detail only (each less its own local average): a change of light over part of the picture - a
     * cloud's shadow, the exposure - hardly moves the answer. The cost is the mean absolute difference, each pixel's
     * capped at 20: a moving object doesn't pull it either.
     */
    private void estimateShift() {
        int sw = w / 4, sh = h / 4;
        // 1.0.56: measured on the room only - what was foreground in the last picture (a person close by, filling half
        // of it and moving 10 px a picture) pulled the answer along with it, and the room no longer matched
        byte[] m = null, ms = null;
        int usable = 0;
        for (int i = 0; i < w * h; i++) {
            still[i] = (byte) (raw[i] == 0 && grown[i] == 0 ? 1 : 0);
            usable += still[i];
        }
        // HIGH (1.0.58): measured on what is calm - leaves swaying below the foreground line (their background varies,
        // or they flickered lately) pulled the answer a fifth of a pixel off a camera standing still, and the fine
        // picture, judged without the 3 x 3 test's slack, was off by half a pixel everywhere
        // (only once the camera has stood still a while: shaken, every edge varies too, and without them the answer wanders)
        int calm = 0;
        if (anyHigh && stillness < STEADY_SHIFT) {
            for (int i = 0; i < w * h; i++) {
                if (still[i] != 0 && sd[i] <= SHIFT_CALM_SD && near[i] <= SHIFT_CALM_FLICKER) calm++;
            }
        }
        if (calm >= w * h * 15 / 100) {
            for (int i = 0; i < w * h; i++) still[i] = (byte) (still[i] != 0 && sd[i] <= SHIFT_CALM_SD && near[i] <= SHIFT_CALM_FLICKER ? 1 : 0);
            usable = calm;
            m = still;
            ms = stillSmall;
            for (int yy = 0; yy < sh; yy++) {
                for (int xx = 0; xx < sw; xx++) {
                    int n = 0;
                    for (int dy = 0; dy < 4; dy++) for (int dx = 0; dx < 4; dx++) n += still[(yy * 4 + dy) * w + xx * 4 + dx];
                    stillSmall[yy * sw + xx] = (byte) (n >= 12 ? 1 : 0);
                }
            }
        } else if (usable < w * h * 15 / 100 || usable > w * h * 75 / 100) {
            // nearly all moving, or little (leaves, a passer-by far off): the whole picture, as before
        } else {
            m = still;
            ms = stillSmall;
            for (int yy = 0; yy < sh; yy++) {
                for (int xx = 0; xx < sw; xx++) {
                    int n = 0;
                    for (int dy = 0; dy < 4; dy++) for (int dx = 0; dx < 4; dx++) n += still[(yy * 4 + dy) * w + xx * 4 + dx];
                    stillSmall[yy * sw + xx] = (byte) (n >= 12 ? 1 : 0);
                }
            }
        }
        reduce(cur, smallCur, sw, sh);
        reduce(mean, smallMean, sw, sh);
        highPass(smallCur, hpSmallCur, sw, sh, 1);
        highPass(smallMean, hpSmallMean, sw, sh, 1);
        highPass(cur, hpCur, w, h, 2);
        highPass(mean, hpMean, w, h, 2);
        int bx = 0, by = 0;
        float best = Float.MAX_VALUE;
        for (int dy = -COARSE_RANGE; dy <= COARSE_RANGE; dy++) {
            for (int dx = -COARSE_RANGE; dx <= COARSE_RANGE; dx++) {
                float c = cost(hpSmallCur, hpSmallMean, sw, sh, dx, dy, 1, ms);
                if (c < best) {
                    best = c;
                    bx = dx;
                    by = dy;
                }
            }
        }
        int fx = bx * 4, fy = by * 4;
        best = Float.MAX_VALUE;
        int cx = fx, cy = fy;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                float c = cost(hpCur, hpMean, w, h, fx + dx, fy + dy, 2, m);
                if (c < best) {
                    best = c;
                    cx = fx + dx;
                    cy = fy + dy;
                }
            }
        }
        // the fraction from every pixel (the search took every other one)
        best = cost(hpCur, hpMean, w, h, cx, cy, 1, m);
        // 1.0.56: the last shift held unless the new one is clearly better - over a room with little fine detail the
        // costs lie close together and the answer wandered (a few pixels a picture, then a whole picture judged off)
        int px = Math.round(shiftX), py = Math.round(shiftY);
        if (m != null && usable < w * h * 8 / 10 && (px != cx || py != cy)) {
            float held = cost(hpCur, hpMean, w, h, px, py, 1, m);
            if (held <= best * SHIFT_HOLD) {
                cx = px;
                cy = py;
                best = held;
            }
        }
        float l = cost(hpCur, hpMean, w, h, cx - 1, cy, 1, m), r = cost(hpCur, hpMean, w, h, cx + 1, cy, 1, m);
        float t = cost(hpCur, hpMean, w, h, cx, cy - 1, 1, m), b = cost(hpCur, hpMean, w, h, cx, cy + 1, 1, m);
        shiftX = cx + fraction(l, best, r);
        shiftY = cy + fraction(t, best, b);
        stillness += STILLNESS_RATE * (Math.max(Math.abs(shiftX), Math.abs(shiftY)) - stillness);
    }

    /** How far the camera has moved a picture lately (working pixels, smoothed): under STEADY_SHIFT it stands still. */
    private float stillness = 1;
    static final float STILLNESS_RATE = 0.2f;

    /** At least 80 % of the 7 x 7 round (x, y) foreground (clean): deep enough inside a blob for its detail. */
    private boolean interior(int x, int y) {
        int x0 = Math.max(0, x - 3), y0 = Math.max(0, y - 3), x1 = Math.min(w, x + 4), y1 = Math.min(h, y + 4);
        int W1 = w + 1;
        int n = cleanSum[y1 * W1 + x1] - cleanSum[y0 * W1 + x1] - cleanSum[y1 * W1 + x0] + cleanSum[y0 * W1 + x0];
        return n >= 0.8f * (x1 - x0) * (y1 - y0);
    }

    /** dst = src smoothed over 3 x 3, less its average over 7 x 7: the detail a few pixels across (bandA as scratch). */
    private void band(float[] src, float[] dst) {
        highPass(src, bandA, w, h, 1);
        for (int i = 0; i < w * h; i++) bandA[i] = src[i] - bandA[i];
        highPass(bandA, dst, w, h, 3);
    }

    /** HIGH (1.0.58): the shift is measured where the background varies by at most this (grey levels) and flickered at most this often. */
    static final float SHIFT_CALM_SD = 6, SHIFT_CALM_FLICKER = 0.05f;

    /** A new shift must cost less than the last one's cost over this (SHIFT_HOLD) to be taken. */
    static final float SHIFT_HOLD = 1.05f;

    /** dst = src less its average over (2r + 1)^2 round each pixel (a box, cut at the edges). */
    private void highPass(float[] src, float[] dst, int cw, int ch, int r) {
        // rows, then columns: running sums into tmp, then the box average
        for (int yy = 0; yy < ch; yy++) {
            int row = yy * cw;
            for (int xx = 0; xx < cw; xx++) {
                float s = 0;
                int n = 0;
                for (int k = Math.max(0, xx - r); k <= Math.min(cw - 1, xx + r); k++, n++) s += src[row + k];
                tmp[row + xx] = s / n;
            }
        }
        for (int yy = 0; yy < ch; yy++) {
            for (int xx = 0; xx < cw; xx++) {
                float s = 0;
                int n = 0;
                for (int k = Math.max(0, yy - r); k <= Math.min(ch - 1, yy + r); k++, n++) s += tmp[k * cw + xx];
                dst[yy * cw + xx] = src[yy * cw + xx] - s / n;
            }
        }
    }

    /**
     * Where between the pixels the least cost lies, from the costs at it and either side: an absolute difference's
     * cost rises in straight lines from its least, so two lines of equal slope through them (a parabola leans to 0).
     */
    private static float fraction(float before, float at, float after) {
        float rise = Math.max(before, after) - at;
        if (rise <= 1e-6f) return 0;
        return Math.max(-0.5f, Math.min(0.5f, 0.5f * (before - after) / rise));
    }

    private void reduce(float[] src, float[] dst, int sw, int sh) {
        for (int yy = 0; yy < sh; yy++) {
            for (int xx = 0; xx < sw; xx++) {
                float s = 0;
                for (int dy = 0; dy < 4; dy++) {
                    int row = (yy * 4 + dy) * w + xx * 4;
                    s += src[row] + src[row + 1] + src[row + 2] + src[row + 3];
                }
                dst[yy * sw + xx] = s / 16;
            }
        }
    }

    /** Mean capped absolute difference of a(x + d) and b(x), every step-th pixel each way. */
    private static float cost(float[] a, float[] b, int cw, int ch, int dx, int dy, int step, byte[] mask) {
        int x0 = Math.max(0, -dx), x1 = Math.min(cw, cw - dx);
        int y0 = Math.max(0, -dy), y1 = Math.min(ch, ch - dy);
        if (x1 - x0 < cw / 2 || y1 - y0 < ch / 2) return Float.MAX_VALUE;
        double s = 0;
        int n = 0;
        for (int yy = y0; yy < y1; yy += step) {
            int rowB = yy * cw, rowA = (yy + dy) * cw + dx;
            for (int xx = x0; xx < x1; xx += step) {
                if (mask != null && mask[rowB + xx] == 0) continue; // (1.0.56: the room only)
                float d = Math.abs(a[rowA + xx] - b[rowB + xx]);
                s += d < 20 ? d : 20;
                n++;
            }
        }
        return n == 0 ? Float.MAX_VALUE : (float) (s / n);
    }

    /** aligned(x) = cur(x + shift), bilinear; valid where all four source pixels are in the picture (and not on the border). */
    private int warp(float sx, float sy) {
        int count = 0;
        for (int yy = 0; yy < h; yy++) {
            float fy = yy + sy;
            int iy = (int) Math.floor(fy);
            float ty = fy - iy;
            for (int xx = 0; xx < w; xx++) {
                int i = yy * w + xx;
                float fx = xx + sx;
                int ix = (int) Math.floor(fx);
                float tx = fx - ix;
                if (ix < 0 || iy < 0 || ix + 1 >= w || iy + 1 >= h || xx == 0 || yy == 0 || xx == w - 1 || yy == h - 1) {
                    valid[i] = 0;
                    aligned[i] = mean[i];
                    continue;
                }
                int s = iy * w + ix;
                float top = cur[s] + (cur[s + 1] - cur[s]) * tx;
                float bottom = cur[s + w] + (cur[s + w + 1] - cur[s + w]) * tx;
                aligned[i] = top + (bottom - top) * ty;
                valid[i] = 1;
                count++;
            }
        }
        return count;
    }

    /**
     * The factor that brings the picture to the background's brightness (background over picture). 1.0.56: the most
     * common factor over the pixels (a histogram's peak), not the factor of their sums - a big dark or bright thing in
     * front of the camera (someone close by) moved the sums as an exposure step would, and the background was rescaled
     * under it. The camera's exposure moves every pixel by one factor: they pile up in one place of the histogram, the
     * thing's own pixels spread. The sums' factor when no clear peak stands out (a flat or noisy picture).
     */
    private float gain() {
        double sumMean = 0, sumCur = 0;
        java.util.Arrays.fill(gainBins, 0);
        int n = 0;
        for (int i = 0; i < w * h; i++) {
            if (valid[i] == 0) continue;
            sumMean += mean[i];
            sumCur += aligned[i];
            if ((i & 1) != 0) continue; // every other pixel is plenty for the histogram
            float r = (mean[i] + 4) / (aligned[i] + 4);
            int bin = (int) ((r - GAIN_LOW) / GAIN_BIN);
            if (bin >= 0 && bin < gainBins.length) gainBins[bin]++;
            n++;
        }
        float sums = (float) (sumMean / Math.max(1, sumCur));
        int best = 0;
        for (int b = 1; b < gainBins.length; b++) if (gainBins[b] > gainBins[best]) best = b;
        int lo = Math.max(0, best - 2), hi = Math.min(gainBins.length - 1, best + 2), peak = 0;
        double sum = 0;
        for (int b = lo; b <= hi; b++) {
            peak += gainBins[b];
            sum += gainBins[b] * (GAIN_LOW + (b + 0.5) * GAIN_BIN);
        }
        boolean found = !night && n > 0 && peak >= GAIN_PEAK * n;
        gainFromPeak = false;
        if (found) {
            peakAt = lastMs;
            float mode = (float) (sum / peak);
            // the sums' factor while the two agree (the picture changed as a whole, or not at all), and unless something
            // big was in the last picture: as before
            if (Math.abs(mode - sums) <= GAIN_DISAGREE || lastFg < GAIN_BIG_THING) return sums;
            gainFromPeak = true;
            return mode;
        }
        // no peak while there was one moments ago: something big in front (the room's share too small to stand out),
        // not the camera's exposure - that moves every pixel by one factor, a peak of its own
        return !night && lastFg >= GAIN_BIG_THING && lastMs - peakAt <= PEAK_HOLD_MS ? 1f : sums;
    }

    /** The gain histogram: factors from GAIN_LOW in steps of GAIN_BIN (0.33 - 3, past which the scene starts again). */
    static final float GAIN_LOW = 0.3f, GAIN_BIN = 0.01f;
    /** The peak (five bins) must hold this share of the pixels to be taken. */
    static final float GAIN_PEAK = 0.15f;
    /** The peak's factor is taken over the sums' only when they differ by more than this (something big in front). */
    static final float GAIN_DISAGREE = 0.04f;
    /** ... and only while at least this share of the last picture was foreground (the sums' factor is fine otherwise). */
    static final float GAIN_BIG_THING = 0.1f;
    /** The share of the last picture that was foreground. */
    private float lastFg;

    /** The last gain came from the histogram's peak (the exposure's own factor), not from the sums. */
    private boolean gainFromPeak;
    /** When the gain last came from a peak; for PEAK_HOLD_MS after it, no peak means no exposure change. */
    private long peakAt = Long.MIN_VALUE / 2;
    static final long PEAK_HOLD_MS = 2000;
    private final int[] gainBins = new int[(int) ((3.1f - GAIN_LOW) / GAIN_BIN)];

    /**
     * More than GLOBAL_CHANGE_FRACTION of the picture changed: one thing in front of the camera (1.0.56) rather than the
     * picture as a whole? The change not other light, packed into the grid cells it fills, the room still clean round
     * it (NEAR_CELL). A shake's change lies thin along every edge; a misjudged exposure leaves no cell clean.
     */
    private boolean oneThingInFront(int changedCount) {
        int cw = (w + NEAR_CELL - 1) / NEAR_CELL, ch = (h + NEAR_CELL - 1) / NEAR_CELL;
        if (cellRaw == null || cellRaw.length != cw * ch) cellRaw = new int[cw * ch];
        java.util.Arrays.fill(cellRaw, -1);
        int raws = 0, inFull = 0, clean = 0, cells = 0;
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                int v = 0, r = 0;
                for (int yy = cy * NEAR_CELL; yy < Math.min(h, (cy + 1) * NEAR_CELL); yy++) {
                    for (int xx = cx * NEAR_CELL; xx < Math.min(w, (cx + 1) * NEAR_CELL); xx++) {
                        int i = yy * w + xx;
                        v += valid[i];
                        r += raw[i];
                    }
                }
                if (v < NEAR_CELL * NEAR_CELL / 2) continue; // the border
                cells++;
                raws += r;
                if (r >= NEAR_FULL_CELL * v) {
                    inFull += r;
                    cellRaw[cy * cw + cx] = r;
                } else if (r < NEAR_CLEAN_CELL * v) clean++;
            }
        }
        if (cells == 0 || raws < 0.7f * changedCount || inFull < NEAR_IN_FULL * raws || clean < NEAR_CLEAN_CELLS * cells) return false;
        // one thing: the filled cells one piece (a camera knocked further than the shift search reaches leaves the room
        // changed in pieces all over)
        int best = 0;
        int[] queue = new int[cw * ch];
        for (int start = 0; start < cw * ch; start++) {
            if (cellRaw[start] < 0) continue;
            int sum = 0, head = 0, tail = 0;
            queue[tail++] = start;
            sum += cellRaw[start];
            cellRaw[start] = -1;
            while (head < tail) {
                int c = queue[head++], cx = c % cw, cy = c / cw;
                int[] next = {cx > 0 ? c - 1 : -1, cx < cw - 1 ? c + 1 : -1, cy > 0 ? c - cw : -1, cy < ch - 1 ? c + cw : -1};
                for (int n : next) {
                    if (n < 0 || cellRaw[n] < 0) continue;
                    sum += cellRaw[n];
                    cellRaw[n] = -1;
                    queue[tail++] = n;
                }
            }
            best = Math.max(best, sum);
        }
        return best >= NEAR_ONE_PIECE * inFull;
    }

    /** The share of the filled cells' change the largest piece of them must hold. */
    static final float NEAR_ONE_PIECE = 0.85f;
    private int[] cellRaw;

    // ------------------------------------------------------------------ 3. foreground

    /** Marks raw[] foreground pixels; returns how many. */
    private int foreground() {
        float floor = night ? 5f : 2.5f;
        for (int i = 0; i < w * h; i++) sd[i] = Math.max(floor, (float) Math.sqrt(var[i]));
        // a steady camera: a pixel also counts against its own background alone, by a wider margin - a small thing on a
        // strong edge (a vehicle far off on the horizon line) matches a dark neighbour in the 3 x 3 test and was never seen
        boolean steady = Math.abs(shiftX) < STEADY_SHIFT && Math.abs(shiftY) < STEADY_SHIFT;
        int count = 0;
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int i = yy * w + xx;
                raw[i] = 0;
                changed[i] = 0;
                if (valid[i] == 0) continue;
                // the sensitivity of the area it lies in (1.0.58): an OFF area is never foreground
                int lv = levelAt[i];
                if (lv == OFF) continue;
                float k = K_SIGMA[lv];
                float margin = night ? MIN_DIFF_NIGHT[lv] : MIN_DIFF_DAY[lv];
                float a = aligned[i];
                if (steady && !night) {
                    float own = Math.abs(a - mean[i]) - k * sd[i];
                    // HIGH: against its own background a fraction of a pixel off too, not only against any neighbour's
                    if (own > margin * OWN_MARGIN || lv == HIGH && halfLeast(mean, sd, w, i, a, k) > margin * HALF_MARGIN) {
                        count++;
                        changed[i] = 1;
                        if (!otherLight(xx, yy)) raw[i] = 1;
                        continue;
                    }
                }
                float least = Float.MAX_VALUE;
                // further from every background pixel round it: a picture a fraction of a pixel off still matches
                for (int dy = -1; dy <= 1 && least > margin; dy++) {
                    int row = (yy + dy) * w + xx;
                    for (int dx = -1; dx <= 1; dx++) {
                        int n = row + dx;
                        float d = night ? a - mean[n] : Math.abs(a - mean[n]);
                        float score = d - k * sd[n];
                        if (score < least) least = score;
                    }
                }
                if (least > margin) {
                    count++; // the picture changed here, whatever the cause (the share of it says shake or shadow)
                    changed[i] = 1;
                    if (!night && otherLight(xx, yy)) continue;
                    raw[i] = 1;
                }
            }
        }
        if (fineNow) {
            if (steady && !night) {
                count += fineForeground();
            } else {
                java.util.Arrays.fill(fraw, (byte) 0);
                java.util.Arrays.fill(fchanged, (byte) 0);
            }
        }
        return count;
    }

    /**
     * HIGH, the camera steady (1.0.58): the same tests on the fine picture where the working pixel is HIGH; a working
     * pixel with FINE_OF_FOUR of its four fine pixels changed is changed too. Returns how many it added.
     */
    private int fineForeground() {
        float k = K_SIGMA[HIGH], margin = MIN_DIFF_DAY[HIGH];
        for (int i = 0; i < fw * fh; i++) fsd[i] = Math.max(2.5f, (float) Math.sqrt(fvar[i]));
        for (int yy = 0; yy < fh; yy++) {
            for (int xx = 0; xx < fw; xx++) {
                int i = yy * fw + xx;
                fraw[i] = 0;
                fchanged[i] = 0;
                if (fvalid[i] == 0 || levelAt[(yy / 2) * w + xx / 2] != HIGH) continue;
                float a = faligned[i];
                boolean hit = Math.abs(a - fmean[i]) - k * fsd[i] > margin * OWN_MARGIN
                        || halfLeast(fmean, fsd, fw, i, a, k) > margin * HALF_MARGIN;
                if (!hit) {
                    float least = Float.MAX_VALUE;
                    for (int dy = -1; dy <= 1 && least > margin; dy++) {
                        int row = (yy + dy) * fw + xx;
                        for (int dx = -1; dx <= 1; dx++) {
                            int q = row + dx;
                            float score = Math.abs(a - fmean[q]) - k * fsd[q];
                            if (score < least) least = score;
                        }
                    }
                    hit = least > margin;
                }
                if (!hit) continue;
                fchanged[i] = 1;
                if (!otherLight(faligned, fmean, fw, xx, yy)) fraw[i] = 1;
            }
        }
        int added = 0;
        for (int wy = 0; wy < h; wy++) {
            for (int wx = 0; wx < w; wx++) {
                int i = wy * w + wx;
                if (valid[i] == 0 || changed[i] != 0 || levelAt[i] != HIGH) continue;
                int f = 2 * wy * fw + 2 * wx;
                if (fchanged[f] + fchanged[f + 1] + fchanged[f + fw] + fchanged[f + fw + 1] < FINE_OF_FOUR) continue;
                changed[i] = 1;
                added++;
                if (fraw[f] + fraw[f + 1] + fraw[f + fw] + fraw[f + fw + 1] >= FINE_OF_FOUR) raw[i] = 1;
            }
        }
        return added;
    }

    /**
     * The least of |a - background| - k sd over the background (m, sd; cw wide) shifted by at most half a pixel each way
     * round pixel i (1.0.58): the camera steady, the picture is aligned to a fraction of a pixel - a small thing on a
     * busy background matches some neighbour in the 3 x 3 test, but not its own place.
     */
    private static float halfLeast(float[] m, float[] sd, int cw, int i, float a, float k) {
        float c = m[i], l = m[i - 1], r = m[i + 1], u = m[i - cw], d = m[i + cw];
        float sc = sd[i], sl = sd[i - 1], sr = sd[i + 1], su = sd[i - cw], sn = sd[i + cw];
        float least = Math.abs(a - c) - k * sc;
        least = Math.min(least, Math.abs(a - (c + l) / 2) - k * (sc + sl) / 2);
        least = Math.min(least, Math.abs(a - (c + r) / 2) - k * (sc + sr) / 2);
        least = Math.min(least, Math.abs(a - (c + u) / 2) - k * (sc + su) / 2);
        least = Math.min(least, Math.abs(a - (c + d) / 2) - k * (sc + sn) / 2);
        least = Math.min(least, Math.abs(a - (c + l + u + m[i - cw - 1]) / 4) - k * (sc + sl + su + sd[i - cw - 1]) / 4);
        least = Math.min(least, Math.abs(a - (c + r + u + m[i - cw + 1]) / 4) - k * (sc + sr + su + sd[i - cw + 1]) / 4);
        least = Math.min(least, Math.abs(a - (c + l + d + m[i + cw - 1]) / 4) - k * (sc + sl + sn + sd[i + cw - 1]) / 4);
        least = Math.min(least, Math.abs(a - (c + r + d + m[i + cw + 1]) / 4) - k * (sc + sr + sn + sd[i + cw + 1]) / 4);
        return least;
    }

    /** warp on the fine picture (1.0.58; the working shift times two). */
    private void warpFine(float sx, float sy) {
        for (int yy = 0; yy < fh; yy++) {
            float fy = yy + sy;
            int iy = (int) Math.floor(fy);
            float ty = fy - iy;
            for (int xx = 0; xx < fw; xx++) {
                int i = yy * fw + xx;
                float fx = xx + sx;
                int ix = (int) Math.floor(fx);
                float tx = fx - ix;
                if (ix < 0 || iy < 0 || ix + 1 >= fw || iy + 1 >= fh || xx == 0 || yy == 0 || xx == fw - 1 || yy == fh - 1) {
                    fvalid[i] = 0;
                    faligned[i] = fmean[i];
                    continue;
                }
                int s = iy * fw + ix;
                float top = fcur[s] + (fcur[s + 1] - fcur[s]) * tx;
                float bottom = fcur[s + fw] + (fcur[s + fw + 1] - fcur[s + fw]) * tx;
                faligned[i] = top + (bottom - top) * ty;
                fvalid[i] = 1;
            }
        }
    }

    /**
     * The same ground in other light (a cloud's shadow, a patch of sun): over the 3 x 3 pixels round it the picture
     * follows the background's texture closely (correlation over LIGHT_CHANGE_CORRELATION), only darker or brighter.
     * Something in front of the ground hides that texture. Only where there is texture to follow: on a flat sky the
     * two can't be told apart, and it stays foreground.
     */
    private boolean otherLight(int xx, int yy) {
        return otherLight(aligned, mean, w, xx, yy);
    }

    /** otherLight on a picture and its background cw wide (the working or the fine one). */
    private static boolean otherLight(float[] pic, float[] back, int cw, int xx, int yy) {
        float sa = 0, sm = 0, saa = 0, smm = 0, sam = 0;
        float least = Float.MAX_VALUE, most = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int row = (yy + dy) * cw + xx;
            for (int dx = -1; dx <= 1; dx++) {
                float a = pic[row + dx], m = back[row + dx];
                sa += a;
                sm += m;
                saa += a * a;
                smm += m * m;
                sam += a * m;
                float r = (a + 4) / (m + 4);
                if (r < least) least = r;
                if (r > most) most = r;
            }
        }
        // light darkens or brightens the patch by one factor; a dark thing lying along a dark line (a vehicle on the
        // horizon) follows the texture too, but leaves the pale ground round it as it was (1.0.52)
        if (most > LIGHT_RATIO_SPREAD * least) return false;
        float va = saa / 9 - (sa / 9) * (sa / 9), vm = smm / 9 - (sm / 9) * (sm / 9);
        float minVar = (float) (LIGHT_CHANGE_MIN_SD * LIGHT_CHANGE_MIN_SD);
        if (vm < minVar || va < minVar * 0.25f) return false;
        float ratio = sa / Math.max(1, sm);
        if (ratio < 0.3f || ratio > 2f) return false;
        float cov = sam / 9 - (sa / 9) * (sm / 9);
        return cov / (float) Math.sqrt(va * vm) > LIGHT_CHANGE_CORRELATION;
    }

    // ------------------------------------------------------------------ 4. blobs

    /** dst = the most of src within r pixels each way (a square), rows then columns. */
    private void nearMax(float[] src, float[] dst, int r) {
        for (int yy = 0; yy < h; yy++) {
            int row = yy * w;
            for (int xx = 0; xx < w; xx++) {
                float m = 0;
                for (int k = Math.max(0, xx - r); k <= Math.min(w - 1, xx + r); k++) m = Math.max(m, src[row + k]);
                tmp[row + xx] = m;
            }
        }
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                float m = 0;
                for (int k = Math.max(0, yy - r); k <= Math.min(h - 1, yy + r); k++) m = Math.max(m, tmp[k * w + xx]);
                dst[yy * w + xx] = m;
            }
        }
    }
    private List<Blob> blobs() {
        nearMax(freq, near, 2);
        // sensor noise: a foreground pixel needs two foreground neighbours
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int i = yy * w + xx;
                clean[i] = 0;
                if (raw[i] == 0 || xx == 0 || yy == 0 || xx == w - 1 || yy == h - 1) continue;
                int n = raw[i - w - 1] + raw[i - w] + raw[i - w + 1] + raw[i - 1] + raw[i + 1] + raw[i + w - 1] + raw[i + w] + raw[i + w + 1];
                if (n >= 2) clean[i] = 1;
            }
        }
        // how much of each pixel's 7 x 7 is foreground (a summed table of clean, rows and columns)
        for (int yy = 0; yy < h; yy++) {
            int run = 0;
            for (int xx = 0; xx < w; xx++) {
                run += clean[yy * w + xx];
                cleanSum[(yy + 1) * (w + 1) + xx + 1] = cleanSum[yy * (w + 1) + xx + 1] + run;
            }
        }
        // grown by a pixel, so the pieces of one thing (legs, a car's windows) join
        java.util.Arrays.fill(grown, (byte) 0);
        for (int yy = 1; yy < h - 1; yy++) {
            for (int xx = 1; xx < w - 1; xx++) {
                int i = yy * w + xx;
                if (clean[i] == 0) continue;
                grown[i - w - 1] = grown[i - w] = grown[i - w + 1] = grown[i - 1] = grown[i] = grown[i + 1] = 1;
                grown[i + w - 1] = grown[i + w] = grown[i + w + 1] = 1;
            }
        }
        java.util.Arrays.fill(labels, 0);
        float lumMean = average(cur);
        List<Blob> found = new ArrayList<>();
        small.clear();
        int next = 0;
        for (int start = 0; start < w * h; start++) {
            if (grown[start] == 0 || labels[start] != 0) continue;
            Blob b = new Blob();
            b.label = ++next;
            b.minX = b.minY = Integer.MAX_VALUE;
            b.maxX = b.maxY = -1;
            int top = 0;
            stack[top++] = start;
            labels[start] = b.label;
            while (top > 0) {
                int i = stack[--top];
                int xx = i % w, yy = i / w;
                if (clean[i] != 0) {
                    b.count++;
                    b.sumX += xx;
                    b.sumY += yy;
                    if (xx < b.minX) b.minX = xx;
                    if (xx > b.maxX) b.maxX = xx;
                    if (yy < b.minY) b.minY = yy;
                    if (yy > b.maxY) b.maxY = yy;
                    b.freq += near[i];
                    if (xx > 0 && yy > 0 && xx < w - 1 && yy < h - 1) {
                        b.texture += Math.abs(mean[i + 1] - mean[i - 1]) + Math.abs(mean[i + w] - mean[i - w]);
                    }
                    if (aligned[i] > b.peak) b.peak = aligned[i];
                    float a = aligned[i], m = mean[i];
                    b.sa += a;
                    b.sm += m;
                    b.saa += a * a;
                    b.smm += m * m;
                    b.sam += a * m;
                    double q = (a + 4) / (m + 4);
                    b.sq += q;
                    b.sqq += q * q;
                    if (interior(xx, yy)) {
                        float da = hpAligned[i], dm = hpBack[i];
                        b.interior++;
                        b.da += da;
                        b.dm += dm;
                        b.daa += da * da;
                        b.dmm += dm * dm;
                        b.dam += da * dm;
                    }
                }
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = yy + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = xx + dx;
                        if (nx < 0 || nx >= w) continue;
                        int n = ny * w + nx;
                        if (grown[n] != 0 && labels[n] == 0) {
                            labels[n] = b.label;
                            stack[top++] = n;
                        }
                    }
                }
            }
            // (1.0.56: a thing close to the camera may fill most of the picture - a whole-picture change never comes here)
            if (b.count < 2 || b.count > w * h * 95 / 100) continue;
            if (night && b.peak < lumMean + NIGHT_PEAK_OVER_MEAN) continue;
            if (!night && lightChange(b)) continue;
            b.freq /= b.count;
            b.texture /= b.count;
            if (b.count < MIN_AREA[Math.max(LOW, levelAt(b.cx(), b.cy()))]) small.add(b);
            else found.add(b);
        }
        return found;
    }

    /**
     * The same ground in other light (a cloud's shadow, a patch of sun): the blob's pixels follow the background's
     * texture closely, only darker or brighter. Something in front of the ground hides that texture. Only where there
     * is texture to follow (a flat sky says nothing either way).
     */
    /**
     * 1.0.56: the ground's detail (a few pixels across) still shows through the blob - a shadow or a patch of sun
     * however soft its edge, which lightChange's one factor misses. Something in front of the ground hides it.
     */
    /** A blob with at least this many interior pixels has its detail measured (NEAR_DETAIL_SHOWS). */
    static final int DETAIL_MIN_PIXELS = 30;

    private static boolean lightChange(Blob b) {
        int n = b.count;
        double va = b.saa / n - (b.sa / n) * (b.sa / n), vm = b.smm / n - (b.sm / n) * (b.sm / n);
        if (vm < LIGHT_CHANGE_MIN_SD * LIGHT_CHANGE_MIN_SD || va <= 1e-6) return false;
        double cov = b.sam / n - (b.sa / n) * (b.sm / n);
        return cov / Math.sqrt(va * vm) > LIGHT_CHANGE_CORRELATION;
    }

    // ------------------------------------------------------------------ 5. tracks

    private void follow(List<Blob> blobs, long now) {
        // every pair within its gate, nearest first; each track and blob taken once
        List<float[]> pairs = new ArrayList<>();
        for (int t = 0; t < tracks.size(); t++) {
            Track tr = tracks.get(t);
            float[] last = tr.history.get(tr.history.size() - 1);
            float[] vel = velocity(tr);
            float ahead = (rel(now) - last[0]) / 1000f;
            float px = last[1] + vel[0] * ahead, py = last[2] + vel[1] * ahead;
            float size = Math.max(tr.maxX - tr.minX + 1, tr.maxY - tr.minY + 1);
            float gate = Math.max(10, 0.8f * size) + 0.5f * (float) Math.hypot(vel[0], vel[1]) * ahead;
            // a proven thing that breaks up or stops to turn is as big and as fast as it was lately, not its last piece
            if (tr.proven) gate = Math.max(gate, Math.max(10, recentSize(tr)) + recentSpeed(tr) * Math.max(0.25f, ahead));
            for (int b = 0; b < blobs.size(); b++) {
                Blob bl = blobs.get(b);
                float d = (float) Math.hypot(bl.cx() - px, bl.cy() - py);
                if (d <= gate) pairs.add(new float[]{d, t, b});
            }
        }
        Collections.sort(pairs, (p, q) -> Float.compare(p[0], q[0]));
        boolean[] trackTaken = new boolean[tracks.size()];
        for (float[] p : pairs) {
            int t = (int) p[1], b = (int) p[2];
            if (trackTaken[t] || blobs.get(b).track != null) continue;
            trackTaken[t] = true;
            Track tr = tracks.get(t);
            if (tr.confirmed) join(tr, blobs.get(b), blobs, tr.proven ? recentSize(tr) : 0);
            update(tr, blobs.get(b), now);
        }
        // a proven track nothing matched: the nearest small blob in its gate (it shrank turning head-on, far off)
        for (int t = 0; t < tracks.size(); t++) {
            Track tr = tracks.get(t);
            if (trackTaken[t] || !tr.proven || small.isEmpty()) continue;
            float[] last = tr.history.get(tr.history.size() - 1);
            float ahead = (rel(now) - last[0]) / 1000f;
            float gate = Math.max(10, recentSize(tr)) + recentSpeed(tr) * Math.max(0.25f, ahead);
            Blob best = null;
            float bestD = gate;
            for (Blob b : small) {
                float d = (float) Math.hypot(b.cx() - last[1], b.cy() - last[2]);
                if (b.track == null && d <= bestD) {
                    bestD = d;
                    best = b;
                }
            }
            if (best != null) {
                trackTaken[t] = true;
                update(tr, best, now);
            }
        }
        for (Blob b : blobs) {
            if (b.track != null) continue;
            Track t = new Track(newId(), now, frames, b);
            tracks.add(t);
            update(t, b, now);
        }
        for (int i = tracks.size() - 1; i >= 0; i--) {
            Track tr = tracks.get(i);
            if (now - tr.lastSeen > (tr.proven ? PROVEN_LOST_MS : TRACK_LOST_MS)) tracks.remove(i);
        }
        // a busy picture: the oldest unconfirmed tracks go first, past 60
        for (int i = 0; tracks.size() > 60 && i < tracks.size(); ) {
            if (!tracks.get(i).confirmed) tracks.remove(i);
            else i++;
        }
    }

    /**
     * The other pieces of a confirmed track's thing: unmatched blobs that touch its last box (JOIN_GAP) or the matched
     * blob's join it - its bounds, area and sums grow, and they are the track's (no track of their own, learnt slowly).
     */
    private static void join(Track t, Blob main, List<Blob> blobs, float recentSize) {
        int ax0 = Math.min(t.minX, main.minX) - JOIN_GAP, ay0 = Math.min(t.minY, main.minY) - JOIN_GAP;
        int ax1 = Math.max(t.maxX, main.maxX) + JOIN_GAP, ay1 = Math.max(t.maxY, main.maxY) + JOIN_GAP;
        for (Blob o : blobs) {
            if (o == main || o.track != null) continue;
            if (o.maxX < ax0 || o.minX > ax1 || o.maxY < ay0 || o.minY > ay1) continue;
            // only within the matched blob's own reach: a separate thing passing close by stays its own
            float reach = JOIN_GAP + Math.max(main.maxX - main.minX, recentSize);
            if (o.maxX < main.minX - reach || o.minX > main.maxX + reach) continue;
            o.track = t;
            main.minX = Math.min(main.minX, o.minX);
            main.minY = Math.min(main.minY, o.minY);
            main.maxX = Math.max(main.maxX, o.maxX);
            main.maxY = Math.max(main.maxY, o.maxY);
        }
    }

    /** The largest the track's box was over its kept history (working pixels, its longer side). */
    private static float recentSize(Track t) {
        float s = 0;
        for (float[] p : t.history) s = Math.max(s, Math.max(p[5] - p[3] + 1, p[6] - p[4] + 1));
        return s;
    }

    /** The fastest the track went over its kept history, working pixels a second (half-second steps). */
    private static float recentSpeed(Track t) {
        float best = 0;
        List<float[]> h = t.history;
        for (int i = 0, j = 0; i < h.size(); i++) {
            while (j < i && h.get(i)[0] - h.get(j + 1)[0] >= 500) j++;
            float dt = (h.get(i)[0] - h.get(j)[0]) / 1000f;
            if (dt >= 0.4f) best = Math.max(best, (float) Math.hypot(h.get(i)[1] - h.get(j)[1], h.get(i)[2] - h.get(j)[2]) / dt);
        }
        return best;
    }

    /** A time as the tracks' history keeps it: ms from the epoch, small enough for a float to hold exactly. */
    private float rel(long timeMs) {
        return (float) (timeMs - epoch);
    }

    /** Working pixels a second, from the last half second of the track. */
    private static float[] velocity(Track t) {
        List<float[]> hist = t.history;
        float[] last = hist.get(hist.size() - 1);
        for (int i = hist.size() - 2; i >= 0; i--) {
            float[] p = hist.get(i);
            float dt = (last[0] - p[0]) / 1000f;
            if (dt >= 0.5f || i == 0) {
                return dt <= 0 ? new float[]{0, 0} : new float[]{(last[1] - p[1]) / dt, (last[2] - p[2]) / dt};
            }
        }
        return new float[]{0, 0};
    }

    private void update(Track t, Blob b, long now) {
        b.track = t;
        t.lastSeen = now;
        t.hits++;
        t.minX = b.minX;
        t.minY = b.minY;
        t.maxX = b.maxX;
        t.maxY = b.maxY;
        t.light = night;
        t.history.add(new float[]{rel(now), b.cx(), b.cy(), b.minX, b.minY, b.maxX, b.maxY});
        while (t.history.size() > 2 && rel(now) - t.history.get(0)[0] > TRAVEL_WINDOW_MS + 1000) t.history.remove(0);
        float fromOrigin = (float) Math.hypot(b.cx() - t.ox, b.cy() - t.oy);
        if (fromOrigin > t.maxFromOrigin) {
            t.maxFromOrigin = fromOrigin;
            t.farAt = now;
        }
        // the blob's look, averaged over the track's life (a third each picture)
        float a = t.hits == 1 ? 1 : 0.3f;
        t.flicker += a * (b.freq - t.flicker);
        float ratio = textureMean > 0.5f ? b.texture / textureMean : 1;
        t.textureRatio += a * (ratio - t.textureRatio);
        // 1.0.56, a thing close to the camera: how big and solid, how calm the place was before it
        t.area = b.count;
        t.fill = b.count / (float) ((b.maxX - b.minX + 1) * (b.maxY - b.minY + 1));
        double qm = b.sq / b.count;
        t.ratioSpread = (float) (Math.sqrt(Math.max(0, b.sqq / b.count - qm * qm)) / Math.max(1e-6, qm));
        int n = Math.max(1, b.interior);
        double va = b.daa / n - (b.da / n) * (b.da / n), vm = b.dmm / n - (b.dm / n) * (b.dm / n);
        t.detail = b.interior < DETAIL_MIN_PIXELS ? Float.NaN : vm < NEAR_DETAIL_MIN_SD * NEAR_DETAIL_MIN_SD || va <= 1e-6 ? Float.NaN
                : (float) ((b.dam / n - (b.da / n) * (b.dm / n)) / Math.sqrt(va * vm));
        if (t.hits <= 3) t.startFlicker = Math.max(t.startFlicker, Math.max(b.freq, ringFlicker(b)));
        judge(t, now, fromOrigin);
    }

    /** Whether the track travels, its vegetation score, its confidence; confirms it, or takes the confirmation back. */
    private void judge(Track t, long now, float fromOrigin) {
        // the rules of the area it is in (1.0.58): never confirmed in an OFF area
        float[] at = t.history.get(t.history.size() - 1);
        int sensitivity = levelAt(at[1], at[2]);
        if (sensitivity == OFF) {
            t.confirmed = t.proven = t.near = false;
            t.confidence = 0;
            return;
        }
        // positions a quarter second apart at least: the jitter of a blob's centre doesn't add to the way gone
        List<float[]> pts = new ArrayList<>();
        for (float[] p : t.history) {
            if (rel(now) - p[0] > TRAVEL_WINDOW_MS) continue;
            if (pts.isEmpty() || p[0] - pts.get(pts.size() - 1)[0] >= 250) pts.add(p);
        }
        float[] newest = t.history.get(t.history.size() - 1);
        if (pts.isEmpty() || pts.get(pts.size() - 1) != newest) pts.add(newest);
        float[] p0 = pts.get(0);
        float net = (float) Math.hypot(newest[1] - p0[1], newest[2] - p0[2]);
        float path = 0;
        for (int i = 1; i < pts.size(); i++) {
            path += (float) Math.hypot(pts.get(i)[1] - pts.get(i - 1)[1], pts.get(i)[2] - pts.get(i - 1)[2]);
        }
        float straight = path > 0 ? net / path : 0;
        // both halves of the window the same way as the whole
        float[] mid = pts.get(pts.size() / 2);
        float ux = newest[1] - p0[1], uy = newest[2] - p0[2];
        boolean steady = net > 0 && cos(mid[1] - p0[1], mid[2] - p0[2], ux, uy) > 0.3f
                && cos(newest[1] - mid[1], newest[2] - mid[2], ux, uy) > 0.3f;
        // back and forth: steps along the way's line that turn round
        int reversals = 0;
        float lastSign = 0;
        for (int i = 1; i < pts.size(); i++) {
            float along = net > 0 ? ((pts.get(i)[1] - pts.get(i - 1)[1]) * ux + (pts.get(i)[2] - pts.get(i - 1)[2]) * uy) / net : 0;
            if (Math.abs(along) < 0.4f) continue;
            float sign = Math.signum(along);
            if (lastSign != 0 && sign != lastSign) reversals++;
            lastSign = sign;
        }
        float size = Math.max(t.maxX - t.minX + 1, t.maxY - t.minY + 1);
        float needed = Math.max(MIN_TRAVEL[sensitivity], 0.5f * size);
        // a young background doesn't know yet where leaves flicker: a longer way asked meanwhile
        if (now - warmUntil < YOUNG_MS) needed *= 1.5f;
        boolean returning = t.maxFromOrigin > needed * 0.5f && fromOrigin < 0.6f * t.maxFromOrigin;
        if (returning) t.cameBack = true; // (a thing coming in at the edge grows: its centre jitters, it doesn't come back)
        boolean wake = leavesChange(t, now);
        // 1.0.56: a shadow's edge - not confirmed on that (shadeBehind)
        boolean shade = !t.confirmed && shadeBehind;
        float oscillation = Math.min(1, reversals / 3f);
        // the flicker's average started from 0 at the end of the warm-up: scaled up while it is young (its bias)
        float learnt = (float) (1 - Math.exp(-Math.max(0, now - warmUntil) / 20_000.0));
        // (at most twice: more made every textured thing - a vehicle - look like leaves for the first 20 s, 1.0.52)
        float flicker = Math.min(1, t.flicker / Math.max(0.5f, learnt) / 0.12f);
        float texture = Math.max(0, Math.min(1, (t.textureRatio - 1) / 2));
        t.vegetation = VEG_FLICKER * flicker + VEG_WAY * Math.max(oscillation, 1 - straight) + VEG_TEXTURE * texture;
        float ageS = (now - t.first) / 1000f;
        // seen in most pictures since it began: leaves at the far end of a gust's swing show now and then, a thing that
        // passes shows all the time
        boolean seen = t.hits >= 5 && t.hits >= MIN_SEEN * (frames - t.firstFrame + 1);
        boolean travels = ageS >= MIN_AGE_S[sensitivity] && seen && net >= needed
                && straight >= MIN_STRAIGHT[sensitivity] && steady && !returning && !wake && !shade;
        // or it plainly drives through: far, straight, one way, seen in nearly every picture - early
        boolean drives = ageS >= FAST_AGE_S[sensitivity] && t.hits >= 5 && t.hits >= FAST_SEEN * (frames - t.firstFrame + 1)
                && net >= Math.max(FAST_TRAVEL * w, size) && straight >= FAST_STRAIGHT && steady
                && reversals == 0 && !returning && !wake && !shade;
        // or it goes its way slowly but never back (a long vehicle at walking pace covers half its length in 2 s only
        // later): its way from where it began, in the window's direction - leaves come back at every swing
        float gx = newest[1] - t.ox, gy = newest[2] - t.oy;
        boolean keepsOn = ageS >= MIN_AGE_S[sensitivity] && seen && !t.cameBack && fromOrigin >= needed
                && net >= MIN_TRAVEL[sensitivity] * 0.5f && cos(ux, uy, gx, gy) > 0.8f
                && straight >= MIN_STRAIGHT[sensitivity] && steady && reversals == 0 && !returning && !wake && !shade;
        float maxVeg = MAX_VEGETATION[sensitivity] * (now - warmUntil < YOUNG_MS ? 0.5f : 1);
        // the young background's stricter look is for what might be leaves; a thing that went that far one way isn't -
        // and its own flicker is its passing (a slow vehicle makes its pixels foreground again and again): its look then
        // without the flicker
        float wayLook = VEG_WAY * Math.max(oscillation, 1 - straight) + VEG_TEXTURE * texture;
        if (!t.confirmed && (travels && t.vegetation < maxVeg || (drives || keepsOn) && wayLook < MAX_VEGETATION[sensitivity])) {
            t.confirmed = true;
        }
        // 1.0.56: or it is close to the camera - big, solid, where the picture was calm, not other light, seen nearly
        // all the time: confirmed whichever way it moves (across in a second, or in place)
        float calm = NEAR_CALM * Math.max(0.05f, learnt);
        // not the same ground in other light: its fine detail hidden where it has some, else its brightness uneven
        boolean thing = Float.isNaN(t.detail) ? t.ratioSpread >= NEAR_RATIO_SPREAD : t.detail < NEAR_DETAIL_SHOWS;
        boolean nearNow = !night && !wake && !shade && t.area >= NEAR_AREA * w * h && t.fill >= NEAR_FILL && thing
                && t.startFlicker >= 0 && t.startFlicker <= calm;
        if (!t.confirmed && nearNow && ageS >= NEAR_AGE_S && t.hits >= 4
                && t.hits >= NEAR_SEEN * (frames - t.firstFrame + 1)) {
            t.confirmed = t.near = true;
            nearMade++;
        }
        // a close thing that went far is a proven track like any other (a vehicle turning keeps its box that way)
        if (t.near && t.maxFromOrigin >= PROVEN_TRAVEL * needed) {
            t.near = false;
            t.proven = true;
        }
        if (t.near) {
            // kept while it is seen: it may sway and come back as it likes - only leaves' flicker or a shadow's edge
            // takes it away
            if (wake || VEG_FLICKER * flicker > maxVeg + 0.15f) t.confirmed = t.near = false;
            else t.confirmed = true;
            if (debug) t.why = String.format(java.util.Locale.ROOT, "NEAR area %.0f fill %.2f spread %.2f detail %.2f calm %.3f/%.3f", t.area, t.fill, t.ratioSpread, t.detail, t.startFlicker, calm);
            t.confidence = NEAR_CONFIDENCE;
            return;
        }
        if (t.confirmed && t.maxFromOrigin >= PROVEN_TRAVEL * needed) t.proven = true;
        // HIGH (1.0.58): or it went far one way for seconds without ever coming back - a sentry walking his beat, who
        // stops and turns at its end; leaves swing back within a second or two
        if (sensitivity == HIGH && t.confirmed && !t.cameBack && t.maxFromOrigin >= LEG_FAR * needed && t.farAt - t.first >= LEG_MS) {
            t.proven = true;
        }
        if (t.proven) {
            // it went far: a turn, a loop or a stop doesn't take its box away - only a look of leaves or a shadow's edge
            float look = VEG_FLICKER * flicker + VEG_TEXTURE * texture;
            if (look > maxVeg + 0.15f || wake) t.confirmed = t.proven = false;
            else t.confirmed = true;
        } else if (t.confirmed && ((t.cameBack ? t.vegetation : wayLook) > maxVeg + 0.15f || returning || wake)) {
            t.confirmed = false;
        }
        if (debug) {
            t.why = String.format(java.util.Locale.ROOT, "net %.1f/%.1f straight %.2f steady %b rev %d returning %b wake %b | area %.0f fill %.2f spread %.2f detail %.2f calm %.3f/%.3f",
                    net, needed, straight, steady, reversals, returning, wake, t.area, t.fill, t.ratioSpread, t.detail, t.startFlicker, calm);
        }
        float reach = Math.min(1, net / (2 * needed));
        t.confidence = Math.max(0, Math.min(1, (0.5f * reach + 0.5f * straight) * (1 - 0.5f * t.vegetation)));
    }

    /**
     * How often the picture flickered (freq) in a ring NEAR_RING wide round the blob's box, on average: next to swaying
     * leaves it is high (a gust carries them a little further than before), round a thing in a room it is low.
     */
    private float ringFlicker(Blob b) {
        int x0 = Math.max(0, b.minX - NEAR_RING), x1 = Math.min(w - 1, b.maxX + NEAR_RING);
        int y0 = Math.max(0, b.minY - NEAR_RING), y1 = Math.min(h - 1, b.maxY + NEAR_RING);
        double sum = 0;
        int n = 0;
        for (int yy = y0; yy <= y1; yy++) {
            for (int xx = x0; xx <= x1; xx++) {
                if (xx >= b.minX && xx <= b.maxX && yy >= b.minY && yy <= b.maxY) continue;
                sum += freq[yy * w + xx];
                n++;
            }
        }
        return n == 0 ? 0 : (float) (sum / n);
    }

    /**
     * Whether the track leaves the picture changed behind it: where it was about a second ago (and isn't now) still
     * differs from the slow background (what was there seconds before). Something passing uncovers the ground it hid;
     * the edge of a shadow or of a patch of sun moving over the ground leaves the ground darker or brighter behind it. Undecided (false) while the old place
     * and the new one overlap too much to tell.
     */
    private boolean leavesChange(Track t, long now) {
        shadeBehind = false;
        float[] old = null;
        for (float[] p : t.history) {
            if (rel(now) - p[0] < WAKE_AFTER_MS) break;
            old = p;
        }
        if (old == null) return false;
        float[] last = t.history.get(t.history.size() - 1);
        int behind = 0, still = 0, qn = 0;
        double qs = 0, da = 0, dm = 0, daa = 0, dmm = 0, dam = 0;
        for (int yy = (int) old[4]; yy <= (int) old[6]; yy++) {
            for (int xx = (int) old[3]; xx <= (int) old[5]; xx++) {
                if (xx >= last[3] - 1 && xx <= last[5] + 1 && yy >= last[4] - 1 && yy <= last[6] + 1) continue;
                behind++;
                int i = yy * w + xx;
                if (valid[i] != 0 && Math.abs(aligned[i] - slow[i]) > Math.max(WAKE_MIN_DIFF, 3 * sd[i])) still++;
                if (valid[i] != 0 && !night) {
                    double q = (aligned[i] + 4) / (slow[i] + 4), a = hpAligned[i], m = hpSlow[i];
                    qn++;
                    qs += q;
                    da += a;
                    dm += m;
                    daa += a * a;
                    dmm += m * m;
                    dam += a * m;
                }
            }
        }
        if (behind >= 6 && still > WAKE_CHANGED * behind) return true;
        // 1.0.56: or the ground behind is darker or brighter, its detail what it was - a shadow's edge (the edge passing
        // just raised those pixels' variance, so the test above no longer counts them). Ground uncovered by something
        // passing matches what was there; where someone stood from the start, the wall's detail isn't theirs.
        if (qn < 6 || night) return false;
        double va = daa / qn - (da / qn) * (da / qn), vm = dmm / qn - (dm / qn) * (dm / qn);
        if (vm < NEAR_DETAIL_MIN_SD * NEAR_DETAIL_MIN_SD || va <= 1e-6) return false;
        double corr = (dam / qn - (da / qn) * (dm / qn)) / Math.sqrt(va * vm);
        // (kept apart from the wake: it only keeps a track from being confirmed - behind a vehicle that halted, the slow
        // background half learnt it, and a box it had must not go)
        shadeBehind = Math.abs(qs / qn - 1) >= WAKE_LIGHT_STEP && corr >= WAKE_LIGHT_DETAIL;
        return false;
    }

    /** The last leavesChange found the ground behind in other light, its detail still there (1.0.56). */
    private boolean shadeBehind;
    private static float cos(float ax, float ay, float bx, float by) {
        float n = (float) (Math.hypot(ax, ay) * Math.hypot(bx, by));
        return n <= 1e-6f ? 0 : (ax * bx + ay * by) / n;
    }

    private List<Detection> report(long now) {
        List<Track> shown = new ArrayList<>();
        for (Track t : tracks) {
            float[] p = t.history.get(t.history.size() - 1);
            if (t.confirmed && now - t.lastSeen <= REPORT_COAST_MS && levelAt(p[1], p[2]) != OFF) shown.add(t);
        }
        Collections.sort(shown, (a, b) -> Float.compare(b.confidence, a.confidence));
        List<Detection> out = new ArrayList<>();
        for (int i = 0; i < shown.size() && i < MAX_DETECTIONS; i++) {
            Track t = shown.get(i);
            // a pixel of margin round the blob (it was grown by one to join its pieces)
            float x0 = Math.max(0, t.minX - 1), y0 = Math.max(0, t.minY - 1);
            float x1 = Math.min(w, t.maxX + 2), y1 = Math.min(h, t.maxY + 2);
            out.add(new Detection(t.id, x0 / w, y0 / h, (x1 - x0) / w, (y1 - y0) / h, t.confidence, t.vegetation, t.light));
        }
        // and what comes straight at the camera, unless a track already boxes it
        for (Looming.Loomer l : loom.loomers()) {
            if (!l.confirmed || l.lastSeen != now || out.size() >= MAX_DETECTIONS) continue;
            if (levelAt((l.minX + l.maxX) / 2f, (l.minY + l.maxY) / 2f) == OFF) continue;
            float x0 = Math.max(0, l.minX - 1), y0 = Math.max(0, l.minY - 1);
            float x1 = Math.min(w, l.maxX + 2), y1 = Math.min(h, l.maxY + 2);
            boolean boxed = false;
            for (Detection d : out) {
                if (d.x * w < x1 && (d.x + d.w) * w > x0 && d.y * h < y1 && (d.y + d.h) * h > y0) boxed = true;
            }
            if (!boxed) out.add(new Detection(l.id, x0 / w, y0 / h, (x1 - x0) / w, (y1 - y0) / h, LOOM_CONFIDENCE, 0, false));
        }
        return out.isEmpty() ? Collections.<Detection>emptyList() : out;
    }

    // ------------------------------------------------------------------ the background learns

    /**
     * Mean: 3 s; a foreground pixel's 8 s; a confirmed track's 30 s (it is not learnt into the background while it
     * passes); other light (a shadow, found by otherLight) 1 s. Variance: up in 3 s below the foreground line (the
     * picture's own noise and shimmer), a foreground pixel's in 6 s and by at most 40 grey levels (something passing once
     * leaves little; leaves that sway back and forth all the time build a wide range), a confirmed track's in 30 s, not
     * at all for other light; down in 20 s. How often each pixel is foreground: 20 s. The slow background (what was
     * there before, for leavesChange): 8 s, a foreground pixel's 30 s, settling or not. A picture not judged (settling):
     * the mean and the variance learn in 0.7 s, the variance falls in 3 s.
     */
    private void learn(float dt, List<Blob> blobs, boolean fast) {
        float aMean = fast ? rate(dt, 0.7f) : rate(dt, 3);
        float aMeanBlob = rate(dt, 8), aMeanTrack = rate(dt, 30), aMeanLight = rate(dt, 1);
        float aUp = fast ? rate(dt, 0.7f) : rate(dt, 3), aUpFg = rate(dt, 6), aUpTrack = rate(dt, 30);
        float aDown = fast ? rate(dt, 3) : rate(dt, 20);
        float aFreq = rate(dt, 20);
        float aSlow = rate(dt, 8), aSlowFg = rate(dt, 30);
        boolean[] confirmed = null;
        if (blobs != null && !blobs.isEmpty()) {
            int maxLabel = 0;
            for (Blob b : blobs) maxLabel = Math.max(maxLabel, b.label);
            confirmed = new boolean[maxLabel + 1];
            for (Blob b : blobs) confirmed[b.label] = b.track != null && b.track.confirmed;
        }
        for (int i = 0; i < w * h; i++) {
            if (valid[i] == 0) continue;
            float d = aligned[i] - mean[i];
            boolean fg = !fast && raw[i] != 0;
            boolean light = !fast && raw[i] == 0 && changed[i] != 0;
            boolean inTrack = fg && labels[i] != 0 && confirmed != null && labels[i] < confirmed.length && confirmed[labels[i]];
            float am = fast ? aMean : inTrack ? aMeanTrack : fg ? aMeanBlob : light ? aMeanLight : aMean;
            mean[i] += am * d;
            float d2 = Math.min(d * d, fg ? 40 * 40 : 60 * 60);
            if (d2 > var[i]) {
                if (!light) var[i] += (inTrack ? aUpTrack : fg ? aUpFg : aUp) * (d2 - var[i]);
            } else {
                var[i] += aDown * (d2 - var[i]);
            }
            // (HIGH, 1.0.58: a confirmed thing standing there is not flicker - a sentry at the end of his beat)
            boolean flick = raw[i] != 0 && !(inTrack && levelAt[i] == HIGH);
            if (!fast) freq[i] += aFreq * ((flick ? 1 : 0) - freq[i]);
            slow[i] += (fg ? aSlowFg : aSlow) * (aligned[i] - slow[i]);
        }
        if (fineNow) learnFine(fast, confirmed, aMean, aMeanBlob, aMeanTrack, aMeanLight, aUp, aUpFg, aUpTrack, aDown);
    }

    /**
     * The fine background learns as the working one (1.0.58), a pixel foreground when it or its working pixel is, but
     * its variance grows at ONE_OFF_GROWTH from a one-off excursion: someone passing once leaves it as it was, and the
     * next one passing there - the next man of a patrol, the sentry coming back - is still seen. An excursion again
     * within ONE_OFF_REPEAT_MS (leaves), or one that lasts, grows it as before.
     */
    private void learnFine(boolean fast, boolean[] confirmed, float aMean, float aMeanBlob, float aMeanTrack, float aMeanLight,
                           float aUp, float aUpFg, float aUpTrack, float aDown) {
        long now = lastMs;
        for (int yy = 0; yy < fh; yy++) {
            for (int xx = 0; xx < fw; xx++) {
                int i = yy * fw + xx;
                if (fvalid[i] == 0) continue;
                int parent = (yy / 2) * w + xx / 2;
                float d = faligned[i] - fmean[i];
                boolean fg = !fast && (fraw[i] != 0 || raw[parent] != 0);
                boolean light = !fast && !fg && fchanged[i] != 0;
                boolean inTrack = fg && labels[parent] != 0 && confirmed != null && labels[parent] < confirmed.length && confirmed[labels[parent]];
                float am = fast ? aMean : inTrack ? aMeanTrack : fg ? aMeanBlob : light ? aMeanLight : aMean;
                fmean[i] += am * d;
                float growth = 1;
                if (!fast) {
                    if (Math.abs(d) > EXCURSION_K * Math.max(2.5f, (float) Math.sqrt(fvar[i]))) {
                        if (inExc[i] == 0) {
                            inExc[i] = 1;
                            excAgain[i] = (byte) (now - excAt[i] < ONE_OFF_REPEAT_MS ? 1 : 0);
                            excAt[i] = now;
                            excSince[i] = now;
                        }
                        if (excAgain[i] == 0 && now - excSince[i] <= ONE_OFF_LASTS_MS) growth = ONE_OFF_GROWTH;
                    } else {
                        inExc[i] = 0;
                    }
                }
                float d2 = Math.min(d * d, fg ? 40 * 40 : 60 * 60);
                if (d2 > fvar[i]) {
                    if (!light) fvar[i] += growth * (inTrack ? aUpTrack : fg ? aUpFg : aUp) * (d2 - fvar[i]);
                } else {
                    fvar[i] += aDown * (d2 - fvar[i]);
                }
            }
        }
    }
    private static float rate(float dt, float tauS) {
        return (float) (1 - Math.exp(-dt / tauS));
    }
}
