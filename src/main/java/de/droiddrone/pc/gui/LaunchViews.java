package de.droiddrone.pc.gui;

import de.droiddrone.pc.launch.MiniViews;
import de.droiddrone.pc.observe.ObserverLink;
import de.droiddrone.pc.observe.TileFeed;
import de.droiddrone.pc.video.VideoPipeline;

import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Fleet PRD AL-20 / AL-21: the launching reserves' own cameras for the pilot's corner. For each mini-view with a live
 * picture (MiniViews: once the reserve has woken and connected, until it is over the floor) an observation feed of its
 * own is opened - 480x270 at 15 fps, the observation's small stream; a failed launch keeps its last picture. FD-7: the
 * stream comes from the reserve's own phone over its own uplink - the piloted drone's uplink, and so its video tier, is
 * not touched; this PC's downlink takes about 0.6 Mbit/s more. The phone allows it while its launch runs, to its
 * commander only (ObserveGate's launch view). Off the stick and video paths: frames land in a picture of their own.
 */
final class LaunchViews {
    static final ObserverLink.Quality MINI = new ObserverLink.Quality(MiniViews.WIDTH, MiniViews.HEIGHT, MiniViews.FPS, 600);

    /** One reserve's feed and its newest picture. */
    private static final class Feed implements VideoPipeline.FrameSink {
        final TileFeed tile;
        volatile BufferedImage picture;
        private int[] buffer;
        private int w, h;

        Feed(String phone, InetSocketAddress at, String key) throws java.io.IOException {
            tile = new TileFeed(phone, at.getAddress(), at.getPort(), key, this, null, MINI, msg -> { });
            tile.link().setQuality(MINI);
        }

        public void videoStarted(int width, int height, boolean mirror) {
        }

        public int[] targetSize() {
            return new int[]{MiniViews.WIDTH, MiniViews.HEIGHT};
        }

        public int[] beginFrame(int width, int height) {
            if (buffer == null || w != width || h != height) {
                buffer = new int[width * height];
                w = width;
                h = height;
            }
            return buffer;
        }

        public void presentFrame() {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, w, h, buffer, 0, w);
            picture = img;
        }

        void close() {
            tile.stopStream();
            tile.release();
        }
    }

    private final Map<String, Feed> feeds = new HashMap<>();
    private final Map<String, BufferedImage> last = new HashMap<>();

    /**
     * The views now, with their pictures: feeds opened for new live views, closed for views gone or failed (the last
     * picture kept for a failed one's 10 s).
     */
    List<OsdFleet.Mini> update(List<MiniViews.View> views, Function<String, InetSocketAddress> addressOf, String key) {
        List<OsdFleet.Mini> out = new ArrayList<>();
        Set<String> live = new HashSet<>();
        for (MiniViews.View v : views) {
            if (v.livePicture()) {
                live.add(v.name());
                Feed f = feeds.get(v.name());
                if (f == null) {
                    InetSocketAddress at = addressOf.apply(v.name());
                    if (at != null) {
                        try {
                            f = new Feed(v.name(), at, key);
                            feeds.put(v.name(), f);
                        } catch (java.io.IOException | RuntimeException e) {
                            f = null; // no picture: the view shows its words and bar on a dark box
                        }
                    }
                }
                BufferedImage p = f == null ? null : f.picture;
                if (p != null) last.put(v.name(), p);
                out.add(new OsdFleet.Mini(v.name(), p, v.word(), v.heightM(), v.floorM(), false));
            } else {
                out.add(new OsdFleet.Mini(v.name(), last.get(v.name()), v.word(), v.heightM(), v.floorM(), v.failed()));
            }
        }
        feeds.entrySet().removeIf(e -> {
            if (live.contains(e.getKey())) return false;
            e.getValue().close();
            return true;
        });
        Set<String> shown = new HashSet<>();
        for (MiniViews.View v : views) shown.add(v.name());
        last.keySet().removeIf(n -> !shown.contains(n));
        return out;
    }

    void close() {
        for (Feed f : feeds.values()) f.close();
        feeds.clear();
        last.clear();
    }
}
