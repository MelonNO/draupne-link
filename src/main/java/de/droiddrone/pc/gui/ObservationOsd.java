package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.observe.ObserverLink;
import de.droiddrone.pc.video.VideoPipeline;

/**
 * An observation tile's OSD (1.0.55, the pilot: "have observation view show the OSD for each drone"): the pilot's own
 * OSD layout over each tile, fed by that phone's own telemetry - what its flight controller sends (phone app pc.43 on:
 * observers get its records too; older phones: their observer record only, so little shows) and the tile's video.
 * Nothing flies from the grid, so there are no sticks, no ping, no arming warnings (see OsdRenderer.setObserver).
 */
final class ObservationOsd {
    /** The text keeps at least this unit (picture height / 720) on a small tile: 15 px figures at 10 px. */
    static final float MIN_UNIT = 0.65f;

    private ObservationOsd() {
    }

    /**
     * The tile's OSD now, or null before its phone sent anything.
     * @param telemetry the tile's own (ObserverLink.telemetry)
     * @param stats its video's figures, or null
     * @param lastPacketAt when its phone was last heard (MonoClock), 0 never
     */
    static OsdRenderer.Data data(FcTelemetry telemetry, VideoPipeline.Stats stats, long lastPacketAt, boolean invertPitch, long now) {
        if (lastPacketAt == 0 || telemetry == null) return null;
        boolean heard = now - lastPacketAt < ObserverLink.SILENT_MS;
        return new OsdRenderer.Data(true, heard, -1, true, false, telemetry, stats, invertPitch,
                null, null, false, false, false, false, null, false,
                null, false, -1, false, null, false, false, false, null);
    }
}
