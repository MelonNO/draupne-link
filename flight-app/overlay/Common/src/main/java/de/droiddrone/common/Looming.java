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
import java.util.List;

/**
 * Something coming straight at the camera (DRAUPNE Link 1.0.53, the pilot: "a person moving from inside the woods
 * directly towards the camera"): it hardly moves in the picture - it grows. {@link MotionDetector}'s background learns
 * a thing that stays in one place within seconds, and its tracks must travel, so it never saw one. This is a second,
 * slow look at the same aligned pictures (the detector's shake and exposure taken out), by brightness alone:
 * <ol>
 * <li>A reference picture that learns slowly ({@link #REF_TAU_S}) and not at all where a pixel is steadily changed.</li>
 * <li>Its noise per pixel: the detector's (which learns leaves' sway in seconds) while nothing stands there, then kept
 * as it was - the detector learns a thing standing still as noise; this must not.</li>
 * <li>A pixel is steadily changed when it differs from its reference by k standard deviations, a margin and what
 * the shake's leftover fraction of a pixel makes of the reference's slope there, for most of the last second or two
 * ({@link #PERSIST_ON}), and isn't the same ground in other light (its 3 x 3 detail follows the reference's). (Not the
 * detector's every-pixel-round-it test: a slim person by a dark trunk always has a neighbour as dark.)</li>
 * <li>Blobs of steady change are followed; one counts when over {@link #WINDOW_MS} it grew steadily - taller and wider,
 * its feet not rising, no sideways back and forth, staying where it is next to its size, compact, not where the
 * detector's flicker map says leaves are.</li>
 * </ol>
 * Something left standing becomes the reference after {@link #ABSORB_S}. Day only. Not thread safe (the detector's
 * thread).
 *
 * <p>pc.50, someone coming slowly: the same growth is also looked for over {@link #SLOW_WINDOW_MS}, and what has a
 * thing's shape, moves within itself and has not shrunk is not made the reference for up to {@link #SLOW_MAX_AGE_MS}
 * (LOW and NORMAL only).
 */
final class Looming {
    /** The reference learns in this (s) where nothing stands. */
    static final float REF_TAU_S = 60;
    /** How steadily a pixel was changed: averaged over this (s). */
    static final float PERSIST_TAU_S = 1.2f;
    /** Changed this share of the time: steadily changed; under REF_FREE the reference learns it. */
    static final float PERSIST_ON = 0.7f, REF_FREE = 0.3f;
    /**
     * The noise allowed per pixel is the detector's standard deviation (leaves' sway learnt in seconds) while the pixel
     * is free (steadiness under this), then kept: the detector learns a thing that stands as noise within seconds - this
     * must not.
     */
    static final float VAR_FREE = 0.1f;
    /**
     * The detector's noise falls back in a calm (20 s): the next gust's leaves would look new. The most it was fades
     * over this (s), and this share of it stays the least allowed.
     */
    static final float MEMORY_TAU_S = 900, MEMORY_SHARE = 0.8f, MEMORY_RISE_S = 10;
    /** ... the most of it within this many pixels: the next gust carries leaves a little further. */
    static final int MEMORY_REACH = 1;
    /** ... worked out every this many pictures. */
    static final int LEAFY_EVERY = 8;
    /** The detector's noise at an edge from the shake: about this times the edge's slope (grey levels a pixel). */
    static final float EDGE_NOISE = 0.7f;
    /** ... free: nothing within this many pixels steadily changed either. Also a blob's reach: what changed now this near its steady core is its extent. */
    static final int FREEZE_REACH = 2;
    /** Steadily changed this long: it stays - the reference takes it. */
    static final float ABSORB_S = 30;
    /** The shake's alignment is good to about this (working pixels): an edge's slope times it is no change. */
    static final float ALIGN_ERROR = 0.35f;
    /** Standard deviations and grey levels from the reference, by sensitivity (LOW..HIGH, 0 unused). */
    static final float[] K_SIGMA = {0, 2.5f, 2.2f, 2f};
    static final float[] MARGIN = {0, 9, 7, 5};
    /** The window the growth is judged over, and the youngest a loomer can count. */
    static final long WINDOW_MS = 6000, MIN_AGE_MS = 4000;
    /** Grown by this factor in height over the window at least (and by GROWTH_MIN_PX working pixels), by sensitivity. */
    static final float[] GROWTH = {0, 1.35f, 1.25f, 1.18f};
    static final float GROWTH_MIN_PX = 2;
    /** At least this tall when it counts (working pixels, a person about 30 m away at 90 degrees): a gust's speck isn't. */
    static final float MIN_HEIGHT = 6;
    /** Its height grew in two of the window's three steps at least, none more than this share of all of it. */
    static final float MAX_STEP_SHARE = 0.6f;
    /** At most this share of the picture wide, and at least this tall for its width. */
    static final float MAX_WIDTH_SHARE = 0.2f, MIN_ASPECT = 0.6f;
    /** Its area by this factor at least (it grows both ways). */
    static final float AREA_GROWTH = 1.35f;
    /** Moved sideways at most this many times its height over the window (it comes nearer; at an angle a little across). */
    static final float MAX_SIDEWAYS = 1.0f;
    /** The detector's flicker map round it over this: leaves. */
    static final float MAX_FLICKER = 0.12f;
    /** Its pixels at least this share of its box. */
    static final float MIN_FILL = 0.35f;
    /** Fewest pixels of a blob. */
    static final int MIN_AREA = 3;
    /**
     * It must grow within this long of being seen first: someone walking in grows from the moment they appear (and what
     * stands longer becomes the reference at ABSORB_S); a patch the wind has kept changed for minutes is no newcomer.
     */
    static final long MAX_AGE_MS = 45_000;
    /**
     * pc.50, someone coming slowly (0.5 - 0.7 m/s: a wary walk): the window's growth is reached only some 15 m off,
     * by which time the reference had taken him (ABSORB_S after his first steady pixel) and nothing was left to grow -
     * he was found 10 - 12 m off, or under 8. A second, slow look: the same growth over this long a window, judged on
     * his box every SLOW_STEP_MS, and stricter (no quarter of it shorter than the one before).
     */
    static final long SLOW_WINDOW_MS = 18_000, SLOW_STEP_MS = 500;
    /**
     * pc.50: a loomer with a person's or a vehicle's shape, calm ground round it, whose height has not fallen over the
     * slow look (at least SLOW_KEEP_MS of it; before that, any loomer) is not taken by the reference, and the slow
     * look may still count it, until it is this old: someone at 0.5 m/s needs over a minute from where he first shows
     * (40 m) to where he has grown enough (20 m). What stops being so is taken SLOW_LET_GO_S later. LOW and NORMAL only.
     */
    static final long SLOW_MAX_AGE_MS = 120_000, SLOW_KEEP_MS = 4000;
    static final float SLOW_LET_GO_S = 5;
    /** ... and moving within itself: its pixels change from picture to picture by this share of what they differ from the reference by. */
    static final float SLOW_ALIVE = 0.1f;
    /** After a restart nothing counts for this long: what the reference took in the middle of a gust settles. */
    static final long SETTLE_MS = 10_000;
    /** A loomer not matched for this long ends. */
    static final long LOST_MS = 1500;

    /** A blob of steady change followed over time. */
    static final class Loomer {
        final int id;
        final long first;
        long lastSeen;
        int hits, minX, minY, maxX, maxY, count;
        float flicker;
        /** How much its pixels change from one picture to the next against how far they are from the reference, averaged. */
        float shimmer;
        boolean confirmed;
        /** time ms (from the detector's epoch), min x, min y, max x, max y, count; oldest first, WINDOW_MS + 1 s kept. */
        final ArrayList<float[]> history = new ArrayList<>();
        /** pc.50: the same every SLOW_STEP_MS, SLOW_WINDOW_MS kept: the slow look's. */
        final ArrayList<float[]> slow = new ArrayList<>();
        /** pc.50: shaped like a thing, calm round it, its height not falling: the reference leaves it be (SLOW_MAX_AGE_MS). */
        boolean keeps;
        /** pc.50: the slow look has a new box since it was last judged. */
        boolean slowDue;
        String why = "", slowWhy = "";

        Loomer(int id, long now) {
            this.id = id;
            this.first = now;
        }
    }

    interface Ids {
        int next();
    }

    private final int w, h;
    /** The detector's sensitivity per pixel (1.0.58), as the last update gave it. */
    private byte[] levels;
    private final float[] ref, allow, memory, leafy, persist, held, around, tmp, prev;
    private final byte[] on, wasOn, mask;
    private final int[] labels, stack;
    private final Ids ids;
    private final ArrayList<Loomer> loomers = new ArrayList<>();
    /** Pictures judged, and how many each loomer was seen in. */
    private long frames;
    boolean debug;

    Looming(int w, int h, Ids ids) {
        this.w = w;
        this.h = h;
        this.ids = ids;
        int n = w * h;
        ref = new float[n];
        allow = new float[n];
        memory = new float[n];
        leafy = new float[n];
        persist = new float[n];
        held = new float[n];
        around = new float[n];
        tmp = new float[n];
        prev = new float[n];
        on = new byte[n];
        wasOn = new byte[n];
        mask = new byte[n];
        labels = new int[n];
        stack = new int[n];
    }

    /** Restarted: the next judged picture's time starts the settling. */
    private boolean restarted = true;
    private long settledAt;

    /** The reference starts again from this picture. */
    void restart(float[] picture) {
        System.arraycopy(picture, 0, ref, 0, w * h);
        System.arraycopy(picture, 0, prev, 0, w * h);
        java.util.Arrays.fill(allow, 3); // (the memory of where leaves are stays: a restart is mostly the wind's doing)
        restarted = true;
        java.util.Arrays.fill(persist, 0);
        java.util.Arrays.fill(around, 0);
        java.util.Arrays.fill(held, 0);
        java.util.Arrays.fill(wasOn, (byte) 0);
        loomers.clear();
    }

    /** The camera's exposure stepped: the reference takes it (the picture was divided by gain to match). */
    void exposure(float gain) {
        for (int i = 0; i < w * h; i++) ref[i] /= gain;
    }

    /** A picture not judged (changed as a whole): the loomers' way so far spans it - they start again. */
    void unsettled() {
        loomers.clear();
    }

    List<Loomer> loomers() {
        return loomers;
    }

    /**
     * One judged picture.
     * @param aligned the picture, the shake taken out and the exposure matched (the detector's)
     * @param valid   where it is (1) or off the edge after the shift
     * @param sd      the detector's standard deviation per pixel (its background's: leaves' sway learnt in seconds)
     * @param near    the detector's flicker map (how often round each pixel was foreground lately)
     * @param levels  each pixel's sensitivity (1.0.58: the detector's areas; 0 off)
     */
    void update(float[] aligned, byte[] valid, float[] sd, float[] near, float dt, long now, float relNow, byte[] levels) {
        this.levels = levels;
        frames++;
        if (restarted) {
            restarted = false;
            settledAt = now + SETTLE_MS;
        }
        float aRef = rate(dt, REF_TAU_S), aPersist = rate(dt, PERSIST_TAU_S);
        float fade = (float) Math.exp(-dt / MEMORY_TAU_S), rise = rate(dt, MEMORY_RISE_S);
        // around: the most any pixel within FREEZE_REACH was steadily changed (the last picture's, made at its end): a
        // thing grows from where it stands, so the pixels round it keep their noise and reference before its edge
        // covers them a little at a time
        // leaves that swayed in the last minutes stay leaves through a calm, and the ground next to them is where the
        // next strong gust carries them: the most the detector's noise was, fading, the most of it round each pixel
        // (not where something stands: the detector learns it as noise, and the memory would keep that for minutes)
        // (it rises in MEMORY_RISE_S: leaves keep the detector's noise up for many seconds, a shake's moment at an edge doesn't)
        // (and only the noise an edge's shake doesn't explain: a trunk's edge is noisy in the detector all day)
        for (int yy = 1; yy < h - 1; yy++) {
            for (int xx = 1; xx < w - 1; xx++) {
                int i = yy * w + xx;
                float slope = Math.max(Math.abs(ref[i + 1] - ref[i - 1]), Math.abs(ref[i + w] - ref[i - w])) / 2;
                float leaves = sd[i] - EDGE_NOISE * slope;
                if (around[i] < VAR_FREE && leaves > memory[i]) memory[i] += rise * (leaves - memory[i]);
                else memory[i] *= fade;
            }
        }
        if (frames % LEAFY_EVERY == 1) nearMax(memory, leafy, MEMORY_REACH); // it changes over minutes
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int i = yy * w + xx;
                on[i] = 0;
                if (valid[i] == 0 || xx == 0 || yy == 0 || xx == w - 1 || yy == h - 1) {
                    mask[i] = 0;
                    continue;
                }
                float a = aligned[i];
                // what the shake left over moves an edge by a fraction of a pixel: the reference's slope times that
                float slope = Math.max(Math.abs(ref[i + 1] - ref[i - 1]), Math.abs(ref[i + w] - ref[i - w])) / 2;
                int lv = levels[i]; // its area's sensitivity: never on in an OFF area
                float score = lv == 0 ? -1 : Math.abs(a - ref[i]) - K_SIGMA[lv] * allow[i] - MARGIN[lv] - ALIGN_ERROR * slope;
                if (score > 0 && !sameGround(aligned, i)) on[i] = 1;
                persist[i] += aPersist * (on[i] - persist[i]);
                // the reference's noise: from pixels that come and go (a swing), never from one that stays changed
                // the noise: the detector's while nothing stands here; kept as it was from the moment something does
                if (around[i] < VAR_FREE) allow[i] = Math.max(sd[i], MEMORY_SHARE * leafy[i]);
                float d = a - ref[i];
                if (around[i] < REF_FREE) {
                    ref[i] += aRef * d;
                    held[i] = 0;
                } else if ((held[i] += dt) > ABSORB_S) { // it stays: the reference takes it
                    ref[i] = a;
                    persist[i] = 0;
                    held[i] = 0;
                }
            }
        }
        // the steady core says something stands there; its extent is what changed now round it: the rows a growing
        // thing just covered take a second to be steady, and its height would lag behind its growth
        nearMax(persist, around, FREEZE_REACH); // (the same reach: the next picture's freeze uses it too)
        for (int i = 0; i < w * h; i++) {
            mask[i] = (byte) (persist[i] >= PERSIST_ON ? 2 : on[i] != 0 && around[i] >= PERSIST_ON ? 1 : 0);
        }
        follow(blobs(aligned, near), now, relNow);
        // pc.50: what keeps a thing's shape and its height (someone coming slowly) the reference does not take yet
        for (Loomer l : loomers) {
            if (!l.keeps || l.lastSeen != now) continue;
            float most = ABSORB_S - SLOW_LET_GO_S;
            for (int yy = Math.max(0, l.minY - FREEZE_REACH); yy <= Math.min(h - 1, l.maxY + FREEZE_REACH); yy++) {
                for (int xx = Math.max(0, l.minX - FREEZE_REACH); xx <= Math.min(w - 1, l.maxX + FREEZE_REACH); xx++) {
                    if (held[yy * w + xx] > most) held[yy * w + xx] = most;
                }
            }
        }
        System.arraycopy(aligned, 0, prev, 0, w * h);
    }

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

    /** The same ground in other light: the 3 x 3 detail round i follows the reference's closely (correlation over 0.8). */
    private boolean sameGround(float[] a, int i) {
        float sa = 0, sm = 0, saa = 0, smm = 0, sam = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int row = i + dy * w;
            for (int dx = -1; dx <= 1; dx++) {
                float x = a[row + dx], m = ref[row + dx];
                sa += x;
                sm += m;
                saa += x * x;
                smm += m * m;
                sam += x * m;
            }
        }
        float va = saa / 9 - (sa / 9) * (sa / 9), vm = smm / 9 - (sm / 9) * (sm / 9);
        if (vm < 9 || va < 2.25f) return false;
        float cov = sam / 9 - (sa / 9) * (sm / 9);
        return cov / (float) Math.sqrt(va * vm) > 0.8f;
    }

    /** Blobs of the steady-change mask: lone pixels dropped, grown by one (a trunk in front splits a person), joined. */
    private List<int[]> blobs(float[] a, float[] near) {
        // {minX, minY, maxX, maxY, count, flicker x 1000, shimmer x 1000}
        List<int[]> found = new ArrayList<>();
        java.util.Arrays.fill(labels, 0);
        int label = 0;
        for (int start = 0; start < w * h; start++) {
            if (mask[start] == 0 || labels[start] != 0) continue;
            label++;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1, count = 0, core = 0;
            float flick = 0, moved = 0, off = 0;
            int top = 0;
            stack[top++] = start;
            labels[start] = label;
            while (top > 0) {
                int i = stack[--top];
                int xx = i % w, yy = i / w;
                count++;
                if (mask[i] == 2) core++;
                flick += near[i];
                moved += Math.abs(a[i] - prev[i]);
                off += Math.abs(a[i] - ref[i]);
                if (xx < minX) minX = xx;
                if (xx > maxX) maxX = xx;
                if (yy < minY) minY = yy;
                if (yy > maxY) maxY = yy;
                // neighbours up to two away: pieces a pixel apart (a trunk in front, legs) are one thing
                for (int dy = -2; dy <= 2; dy++) {
                    int ny = yy + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -2; dx <= 2; dx++) {
                        int nx = xx + dx;
                        if (nx < 0 || nx >= w) continue;
                        int n = ny * w + nx;
                        if (mask[n] != 0 && labels[n] == 0) {
                            labels[n] = label;
                            stack[top++] = n;
                        }
                    }
                }
            }
            if (core < MIN_AREA || count > w * h / 8) continue;
            found.add(new int[]{minX, minY, maxX, maxY, count, Math.round(1000 * flick / count), Math.round(1000 * moved / Math.max(1, off))});
        }
        return found;
    }

    private void follow(List<int[]> blobs, long now, float relNow) {
        boolean[] taken = new boolean[blobs.size()];
        for (Loomer l : loomers) {
            // the blob that overlaps its last box most (grown by 2)
            int best = -1, bestOverlap = 0;
            for (int b = 0; b < blobs.size(); b++) {
                if (taken[b]) continue;
                int[] o = blobs.get(b);
                int ox = Math.min(l.maxX + 2, o[2]) - Math.max(l.minX - 2, o[0]) + 1;
                int oy = Math.min(l.maxY + 2, o[3]) - Math.max(l.minY - 2, o[1]) + 1;
                if (ox <= 0 || oy <= 0) continue;
                if (ox * oy > bestOverlap) {
                    bestOverlap = ox * oy;
                    best = b;
                }
            }
            if (best < 0) continue;
            taken[best] = true;
            update(l, blobs.get(best), now, relNow);
        }
        for (int b = 0; b < blobs.size(); b++) {
            if (taken[b]) continue;
            Loomer l = new Loomer(ids.next(), now);
            loomers.add(l);
            update(l, blobs.get(b), now, relNow);
        }
        for (int i = loomers.size() - 1; i >= 0; i--) {
            Loomer l = loomers.get(i);
            if (now - l.lastSeen > LOST_MS) loomers.remove(i);
            else if (l.lastSeen == now) judge(l, now, relNow, Math.max(1, levels[Math.min(h - 1, (l.minY + l.maxY) / 2) * w + Math.min(w - 1, (l.minX + l.maxX) / 2)]));
        }
        while (loomers.size() > 40) loomers.remove(0); // a busy picture: the oldest go
    }

    private void update(Loomer l, int[] b, long now, float relNow) {
        l.lastSeen = now;
        l.hits++;
        l.minX = b[0];
        l.minY = b[1];
        l.maxX = b[2];
        l.maxY = b[3];
        l.count = b[4];
        l.flicker += (l.hits == 1 ? 1 : 0.2f) * (b[5] / 1000f - l.flicker);
        l.shimmer += (l.hits == 1 ? 1 : 0.2f) * (b[6] / 1000f - l.shimmer);
        float[] p = {relNow, b[0], b[1], b[2], b[3], b[4]};
        l.history.add(p);
        while (l.history.size() > 2 && relNow - l.history.get(0)[0] > WINDOW_MS + 1000) l.history.remove(0);
        if (l.slow.isEmpty() || relNow - l.slow.get(l.slow.size() - 1)[0] >= SLOW_STEP_MS) { // pc.50: the slow look's
            l.slow.add(p);
            l.slowDue = true;
        }
        while (l.slow.size() > 2 && relNow - l.slow.get(0)[0] > SLOW_WINDOW_MS) l.slow.remove(0);
    }

    /** Whether it grew steadily where it stands over the window: confirms it, or takes it back. */
    private void judge(Loomer l, long now, float relNow, int sensitivity) {
        List<float[]> win = new ArrayList<>();
        for (float[] p : l.history) if (relNow - p[0] <= WINDOW_MS) win.add(p);
        int n = win.size();
        boolean grows = false;
        String why = "";
        if (now - l.first >= MIN_AGE_MS && n >= 8 && l.hits >= 0.8f * Math.min(frames, n)) {
            grows = grew(l, win, sensitivity, false);
            why = grewWhy;
        }
        // pc.50, the slow look (not on HIGH: in a gale its patches of leaves, kept this way, grew into alarms): while it
        // has a thing's shape, moves within itself and its height has not fallen, the reference leaves it be - as it
        // does a loomer just begun (its first steady pixel may be half a minute old, far off, before it was one) - and
        // the window's growth over SLOW_WINDOW_MS counts too
        boolean slowly = false;
        int m = l.slow.size();
        float span = m == 0 ? 0 : relNow - l.slow.get(0)[0];
        if (sensitivity == MotionDetector.HIGH || now - l.first > SLOW_MAX_AGE_MS) {
            l.keeps = false;
        } else if (m < 8 || span < SLOW_KEEP_MS) {
            l.keeps = now - l.first <= SLOW_KEEP_MS + 1000;
        } else if (l.slowDue) { // (judged when the slow look has a new box: twice a second)
            boolean grown = grew(l, l.slow, sensitivity, true);
            slowly = grown && l.keeps && span >= 0.9f * SLOW_WINDOW_MS && m >= 0.8f * SLOW_WINDOW_MS / SLOW_STEP_MS;
            if (debug) l.slowWhy = " SLOW " + grewWhy + (l.keeps ? "keeps" : "");
        }
        l.slowDue = false;
        if (debug) why += l.slowWhy;
        if (now >= settledAt && (grows && now - l.first <= MAX_AGE_MS || slowly)) l.confirmed = true;
        // a confirmed loomer stays while it is there and isn't leaves (it may stop growing as it stops)
        else if (l.confirmed && l.flicker > MAX_FLICKER * 1.5f) l.confirmed = false;
        l.why = why;
    }

    /** The last grew's figures (debug). */
    private String grewWhy = "";

    /**
     * Whether the loomer's boxes (oldest first) grew steadily where it stands. slow (pc.50): the slow look's boxes -
     * no quarter shorter than the one before, its area only not smaller (far off he is a line one pixel wide, and that
     * grows in length alone) - and whether the reference leaves it be (keeps).
     */
    private boolean grew(Loomer l, List<float[]> win, int sensitivity, boolean slow) {
        int n = win.size();
        // quarters of the window: their median heights, widths, areas
        float[] hq = new float[4], wq = new float[4], aq = new float[4], cx = new float[4], bottom = new float[4];
        for (int q = 0; q < 4; q++) {
            List<float[]> part = win.subList(q * n / 4, Math.max(q * n / 4 + 1, (q + 1) * n / 4));
            hq[q] = median(part, 4, 2, 1);
            wq[q] = median(part, 3, 1, 1);
            aq[q] = median(part, 5, -1, 0);
            cx[q] = (median(part, 1, -1, 0) + median(part, 3, -1, 0)) / 2;
            bottom[q] = median(part, 4, -1, 0);
        }
        float slack = slow ? 1 : 0.95f;
        boolean steady = hq[1] >= slack * hq[0] && hq[2] >= slack * hq[1] && hq[3] >= slack * hq[2];
        // smoothly: a thing coming nearer grows as one over its distance - a little each quarter; leaves a gust
        // carries over new ground jump once
        float s1 = hq[1] - hq[0], s2 = hq[2] - hq[1], s3 = hq[3] - hq[2], total = hq[3] - hq[0];
        int rising = (s1 > 0 ? 1 : 0) + (s2 > 0 ? 1 : 0) + (s3 > 0 ? 1 : 0);
        boolean smooth = rising >= 2 && Math.max(s1, Math.max(s2, s3)) <= MAX_STEP_SHARE * total;
        // a person's or a vehicle's shape, not a swathe of the picture
        boolean shaped = l.maxX - l.minX + 1 <= w * MAX_WIDTH_SHARE && hq[3] >= MIN_ASPECT * wq[3];
        boolean taller = hq[3] >= GROWTH[sensitivity] * hq[0] && hq[3] - hq[0] >= GROWTH_MIN_PX && hq[3] >= MIN_HEIGHT;
        boolean wider = wq[3] >= wq[0];
        boolean bigger = aq[3] >= (slow ? 1 : AREA_GROWTH) * aq[0];
        boolean feet = bottom[3] >= bottom[0] - 1;
        float sideways = Math.abs(cx[3] - cx[0]);
        boolean stays = sideways <= Math.max(2, MAX_SIDEWAYS * hq[3]);
        // no sideways back and forth: the quarters' centres go one way
        float d1 = cx[1] - cx[0], d2 = cx[2] - cx[1], d3 = cx[3] - cx[2];
        boolean oneWay = !(d1 * d2 < -1 || d2 * d3 < -1);
        float box = (l.maxX - l.minX + 1) * (l.maxY - l.minY + 1);
        boolean compact = l.count >= MIN_FILL * box;
        boolean calm = l.flicker <= MAX_FLICKER;
        // (alive: a walker's legs and arms move within him; a thing that stands, or a stale patch at the picture's edge, is still)
        if (slow) l.keeps = steady && shaped && feet && stays && compact && calm && l.shimmer >= SLOW_ALIVE;
        if (debug) {
            grewWhy = String.format(java.util.Locale.ROOT, "h %.0f>%.0f>%.0f>%.0f w %.0f>%.0f a %.0f>%.0f side %.1f flick %.2f shim %.2f fill %.2f | %s%s%s%s%s%s%s%s%s%s%s",
                    hq[0], hq[1], hq[2], hq[3], wq[0], wq[3], aq[0], aq[3], sideways, l.flicker, l.shimmer, l.count / box,
                    steady ? "" : "!steady ", smooth ? "" : "!smooth ", shaped ? "" : "!shaped ", taller ? "" : "!taller ", wider ? "" : "!wider ", bigger ? "" : "!bigger ",
                    feet ? "" : "!feet ", stays ? "" : "!stays ", oneWay ? "" : "!oneway ", compact ? "" : "!compact ", calm ? "" : "!calm");
        }
        return steady && smooth && shaped && taller && wider && bigger && feet && stays && oneWay && compact && calm;
    }

    /** The median of p[from] - p[to] + add over the points (to < 0: p[from] alone). */
    private static float median(List<float[]> pts, int from, int to, float add) {
        float[] v = new float[pts.size()];
        for (int i = 0; i < v.length; i++) v[i] = pts.get(i)[from] - (to < 0 ? 0 : pts.get(i)[to]) + add;
        java.util.Arrays.sort(v);
        return v[v.length / 2];
    }

    String describe() {
        StringBuilder s = new StringBuilder();
        for (Loomer l : loomers) {
            s.append(String.format(java.util.Locale.ROOT, "L%d hits %d box %d,%d-%d,%d n %d %s %s%n", l.id, l.hits, l.minX, l.minY,
                    l.maxX, l.maxY, l.count, l.confirmed ? "CONFIRMED" : "", l.why));
        }
        return s.toString();
    }

    private static float rate(float dt, float tauS) {
        return (float) (1 - Math.exp(-dt / tauS));
    }
}
