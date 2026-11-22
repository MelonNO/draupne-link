package de.droiddrone.bench.hw;

import de.droiddrone.common.ArmRanges;
import de.droiddrone.common.BeeperBack;
import de.droiddrone.common.BeeperQuiet;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.MspFrames;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keeper's strict mode ({@link FcKeeper#PHONE_CODES}): from the phone's side only the MSP requests the phone app
 * really sends reach the board, framed as it frames them; everything else is dropped and written down - and never one
 * byte of text, which on a real board would open the CLI ('#') or restart it into its bootloader ('R').
 */
class FcKeeperStrictTest {
    private static final int[] AT_REST = {1000, 1000, 1000, 1000};
    private final FakeFlightBoard board = KeeperBench.armable();
    private final KeeperBench bench = new KeeperBench(board);
    private StandInPhone phone;

    @AfterEach
    void end() {
        if (phone != null) phone.close();
        bench.close();
    }

    /** The project's root: the tests run in build/test-work. */
    private static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        return root;
    }

    @Test
    void theListIsWhatThePhoneAppSendsABetaflightBoard() throws Exception {
        // every request DRAUPNE Flight builds is a getMspRequest(...) or getMspRequestWithPayload(...) in its Msp.java
        String source = Files.readString(root().resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Msp.java"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("getMspRequest(?:WithPayload)?\\(\\s*(?:\\(short\\)\\s*)?(\\w+)\\.(\\w+)").matcher(source);
        Set<Integer> sent = new TreeSet<>();
        Map<String, Class<?>> where = Map.of("FcCommon", FcCommon.class, "BeeperQuiet", BeeperQuiet.class, "ArmRanges", ArmRanges.class,
                "BeeperBack", BeeperBack.class);
        int calls = 0;
        while (m.find()) {
            Class<?> home = where.get(m.group(1));
            assertNotNull(home, "a request built from " + m.group(1) + "." + m.group(2) + ": where is that number?");
            sent.add(home.getField(m.group(2)).getInt(null) & 0xffff);
            calls++;
        }
        assertTrue(calls >= 25, "the phone app's requests were found in its source: " + calls);
        Set<Integer> inavOnly = Set.of(FcCommon.MSP2_INAV_STATUS & 0xffff, FcCommon.MSP2_INAV_ANALOG & 0xffff, FcCommon.MSP2_INAV_MIXER & 0xffff);
        assertTrue(sent.containsAll(inavOnly), "the three it sends an INAV board alone: " + sent);
        sent.removeAll(inavOnly);
        assertEquals(sent, FcKeeper.PHONE_CODES.keySet(), "strict mode passes what the phone app sends a Betaflight board - no more, no less");
        assertTrue(source.contains("return MspFrames.v2Request(cmd & 0xFFFF, null);") && source.contains("return MspFrames.v2Request(cmd & 0xFFFF, payload);")
                && !source.contains("v1Request"), "and the phone app frames every one of them as MSP v2: v1 is the keeper's own");
        // none of them writes a setting that lasts, moves a motor, restarts the board or takes the keeper's lock off
        for (int never : new int[]{Msp.ARMING_DISABLE, Msp.SET_MOTOR, Msp.EEPROM_WRITE, Msp.REBOOT, Msp.SET_PASSTHROUGH, Msp.SET_FEATURE_CONFIG, Msp.SET_MIXER_CONFIG,
                Msp.SEND_DSHOT_COMMAND, Msp.SET_MOTOR_OUTPUT_REORDERING}) {
            assertFalse(FcKeeper.PHONE_CODES.containsKey(never), "MSP " + never);
        }
    }

    @Test
    void whatThePhoneAppSendsPassesAndComesBackFramedAsItWasAsked() throws Exception {
        bench.start();
        phone = bench.phone();
        for (int code : FcKeeper.PHONE_CODES.keySet()) {
            int[] payload = FcKeeper.PHONE_CODES.get(code);
            if (code == Msp.SET_RAW_RC) phone.send(StandInPhone.stickFrame(1000, 1000));
            else phone.request(code, payload[0] == 0 ? null : new byte[payload[0]]);
            Waits.waitFor(() -> phone.answersTo(code) > 0, 3000, "an answer to MSP " + code + " (an error answer is one too)", bench.said);
            assertEquals(MspFrames.V2, phone.last(code).version);
            assertTrue(board.mspRequests().contains(code), "MSP " + code + " reached the board");
        }
        phone.request(Msp.BOXIDS, new byte[]{1}); // a page asked for, as the phone app asks
        Waits.waitFor(() -> phone.answersTo(Msp.BOXIDS) > 1, 3000, "the second page", bench.said);
        assertEquals(Map.of(), bench.keeper.findings(), "nothing of it is a finding");
        assertEquals(0, phone.junkIn());
        // the keeper's own answers (it asks in v1) never reach the phone
        assertTrue(phone.answers().stream().noneMatch(f -> f.version == MspFrames.V1), "no v1 frame at the phone");
        assertTrue(phone.answers().stream().noneMatch(f -> f.code == Msp.ARMING_DISABLE || f.code == Msp.MOTOR || f.code == Msp.SET_MOTOR));
    }

    @Test
    void whatIsNotOnTheListIsDroppedAndWrittenDown() throws Exception {
        bench.start().beat();
        phone = bench.phone();
        byte[] spin = Msp.allMotors(1300);
        phone.request(Msp.ARMING_DISABLE, new byte[]{0});      // the keeper's own lock, taken off from the phone's side
        phone.request(Msp.SET_MOTOR, spin);                    // a motor
        phone.request(Msp.EEPROM_WRITE, null);                 // a save
        phone.request(Msp.REBOOT, null);                       // a restart
        phone.request(Msp.SET_PASSTHROUGH, null);              // the port handed to the ESCs
        phone.request(Msp.SET_FEATURE_CONFIG, new byte[4]);    // the features
        phone.request(Msp.SEND_DSHOT_COMMAND, new byte[]{1, (byte) 255, 1, 8});
        phone.request(FcCommon.MSP2_INAV_STATUS & 0xffff, null); // what it sends an INAV board
        phone.send(MspFrames.v1Request(Msp.SET_MOTOR, spin));  // framed as v1: the keeper's own kind
        phone.send(MspFrames.v1Request(Msp.STATUS, null));
        phone.request(Msp.STATUS, new byte[]{1, 2, 3});        // a request the phone app sends, not as it sends it
        phone.send(MspFrames.v2Request(Msp.SET_RAW_RC, new byte[6]));  // three channels: no ARM switch in it
        phone.send(MspFrames.v2Request(Msp.SET_RAW_RC, new byte[9]));  // half a channel
        phone.send(MspFrames.v2Request(Msp.SET_RAW_RC, new byte[40])); // more channels than the phone app has
        byte[] flagged = MspFrames.v2Request(Msp.STATUS, null);
        flagged[3] = 1;                                        // a v2 flag the phone app never sets ...
        flagged[flagged.length - 1] = (byte) MspFrames.crc8DvbS2(MspFrames.crc8DvbS2(MspFrames.crc8DvbS2(MspFrames.crc8DvbS2(MspFrames.crc8DvbS2(0, 1), Msp.STATUS), 0), 0), 0);
        phone.send(flagged);                                   // ... with a checksum that is right
        phone.send(MspTap.bytes(MspFrames.V2, MspFrames.RESPONSE, Msp.STATUS, new byte[22])); // an answer, sent the wrong way
        phone.request(Msp.ANALOG, null); // and one that is on the list, last: when its answer is here, all before it were dealt with
        Waits.waitFor(() -> phone.answersTo(Msp.ANALOG) == 1, 3000, "the one good request to be answered", bench.said);

        assertTrue(board.armingLocked(), "the lock is the keeper's: the phone's side cannot take it off");
        assertArrayEquals(AT_REST, board.motors());
        assertEquals(0, board.saves + board.restarts);
        assertFalse(board.inPassthrough());
        assertEquals(de.droiddrone.pc.setup.BetaflightPlan.mspReceiverMask(FakeFlightBoard.DEFAULT_FEATURES), board.featureMask(), "the features as they were");
        assertEquals(0, bench.sticksAtBoard(), "none of the three odd stick frames");
        assertEquals(1, phone.answers().size(), "one answer came back: the good request's - " + phone.answers().size());
        Map<String, Integer> found = bench.keeper.findings();
        for (String key : new String[]{"strict-" + Msp.ARMING_DISABLE, "strict-" + Msp.SET_MOTOR, "strict-" + Msp.EEPROM_WRITE, "strict-" + Msp.REBOOT,
                "strict-" + Msp.SET_PASSTHROUGH, "strict-" + Msp.SET_FEATURE_CONFIG, "strict-" + Msp.SEND_DSHOT_COMMAND, "strict-" + (FcCommon.MSP2_INAV_STATUS & 0xffff),
                "strict-v1-" + Msp.SET_MOTOR, "strict-v1-" + Msp.STATUS, "strict-" + Msp.STATUS, "strict-" + Msp.SET_RAW_RC}) {
            assertTrue(found.containsKey(key), key + " is written down: " + found);
        }
        assertEquals(3, found.get("strict-" + Msp.SET_RAW_RC));
        assertEquals(3, found.get("strict-" + Msp.STATUS), "the payload, the flag, the answer sent the wrong way");
        assertTrue(bench.saidOf("FINDING: From the phone's side, not passed on: MSP v2 < 99 (1 bytes) - MSP 99 is not among what the phone app sends"), bench.said.toString());
        assertTrue(bench.saidOf("MSP v1 < 214 (16 bytes) - it is framed as MSP v1 (the phone app asks in v2; v1 is the keeper's own)"), bench.said.toString());
        assertTrue(bench.state().number("findings") >= 12);
        assertNull(bench.state().tripped(), "a finding is said, it stops nothing");
    }

    @Test
    void noByteOfTextEverReachesTheBoardFromThePhonesSide() throws Exception {
        bench.start();
        phone = bench.phone();
        phone.send("#".getBytes(StandardCharsets.US_ASCII));                       // would open Betaflight's CLI: the sticks stop
        phone.send("R".getBytes(StandardCharsets.US_ASCII));                       // would restart it into its bootloader
        phone.send("#\r\ndefaults\r\nsave\r\n".getBytes(StandardCharsets.US_ASCII)); // would wipe it
        phone.send("exit\n".getBytes(StandardCharsets.US_ASCII));
        byte[] bad = StandInPhone.stickFrame(1000, 2000);
        bad[bad.length - 1] ^= 1;                                                  // a frame with a wrong checksum, with '#' and 'R' nowhere in it
        phone.send(bad);
        // a whole stick frame with '#' and 'R' among its payload's bytes (1315 is 0x0523, 1362 is 0x0552): a frame, and passed as one
        int[] channels = {1315, 1362, 1000, 1315, 1000, 1362, 1315, 1362};
        byte[] hidden = new byte[16];
        for (int i = 0; i < channels.length; i++) Msp.putU16(hidden, 2 * i, channels[i]);
        assertTrue(new String(hidden, StandardCharsets.ISO_8859_1).contains("#") && new String(hidden, StandardCharsets.ISO_8859_1).contains("R"));
        phone.send(MspFrames.v2Request(Msp.SET_RAW_RC, hidden));
        phone.request(Msp.STATUS, null);
        Waits.waitFor(() -> phone.answersTo(Msp.STATUS) == 1, 3000, "the good request after all of it to be answered", bench.said);
        assertFalse(board.inCli(), "no '#' reached the board");
        assertFalse(board.inBootloader(), "no 'R' reached the board");
        assertEquals(0, board.restarts + board.saves);
        assertEquals(java.util.List.of(), board.cli());
        assertEquals(1, bench.sticksAtBoard(), "the whole frame passed as the frame it is");
        assertTrue(bench.keeper.findings().get("phone-bytes") >= 1, bench.keeper.findings().toString());
        assertTrue(bench.saidOf("byte(s) from the phone's side that are part of no MSP frame: not passed on"), bench.said.toString());
        assertTrue(bench.state().boardUp());
    }

    @Test
    void withStrictModeOffOnAMadeUpBoardAnyV2RequestPassesAndTextStillDoesNot() throws Exception {
        bench.config.strict = false;
        bench.start();
        phone = bench.phone();
        byte[] spin = Msp.allMotors(1000);
        Msp.putU16(spin, 0, 1040);
        phone.request(Msp.SET_MOTOR, spin);
        phone.send("#".getBytes(StandardCharsets.US_ASCII));
        phone.send(MspFrames.v1Request(Msp.SET_MOTOR, Msp.allMotors(1300)));
        Waits.waitFor(() -> phone.answersTo(Msp.SET_MOTOR) == 1, 3000, "the motor value to be taken", bench.said);
        phone.request(Msp.STATUS, null);
        Waits.waitFor(() -> phone.answersTo(Msp.STATUS) == 1, 3000, "a status", bench.said);
        assertArrayEquals(new int[]{1040, 1000, 1000, 1000}, board.motors(), "the v2 request passed; the v1 one is the keeper's kind and did not");
        assertFalse(board.inCli());
        assertTrue(bench.keeper.findings().containsKey("strict-v1-" + Msp.SET_MOTOR) && bench.keeper.findings().containsKey("phone-bytes"), bench.keeper.findings().toString());
        assertTrue(bench.saidOf("NOT strict"));
    }
}
