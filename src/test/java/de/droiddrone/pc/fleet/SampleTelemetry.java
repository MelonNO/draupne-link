package de.droiddrone.pc.fleet;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.common.UdpPacketData;
import de.droiddrone.pc.FcTelemetry;

/** For tests elsewhere: an FcTelemetry filled the way a phone's (or a virtual drone's) records fill it. */
public final class SampleTelemetry {
    public static final de.droiddrone.common.FcInfo BETAFLIGHT =
            new de.droiddrone.common.FcInfo(de.droiddrone.common.FcInfo.FC_VARIANT_BETAFLIGHT, 4, 4, 1, 0, 1, 45, 0);

    private SampleTelemetry() {
    }

    /** Attitude, status, a 4S pack at 16.4 V; every arming block on (FAILSAFE among them) when asked. */
    public static FcTelemetry idleDrone(boolean everyBlock) throws Exception {
        byte[] d = idleDronePacket(everyBlock);
        FcTelemetry t = new FcTelemetry();
        // the FC's records are read knowing the FC (an observer gets it from phone app pc.43 on)
        t.parse(new DataReader(java.util.Arrays.copyOfRange(d, 1, d.length), true), BETAFLIGHT);
        return t;
    }

    /** The same records as the TelemetryData packet a phone sends. */
    public static byte[] idleDronePacket(boolean everyBlock) throws Exception {
        UdpPacketData p = new UdpPacketData(UdpCommon.TelemetryData);
        TelemetryRecords.attitude(p.daos, 2, -3, 123);
        TelemetryRecords.status(p.daos, TelemetryRecords.SENSOR_ACC | TelemetryRecords.SENSOR_GYRO, 0, everyBlock ? -1 : 0, 12, 38);
        TelemetryRecords.analog(p.daos, 120, 0, 0.5, 16.4);
        TelemetryRecords.batteryState(p.daos, 4, 1500, 120, 0.5, 16.4);
        return p.getData();
    }

    /** A Betaflight FcInfo packet as a phone sends it. */
    public static byte[] fcInfoPacket() throws Exception {
        return TelemetryRecords.fcInfoPacket();
    }
}
