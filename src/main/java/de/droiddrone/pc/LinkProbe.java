package de.droiddrone.pc;

import de.droiddrone.pc.video.VideoPipeline;

import java.util.Locale;

/**
 * `probe <seconds> [auto|sharp|robust]`: connects to the phone without a window or sticks, decodes the video into
 * nothing, and prints the link and video figures once a second (and writes a link log) - for comparing video settings on
 * a real link without anyone at the PC. Never on a drone that flies: no sticks are sent, so it fails safe.
 */
final class LinkProbe {
    private LinkProbe() {
    }

    static void run(PcConfig config, int seconds, String mode) throws Exception {
        if (mode != null) config.set(ControlLink.VIDEO_MODE_KEY, mode);
        long[] shown = {0};
        VideoPipeline.FrameSink sink = new VideoPipeline.FrameSink() {
            private final int[] pixels = new int[320 * 180];

            @Override
            public void videoStarted(int width, int height, boolean mirror) {
            }

            @Override
            public int[] targetSize() {
                return new int[]{320, 180};
            }

            @Override
            public int[] beginFrame(int width, int height) {
                return pixels;
            }

            @Override
            public void presentFrame() {
                shown[0]++;
            }
        };
        ControlLink[] linkRef = new ControlLink[1];
        try (VideoPipeline video = new VideoPipeline(sink, () -> linkRef[0] == null ? -1 : linkRef[0].getPing())) {
            Connection connection = Connection.open(config, video);
            ControlLink link = connection.getLink();
            linkRef[0] = link;
            link.setEvents(msg -> System.out.println("EVENT " + msg));
            video.attach(link);
            System.out.println("probe: " + config.getIp() + ":" + config.getPort() + ", video " + ControlLink.VideoMode.parse(config.getString(ControlLink.VIDEO_MODE_KEY))
                    + ", " + seconds + " s");
            System.out.println("t  state  ping max  rx shown Mbps phone step cam dmg lost fixed keyreq bunch skip res robust app");
            try (LinkLog log = LinkLog.open(AppPaths.dataDir().resolve("logs"))) {
                VideoPipeline.Stats prev = video.stats();
                long prevKeys = 0, prevShown = 0;
                for (int t = 1; t <= seconds; t++) {
                    Thread.sleep(1000);
                    VideoPipeline.Stats s = video.stats();
                    log.sample(link, false, false, s.shownFps(), null, "", s);
                    long keys = link.getKeyFrameRequests();
                    System.out.println(String.format(Locale.ROOT, "%3d %-9s %4d %4d %3d %3d %5.2f %5.2f %d %3d %3d %3d %3d %3d %3d %3d %s %s %s",
                            t, link.getState(), link.getPing(), link.takeMaxPingMs(), s.receivedFps(), shown[0] - prevShown,
                            s.megabitsPerSecond(), link.getTelemetry().getPhoneVideoMbps(), s.bitrateStep(), link.getTelemetry().getCameraFps(),
                            s.damaged() - prev.damaged(), s.lost() - prev.lost(), s.recovered() - prev.recovered(), keys - prevKeys,
                            s.bunched() - prev.bunched(),
                            s.drawSkipped() - prev.drawSkipped(), s.width() + "x" + s.height(), link.isRobust() ? "R" : "-",
                            link.getTelemetry().getPhoneAppVersion()));
                    prev = s;
                    prevKeys = keys;
                    prevShown = shown[0];
                }
            } finally {
                connection.close();
            }
        }
    }
}
