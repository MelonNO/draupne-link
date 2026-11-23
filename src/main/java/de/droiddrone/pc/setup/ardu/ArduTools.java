package de.droiddrone.pc.setup.ardu;

import com.MAVLink.MAVLinkPacket;
import com.MAVLink.common.msg_command_long;
import com.MAVLink.common.msg_mag_cal_report;

import java.io.IOException;
import java.util.function.Consumer;


/**
 * Fleet PRD AS-3, AS-4, AS-5, AS-10: Drone setup's bench tools for an ArduPilot drone, over a {@link MavSession}. Each
 * refuses while the flight controller says armed or hasn't said. The commands of ArduPilot's own MAVLink dialect
 * that upstream's library leaves out are numbered here.
 */
public final class ArduTools {
    public static final int MAV_CMD_PREFLIGHT_CALIBRATION = 241, MAV_CMD_DO_MOTOR_TEST = 209, MAV_CMD_PREFLIGHT_REBOOT = 246;
    /** ArduPilot's dialect (ardupilotmega.xml). */
    public static final int MAV_CMD_ACCELCAL_VEHICLE_POS = 42429, MAV_CMD_DO_START_MAG_CAL = 42424,
            MAV_CMD_DO_ACCEPT_MAG_CAL = 42425, MAV_CMD_DO_CANCEL_MAG_CAL = 42426;

    /** The accelerometer calibration's positions, as ArduPilot numbers them (ACCELCAL_VEHICLE_POS). */
    public enum Position {
        LEVEL(1, "Level"), LEFT(2, "On its LEFT side"), RIGHT(3, "On its RIGHT side"), NOSE_DOWN(4, "Nose DOWN"),
        NOSE_UP(5, "Nose UP"), BACK(6, "On its BACK");

        public final int code;
        public final String words;

        Position(int code, String words) {
            this.code = code;
            this.words = words;
        }

        public static Position of(int code) {
            for (Position p : values()) if (p.code == code) return p;
            return null;
        }
    }

    /** ArduPilot's word that the calibration is over (ACCELCAL_VEHICLE_POS_SUCCESS / _FAILED). */
    public static final int ACCELCAL_SUCCESS = 16777215, ACCELCAL_FAILED = 16777216;

    private final MavSession fc;

    public ArduTools(MavSession fc) {
        this.fc = fc;
    }

    private void bench() throws IOException {
        if (fc.armed()) throw new IOException("Armed, or not known: only on the bench, disarmed.");
    }

    /**
     * AS-5: the six-position accelerometer calibration. ArduPilot asks for each position (a COMMAND_LONG
     * ACCELCAL_VEHICLE_POS to this ground station); {@code ask} gets it - the window shows the 3D drone that way - and
     * the pilot's "done" is {@link AccelCal#placed(Position)}. {@code done} gets true / false at the end.
     */
    public AccelCal startAccelCal(Consumer<Position> ask, Consumer<Boolean> done) throws IOException, InterruptedException {
        bench();
        AccelCal cal = new AccelCal(ask, done);
        fc.onPacket(cal);
        int r = fc.command(MAV_CMD_PREFLIGHT_CALIBRATION, 0, 0, 0, 0, 1, 0, 0, 3000);
        if (r != 0 && r != -1) { // -1: some firmwares answer only at the end
            fc.removePacketListener(cal);
            throw new IOException("ArduPilot refused the calibration (result " + r + ")");
        }
        return cal;
    }

    /** One accelerometer calibration under way. */
    public final class AccelCal implements Consumer<MAVLinkPacket> {
        private final Consumer<Position> ask;
        private final Consumer<Boolean> done;
        private volatile Position asked;

        AccelCal(Consumer<Position> ask, Consumer<Boolean> done) {
            this.ask = ask;
            this.done = done;
        }

        @Override
        public void accept(MAVLinkPacket p) {
            if (p.msgid != msg_command_long.MAVLINK_MSG_ID_COMMAND_LONG) return;
            msg_command_long c = new msg_command_long(p);
            if (c.command != MAV_CMD_ACCELCAL_VEHICLE_POS) return;
            int pos = Math.round(c.param1);
            if (pos == ACCELCAL_SUCCESS || pos == ACCELCAL_FAILED) {
                fc.removePacketListener(this);
                done.accept(pos == ACCELCAL_SUCCESS);
                return;
            }
            Position at = Position.of(pos);
            if (at != null) {
                asked = at;
                ask.accept(at);
            }
        }

        /** The pilot has put the drone as asked. */
        public void placed(Position p) throws IOException {
            if (p != asked) throw new IOException("ArduPilot asked for " + (asked == null ? "nothing yet" : asked.words));
            fc.send(new msg_command_long(p.code, 0, 0, 0, 0, 0, 0, MAV_CMD_ACCELCAL_VEHICLE_POS, (short) Math.max(1, fc.fcSysId()),
                    (short) 1, (short) 0, MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack());
        }

        public Position asked() {
            return asked;
        }
    }

    /**
     * AS-5, the quick way: ArduPilot's simple accelerometer calibration (PREFLIGHT_CALIBRATION, accelerometer 4) - the
     * drone level on the bench, one command, no six positions; enough to arm. The six-position one is the better.
     */
    public int simpleAccelCal() throws IOException, InterruptedException {
        bench();
        return fc.command(MAV_CMD_PREFLIGHT_CALIBRATION, 0, 0, 0, 0, 4, 0, 0, 10_000);
    }

    /** AS-4: the level trim (the drone level on the bench): PREFLIGHT_CALIBRATION, accelerometer "level" (5 = 2). */
    public int levelTrim() throws IOException, InterruptedException {
        bench();
        return fc.command(MAV_CMD_PREFLIGHT_CALIBRATION, 0, 0, 0, 0, 2, 0, 0, 5000);
    }

    /**
     * AS-5: ArduPilot's onboard compass calibration (every compass, saved by itself when good). {@code report} gets
     * each compass's result (MAG_CAL_REPORT: id, success, fitness in mGauss); the texts on the way come through the
     * session's text listener.
     */
    public int startCompassCal(Consumer<msg_mag_cal_report> report) throws IOException, InterruptedException {
        bench();
        Consumer<MAVLinkPacket> l = p -> {
            if (p.msgid == msg_mag_cal_report.MAVLINK_MSG_ID_MAG_CAL_REPORT) report.accept(new msg_mag_cal_report(p));
        };
        fc.onPacket(l);
        // all compasses, no retry, save when done, no delay, no reboot
        return fc.command(MAV_CMD_DO_START_MAG_CAL, 0, 0, 1, 0, 0, 0, 0, 3000);
    }

    public int cancelCompassCal() throws IOException, InterruptedException {
        return fc.command(MAV_CMD_DO_CANCEL_MAG_CAL, 0, 0, 0, 0, 0, 0, 0, 3000);
    }

    /**
     * AS-3: one motor spun (props off) - MAV_CMD_DO_MOTOR_TEST by motor NUMBER in ArduPilot's test order (1 = front
     * right on a quad X, then clockwise), throttle in % (2 % at a click, as the Betaflight motor test), for seconds
     * (the motor test's 3 s at most). ArduPilot stops by itself when the time is up.
     */
    public int motorTest(int motor, double throttlePercent, double seconds) throws IOException, InterruptedException {
        bench();
        if (motor < 1 || motor > 12) throw new IOException("motor " + motor);
        double pct = Math.max(0, Math.min(15, throttlePercent)); // never more than 15 % on the bench
        double s = Math.max(0.1, Math.min(3, seconds));
        // instance, type 0 = percent, throttle, timeout s, motor count 0 (one), order 1 = sequence (by test order)
        return fc.command(MAV_CMD_DO_MOTOR_TEST, motor, 0, (float) pct, (float) s, 0, 1, 0, 3000);
    }

    /** A reboot (after the frame type and the orientation, ArduPilot needs one). */
    public int reboot() throws IOException, InterruptedException {
        bench();
        return fc.command(MAV_CMD_PREFLIGHT_REBOOT, 1, 0, 0, 0, 0, 0, 0, 3000);
    }
}
