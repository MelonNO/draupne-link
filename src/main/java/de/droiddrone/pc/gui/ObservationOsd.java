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
    /**
     * A tile's OSD is this much smaller than the pilot's on a picture of its size (2026-11-10, the pilot: "in Observing
     * mode the OSD items are the same size, have the icons and text be scaled down"; was at least 0.65 of the full
     * size, as big as a tile's picture allowed).
     */
    static final float SCALE = 0.7f;
    /** The text keeps at least this unit (picture height / 720) on a small tile: 15 px figures at 6 px. */
    static final float MIN_UNIT = 0.4f;

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
