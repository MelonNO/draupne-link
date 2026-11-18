package de.droiddrone.pc.setup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Drone setup's beepers (the pilot's rule, 1.0.44): a drone on the phone is silent - disarmed or not - unless the
 * pilot presses BEEPER in the app. Betaflight's beeper conditions are all switched off but four:
 * <ul>
 * <li>RX_LOST - the receiver is lost: the phone's USB is out of the drone (the phone feeds idle sticks while the
 * drone is disarmed and the PC sends none, from phone app pc.33, so with the phone in, this is a real loss while
 * armed - the failsafe - the pilot's choice: it beeps then, to find the drone);</li>
 * <li>RX_LOST_LANDING - the failsafe's landing, armed only;</li>
 * <li>RX_SET - the BEEPER mode, which the app's BEEPER button drives on {@link ModeSwitch#BEEPER_AUX};</li>
 * <li>ON_USB - no sound of its own: off, it silences every beep while no battery is in, the button's too.</li>
 * </ul>
 * The DShot beacon (the motors beep) is on for the BEEPER mode, so the button is loud enough to find the drone in grass
 * (Betaflight beeps the motors only while disarmed) - and, 1.0.71, for the lost receiver too (the pilot, 2026-11-18:
 * the drone "should beep if link is lost, usb connection to FC is lost, or out of action is pressed, or beeper is
 * pressed", the buzzer and the motors both). What keeps a resting drone silent is the phone (pc.54, BeeperRule): idle
 * sticks while it is awake, and before a deep sleep or overwatch closes the port, the lost-receiver sounds switched
 * off in the flight controller's memory until the wake.
 *
 * <p>Read and written in the CLI by name ({@code beeper -NAME}, {@code beacon RX_SET}), never as MSP's bit mask: the
 * bits moved between 4.5 and 2025.12 (RC_SMOOTHING_INIT_FAIL went), the names didn't. Betaflight matches a name by
 * its start, the first in its table winning: RX_LOST comes before RX_LOST_LANDING there, ARMING before ARMING_GPS_FIX.
 */
public final class Beepers {
    /** The beeper conditions left on. */
    public static final List<String> KEEP = List.of("RX_LOST", "RX_LOST_LANDING", "RX_SET", "ON_USB");
    /** The DShot beacons wanted on: with the BEEPER mode, and (1.0.71) when the receiver is lost. */
    static final List<String> BEACONS = List.of("RX_SET", "RX_LOST");

    private Beepers() {
    }

    /**
     * The names after "Disabled:" ({@code beeper} / {@code beacon} without arguments) or "Available:" ({@code beeper
     * list}); null when the reply has no such line (a Betaflight built without beepers, or no DShot for the beacon).
     */
    static Set<String> names(String cliReply, String header) {
        for (String line : cliReply.split("\\R")) {
            String l = line.trim();
            if (!l.startsWith(header)) continue;
            Set<String> out = new LinkedHashSet<>();
            for (String n : l.substring(header.length()).trim().split("\\s+")) {
                if (!n.isEmpty() && !n.equalsIgnoreCase("none")) out.add(n.toUpperCase(Locale.ROOT));
            }
            return out;
        }
        return null;
    }

    /**
     * The commands that make it so; null when all is so already or the replies aren't understood (nothing guessed).
     * @param listReply   {@code beeper list}
     * @param beeperReply {@code beeper}
     * @param beaconReply {@code beacon}
     */
    public static BetaflightPlan.Change plan(String listReply, String beeperReply, String beaconReply) {
        Set<String> available = names(listReply, "Available:");
        Set<String> disabled = names(beeperReply, "Disabled:");
        List<String> commands = new ArrayList<>();
        int silenced = 0;
        if (available != null && disabled != null) {
            for (String n : available) {
                if (n.equals("ALL")) continue;
                boolean keep = KEEP.contains(n);
                if (keep && disabled.contains(n)) {
                    commands.add("beeper " + n);
                } else if (!keep && !disabled.contains(n)) {
                    commands.add("beeper -" + n);
                    silenced++;
                }
            }
        }
        int beacons = 0;
        Set<String> beaconOff = names(beaconReply, "Disabled:");
        for (String b : BEACONS) {
            if (beaconOff != null && beaconOff.contains(b)) {
                commands.add("beacon " + b);
                beacons++;
            }
        }
        if (commands.isEmpty()) return null;
        List<String> what = new ArrayList<>();
        if (silenced > 0) what.add(silenced + " beeper sound" + (silenced == 1 ? "" : "s") + " off");
        if (commands.size() - silenced - beacons > 0) what.add("receiver lost and BEEPER on");
        if (beacons > 0) what.add("motors beep with BEEPER and a lost link (DShot beacon)");
        return new BetaflightPlan.Change("Beepers: " + String.join(", ", what) + " - silent unless BEEPER, a lost link or phone unplugged",
                List.copyOf(commands));
    }
}
