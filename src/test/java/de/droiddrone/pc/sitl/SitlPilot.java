package de.droiddrone.pc.sitl;

import com.MAVLink.common.msg_rc_channels_override;
import de.droiddrone.common.ArduLinkLossRule;
import de.droiddrone.pc.setup.ardu.MavSession;

/**
 * Fleet PRD AP-4 / AP-5 against real ArduPilot: what DRAUPNE Flight does on a drone's USB port, played over SITL's TCP
 * port - the pilot's sticks as RC_CHANNELS_OVERRIDE at 20 Hz (the PC's frames, AETR then AUX), and while the PC is
 * "cut", the shared {@link ArduLinkLossRule} at 10 Hz with the pilot's last frame held, sticks centred - the same rule,
 * the same commands (DO_SET_MODE) the phone sends. The MavSession sends the ground station's heartbeat once a second,
 * as the phone does while its rules are in charge.
 */
public final class SitlPilot implements AutoCloseable {
    /** The sticks, in microseconds: roll, pitch, yaw, throttle, AUX1 (ARM, ch5), AUX2 (modes, ch6), AUX3 ... */
    public final short[] frame = {1500, 1500, 1500, 1000, 1000, 1000, 1000, 1000};
    private final MavSession fc;
    private final ArduLinkLossRule rule = new ArduLinkLossRule();
    private volatile boolean pcUp = true, open = true, lead;
    private volatile long pcHeardAt = System.currentTimeMillis();
    public volatile String lastAction = "none";

    public SitlPilot(MavSession fc) {
        this.fc = fc;
        Thread t = new Thread(this::run, "sitlPilot");
        t.setDaemon(true);
        t.start();
    }

    /** The PC's link cut (or back): the phone's own rule takes over. */
    public void setPc(boolean up) {
        pcUp = up;
    }

    public void setLead(boolean lead) {
        this.lead = lead;
    }

    /** ArduPilot Copter's mode switch position (FLTMODE1..6 on ch6) as a PWM. */
    public static short modePwm(int position) {
        return (short) new int[]{1100, 1295, 1425, 1555, 1685, 1900}[Math.max(0, Math.min(5, position - 1))];
    }

    private void run() {
        long lastRule = 0;
        while (open) {
            long now = System.currentTimeMillis();
            try {
                if (pcUp) {
                    pcHeardAt = now;
                    send(frame);
                } else if (now - lastRule >= 100) {
                    lastRule = now;
                    int action = rule.tick(now, pcHeardAt, fc.armedFlag(), fc.customMode(), fc.gpsFix() >= 3, lead);
                    if (action != ArduLinkLossRule.NONE) {
                        lastAction = ArduLinkLossRule.name(action);
                        fc.command(176, 1, ArduLinkLossRule.modeFor(action), 0, 0, 0, 0, 0, 1000);
                    }
                    if (rule.running()) {
                        short[] hold = frame.clone();
                        hold[0] = hold[1] = hold[2] = hold[3] = 1500;
                        send(hold);
                    }
                }
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // the next round
            }
        }
    }

    private void send(short[] f) throws java.io.IOException {
        int t = Math.max(1, fc.fcSysId());
        fc.send(new msg_rc_channels_override(f[0], f[1], f[3], f[2], f[4], f[5], f[6], f[7], (short) t, (short) 1,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack());
    }

    @Override
    public void close() {
        open = false;
    }
}
