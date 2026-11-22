package de.droiddrone.bench;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PhoneReset}, the hand's way after a hardware run that was killed: it takes back what the bench may have left
 * pretended on the phone, changes nothing else, and says what it found.
 */
class PhoneRigResetTest {
    private final List<String> said = new ArrayList<>();

    @Test
    void whatTheBenchPretendsIsTakenBackAndTheRestOnlyRead() {
        RecordingAdb adb = RecordingAdb.aPhone().says("shell cmd connectivity airplane-mode", "disabled").says("shell dumpsys deviceidle get deep", "ACTIVE")
                .says("shell dumpsys deviceidle unforce", "Light state: ACTIVE, deep state: ACTIVE").says("reverse --list", "UsbFfs tcp:5761 tcp:5761");
        assertTrue(PhoneReset.run(adb, said::add));
        assertEquals(List.of("get-state",
                "shell dumpsys deviceidle unforce", "shell dumpsys battery reset",
                "shell settings get global draupne_bench_stay_on_was", // a note of a run that kept the phone awake? None here: nothing put back
                "shell dumpsys battery", "shell cmd connectivity airplane-mode", "shell dumpsys deviceidle get deep",
                "shell settings get global stay_on_while_plugged_in", "reverse --list"), adb.sent);
        assertEquals(List.of(List.of("shell", "dumpsys deviceidle unforce"), List.of("shell", "dumpsys battery reset")), PhoneReset.ORDERS,
                "the two PhoneRig.close always sends, in its order - and nothing else is changed");
        assertTrue(said.get(0).startsWith("done    shell dumpsys deviceidle unforce"), said.toString());
        assertTrue(said.contains("battery: 80 %, 30.1 C, charging, on a charger"), said.toString());
        assertTrue(said.contains("airplane mode: disabled"), said.toString());
        // the bench build's flight controller comes down a port led back to this PC (adb reverse): seen, said, left alone
        assertTrue(said.stream().anyMatch(l -> l.contains("tcp:5761") && l.contains("left as they are")), said.toString());
        assertFalse(adb.sent.stream().anyMatch(c -> c.startsWith("reverse --remove")), adb.sent.toString());
        assertEquals("What the bench pretends is taken back.", said.get(said.size() - 1));
        // every one of its orders is on the guard's list for a real phone, the released app's included
        for (List<String> order : PhoneReset.ORDERS) {
            assertTrue(PhoneGuard.judge(PhoneRigGuardTest.phone(PhoneRigGuardTest.CABLE, PhoneRig.APP, false), order, p -> null).allowed(), order.toString());
        }
    }

    @Test
    void aPhoneThatIsNotThereIsLeftAlone() {
        RecordingAdb adb = RecordingAdb.aPhone().says("get-state", "error: no devices/emulators found");
        assertFalse(PhoneReset.run(adb, said::add));
        assertEquals(List.of("get-state"), adb.sent);
        assertTrue(said.get(0).startsWith("Nothing done"), said.toString());

        RecordingAdb gone = new RecordingAdb().fails("get-state");
        assertFalse(PhoneReset.run(gone, said::add));
        assertEquals(List.of("get-state"), gone.sent);
    }

    @Test
    void whatWasNotPutBackIsSaid() {
        // the cable out between two orders: the other is still sent
        RecordingAdb adb = RecordingAdb.aPhone().fails("shell dumpsys deviceidle unforce");
        assertFalse(PhoneReset.run(adb, said::add));
        assertEquals(List.of("shell dumpsys deviceidle unforce", "shell dumpsys battery reset"), adb.sent.subList(1, 3));
        assertTrue(said.get(0).startsWith("FAILED  shell dumpsys deviceidle unforce"), said.toString());
        assertEquals("NOT everything was put back: see above.", said.get(said.size() - 1));

        // a battery still pretended after the reset, and airplane mode left on: both said - the second not changed
        said.clear();
        RecordingAdb still = RecordingAdb.aPhone().says("shell cmd connectivity airplane-mode", "enabled")
                .says("shell dumpsys battery", "Current Battery Service state:\n  (UPDATES STOPPED -- use 'reset' to restart)\n  USB powered: false\n  status: 3\n  level: 60\n  scale: 100\n  temperature: 300\n");
        assertFalse(PhoneReset.run(still, said::add));
        assertTrue(said.stream().anyMatch(l -> l.startsWith("STILL PRETENDED")), said.toString());
        assertTrue(said.stream().anyMatch(l -> l.startsWith("airplane mode: enabled") && l.contains("by hand")), said.toString());
        assertFalse(still.sent.stream().anyMatch(c -> c.contains("airplane-mode disable") || c.contains("svc ")), "read, never switched: it may be the pilot's own setting");
    }
}
