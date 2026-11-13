package de.droiddrone.pc.gui;

/**
 * What one drone's STANAG 4609 stream shows (1.0.65, the pilot: "one stream for each drone ... the video feed should
 * only be sent when someone is in pilot UAV [or] when a drone is in observation mode. Else there should be a black
 * screen with the DRAUPNE logo and an informational text about what mode it is in"; then "live picture then clip" for
 * an overwatch alert). Pure: the rules, for the tests.
 */
final class StanagShow {
    private StanagShow() {
    }

    /** What goes out, best first. */
    enum Kind { VIDEO, ALERT, CLIP, PAGE }

    /** A page's colour: plain, a warning (yellow), an alarm (red). */
    enum Tone { PLAIN, WARN, ALARM }

    /** One figure under the page's text: "PHONE" "87 %"; low: drawn as a warning. */
    record Stat(String label, String value, boolean low) {}

    /**
     * The page: the mode's name in big letters, a line or two on what it means, and the drone's figures under that
     * (the pilot: "include some stats in the screen shown when no video is streamed, specifically battery level").
     */
    record Page(String title, String text, Tone tone, java.util.List<Stat> stats) {
        Page(String title, String text, Tone tone) {
            this(title, text, tone, java.util.List.of());
        }

        Page with(java.util.List<Stat> figures) {
            return new Page(title, text, tone, figures == null ? java.util.List.of() : java.util.List.copyOf(figures));
        }
    }

    /** A phone's battery under this, not charging, is low; and a drone's pack under this. */
    static final int PHONE_LOW_PERCENT = 20, DRONE_LOW_PERCENT = 20;

    /**
     * The drone's pack as a percentage (the pilot: "give a percentage of the power in the drone battery, don't give the
     * cell value"): from its voltage per cell as FLEET's gauge fills ({@link FleetCards#cellFill}: 3.3 V empty, 4.2 V
     * full) - an estimate from the voltage at rest, the only measure a sitting drone gives.
     */
    static int dronePercent(double cellVolts) {
        return (int) Math.round(FleetCards.cellFill(cellVolts) * 100);
    }
    /** A pack read longer ago than this is said with when it was read. */
    static final long CELL_OLD_MS = 60_000;

    /**
     * The drone's figures as FLEET has them: the phone's battery, the drone's pack in per cent (with the time when this
     * PC read it a while ago) and the flight time and range that leaves ({@link FleetCards#FULL_FLIGHT_MIN}), when it
     * was last heard and when a sleeper checks in next. Times as clock times, to the
     * minute: the page is a still picture, and stays one between its changes.
     */
    static java.util.List<Stat> stats(FleetCards.Card c, long wallNow) {
        java.util.List<Stat> out = new java.util.ArrayList<>();
        if (c.phonePercent() >= 0) {
            out.add(new Stat("PHONE", c.phonePercent() + " %" + (c.charging() ? " CHARGING" : ""), c.phonePercent() < PHONE_LOW_PERCENT && !c.charging()));
        }
        if (!Double.isNaN(c.cellVolts())) {
            int percent = dronePercent(c.cellVolts());
            String v = percent + " %";
            if (c.cellAgeMs() > CELL_OLD_MS) v += " AT " + minute(wallNow - c.cellAgeMs());
            out.add(new Stat("DRONE", v, percent < DRONE_LOW_PERCENT));
            // the pilot: "flight time remaining and estimated range left ... displayed on the stanag screen"
            out.add(new Stat("FLIGHT", FleetCards.flightLeft(c.cellVolts()), percent < DRONE_LOW_PERCENT));
            out.add(new Stat("RANGE", FleetCards.rangeLeft(c.cellVolts()), percent < DRONE_LOW_PERCENT));
        }
        if (c.heardAgoMs() >= 0 && !"CONNECTED".equals(c.badge())) out.add(new Stat("HEARD", minute(wallNow - c.heardAgoMs()), false));
        if (c.nextCheckMs() > 0 && c.nextCheckMs() != FleetCards.LISTENING) out.add(new Stat("NEXT CHECK", minute(wallNow + c.nextCheckMs()), false));
        return out;
    }

    /** "14:32", this PC's time zone. */
    static String minute(long wallMs) {
        return clock(wallMs).substring(0, 5);
    }

    /** The drone's page with its figures (see {@link #page(FleetCards.Card, boolean, String, boolean)} and {@link #stats}). */
    static Page page(FleetCards.Card c, boolean watchedHere, String flownBy, boolean alerting, long wallNow) {
        return page(c, watchedHere, flownBy, alerting).with(stats(c, wallNow));
    }

    /**
     * The first that there is: the video (this PC pilots or observes the drone and pictures come), an alert's live
     * picture, an event's clip, the page.
     */
    static Kind pick(boolean videoFresh, boolean alertPicture, boolean clip) {
        return videoFresh ? Kind.VIDEO : alertPicture ? Kind.ALERT : clip ? Kind.CLIP : Kind.PAGE;
    }

    /**
     * The drone's page.
     * @param c          FLEET's card of it
     * @param watchedHere this PC watches it as a spectator (its badge is not CONNECTED then)
     * @param flownBy    the PC that flies it when that is another one ("" when it is not known which), else null
     * @param alerting   its overwatch alert runs now
     */
    static Page page(FleetCards.Card c, boolean watchedHere, String flownBy, boolean alerting) {
        String badge = c.badge() == null ? "" : c.badge();
        if (c.kind() == MapView.Kind.CRASHED) return new Page("CRASHED", "Marked lost. Last place in the metadata.", Tone.ALARM);
        if (c.kind() == MapView.Kind.LOST) return new Page("LOST", "Link lost while flown.", Tone.ALARM);
        if ("CONNECTED".equals(badge) || watchedHere) return new Page("PILOTED", "Waiting for the picture.", Tone.PLAIN);
        if (alerting) return new Page("OVERWATCH - ALERT", "Motion seen. See the map window.", Tone.ALARM);
        // "flown from another PC" is the phone's word from its last answer - up to half a minute old. A drone this PC
        // knows to rest (asleep, overwatching, in standby) is not flown: the pilot saw PILOTED for some seconds between
        // DEEP SLEEP and its page ("the stream went to piloted before entering as deep sleep")
        boolean resting = badge.equals("OVERWATCH") || badge.equals("ASLEEP") || badge.equals("STANDBY");
        if (flownBy != null && !resting) {
            return new Page("PILOTED", "Flown from " + (flownBy.isEmpty() ? "another PC" : flownBy) + ". Video on that PC.", Tone.PLAIN);
        }
        switch (badge) {
            case "OVERWATCH":
                if (c.blind()) return new Page("OVERWATCH - BLIND", "Camera stopped. The phone restarts it.", Tone.WARN);
                return new Page("OVERWATCH", "Asleep, camera watching for motion.", Tone.PLAIN);
            case "ASLEEP":
                return new Page("DEEP SLEEP", sleepText(c), Tone.PLAIN);
            case "STANDBY":
                return new Page("STANDBY", "Camera off. Ready for PILOT UAV.", Tone.PLAIN);
            case "ACTIVE":
                return new Page("IDLE", "Awake, no pilot. PILOT UAV flies it.", Tone.PLAIN);
            case "UNKNOWN":
                if (c.heardAgoMs() < 0) return new Page("OFFLINE", "Not heard since this PC started.", Tone.PLAIN);
                return new Page("UNKNOWN", c.nextCheckMs() != -1 ? "Missed its check-in." : "Not heard from.", Tone.WARN);
            default:
                return new Page(badge.isEmpty() ? "UNKNOWN" : badge, "", Tone.PLAIN);
        }
    }

    private static String sleepText(FleetCards.Card c) {
        if (c.nextCheckMs() == FleetCards.LISTENING || c.intervalMin() == de.droiddrone.common.SleepCheck.ALWAYS) return "Asleep. Always listening.";
        if (c.intervalMin() > 0) return "Asleep. Checks in every " + c.intervalMin() + " min.";
        return "Asleep.";
    }

    /** "14:32:07", this PC's time zone. */
    static String clock(long wallMs) {
        java.time.LocalTime t = java.time.Instant.ofEpochMilli(wallMs).atZone(java.time.ZoneId.systemDefault()).toLocalTime();
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
    }

    /** The bar over an alert's picture: live while the preview comes, else the last picture this PC has. */
    static String alertBar(String drone, boolean live, long wallNow) {
        return drone + " - OVERWATCH - ALERT - " + (live ? "LIVE " : "") + clock(wallNow);
    }

    /** The bar over a clip: when it was recorded, and that it is a recording. */
    static String clipBar(String drone, long startedAtWall) {
        return drone + " - OVERWATCH - CLIP " + clock(startedAtWall) + " - RECORDED";
    }
}
