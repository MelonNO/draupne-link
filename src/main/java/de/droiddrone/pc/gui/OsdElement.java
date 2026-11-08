package de.droiddrone.pc.gui;

/**
 * Every part of the OSD that SETTINGS > OSD can turn on or off and move, with its default: the layout before the OSD
 * became configurable, plus extra elements that are off until turned on. Positions are fractions of the picture (x of
 * its width, y of its height), measured on a 1280 x 720 picture; x is the element's left edge, centre or right edge,
 * by the third of the picture it is in (see {@link OsdLayout.Placement#align()}), y its first line's baseline (a stick
 * box's top). The key is what the settings file stores it under, so it must never change.
 */
enum OsdElement {
    // ------------------------------------------------------------------ the standard layout
    CROSSHAIR("crosshair", "Crosshair", "Fixed aircraft symbol in the middle.", true, 0.5, 0.5, false, true),
    HORIZON("horizon", "Artificial horizon", "Horizon line and pitch ladder, following roll and pitch.", true, 0.5, 0.5, false, true),
    HEADING("heading", "Heading tape", "Compass tape, heading and home direction (H).", true, 0.5, y(16), true, true),
    BATTERY("battery", "Battery", "Cell voltage with a bar, pack voltage, mAh used, current.", true, left(), y(40), true, true),
    PING("ping", "Ping", "Round-trip time to the phone.", true, right(), y(40), true, true),
    NETWORK("network", "Mobile network", "Phone network (LTE, 5G ...) and signal.", true, (1280 - 24) / 1280.0, y(92), true, true),
    VIDEO("video", "Video", "Frames per second and bitrate.", true, right(), y(114), true, true),
    PHONE_BATTERY("phoneBattery", "Phone battery", "The phone's battery (the drone's radio).", true, right(), y(140), true, true),
    PICTURE_AGE("pictureAge", "Picture age", "Frame arrival to screen. Rising means the PC falls behind.", true, right(), y(166), true, true),
    STICKS_AGE("sticksAge", "Sticks age", "Since the phone last wrote sticks to the FC. Red over 100 ms.", true, right(), y(192), true, true),
    BANNER("banner", "Status line", "What just happened, e.g. LINK BACK - ARMED - STICKS LIVE.", true, 0.5, y(190), true, true),
    ARMED("armed", "Arm state", "ARMED or DISARMED.", true, left(), y(628), true, true),
    FLIGHT_MODE("flightMode", "Flight mode", "ACRO, AIR, ANGLE ... as Betaflight names it, plus active switches.", true, left(), y(655), true, true),
    FLY_TIME("flyTime", "Fly time", "Time armed since connecting.", true, left(), y(680), true, true),
    CORE_TEMP("coreTemp", "Core temperature", "FC processor temperature (Betaflight 4.4+). Yellow from 60, red from 70 C.", true, (18 + 90 + 18) / 1280.0, y(680), true, true),
    GPS_SATS("gpsSats", "GPS satellites", "Satellites and fix (3D, 2D, NO FIX).", true, right(), y(616), true, true),
    SPEED("speed", "Speed", "Ground speed from the GPS.", true, right(), y(638), true, true),
    ALTITUDE("altitude", "Altitude", "Altitude, with an arrow while climbing or sinking.", true, right(), y(660), true, true),
    HOME("home", "Home distance", "Distance to home and an arrow pointing there.", true, right(), y(686), true, true),
    WARNINGS("warnings", "Warnings", "LINK LOST, FAILSAFE, LOW BATTERY, arming blocked ... Always on, movable only.", true, 0.5, 0.66, true, false),
    DEBUG("debug", "Video decoder stats", "Codec, resolution, decode/draw times, damaged and lost frames.", true, 0.5, y(710), true, true),

    // ------------------------------------------------------------------ extras, off until turned on
    THROTTLE("throttle", "Throttle", "Throttle in %, as sent.", false, left(), y(300), true, true),
    CURRENT("current", "Current draw", "Battery current in amps.", false, left(), y(325), true, true),
    MAH_DRAWN("mahDrawn", "mAh used", "Capacity used from the battery.", false, left(), y(350), true, true),
    PACK_VOLTAGE("packVoltage", "Battery voltage", "The whole pack's voltage.", false, left(), y(375), true, true),
    POWER("power", "Power", "Battery power in watts.", false, left(), y(400), true, true),
    DISTANCE("distance", "Distance flown", "Distance travelled while armed, from the GPS.", false, left(), y(425), true, true),
    RSSI("rssi", "RSSI", "Link quality from Betaflight (follows the mobile link).", false, right(), y(300), true, true),
    CPU_LOAD("cpuLoad", "CPU load", "The flight controller's CPU load.", false, right(), y(325), true, true),
    VARIO("vario", "Vertical speed", "Climb or sink rate.", false, right(), y(350), true, true),
    PITCH("pitch", "Pitch angle", "Pitch in degrees.", false, right(), y(375), true, true),
    ROLL("roll", "Roll angle", "Roll in degrees.", false, right(), y(400), true, true),
    COORDINATES("coordinates", "GPS coordinates", "Latitude and longitude.", false, right(), y(545), true, true),
    ON_TIME("onTime", "On time", "Time since the phone found the FC.", false, left(), y(704), true, true),
    CLOCK("clock", "Clock", "This PC's time of day.", false, 0.5, y(110), true, true),
    STICKS_LEFT("sticksLeft", "Left stick", "Left stick (mode 2: throttle and yaw).", false, 560 / 1280.0, y(600), true, true),
    STICKS_RIGHT("sticksRight", "Right stick", "Right stick (mode 2: pitch and roll).", false, 720 / 1280.0, y(600), true, true);

    final String key;
    final String title;
    final String help;
    final boolean defaultOn;
    final double defaultX;
    final double defaultY;
    /** Can be dragged to another place (the crosshair and horizon belong in the middle). */
    final boolean movable;
    /** Can be turned off (the warnings can't: they are what keeps the pilot safe). */
    final boolean hideable;

    OsdElement(String key, String title, String help, boolean defaultOn, double defaultX, double defaultY, boolean movable, boolean hideable) {
        this.key = key;
        this.title = title;
        this.help = help;
        this.defaultOn = defaultOn;
        this.defaultX = defaultX;
        this.defaultY = defaultY;
        this.movable = movable;
        this.hideable = hideable;
    }

    private static double left() {
        return 18 / 1280.0;
    }

    private static double right() {
        return (1280 - 18) / 1280.0;
    }

    private static double y(double units) {
        return units / 720.0;
    }
}
