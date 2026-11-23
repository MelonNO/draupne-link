package de.droiddrone.pc.setup.ardu;

import de.droiddrone.pc.setup.dfu.DfuDescriptors;
import de.droiddrone.pc.setup.dfu.DfuLayout;
import de.droiddrone.pc.setup.dfu.HexImage;
import de.droiddrone.pc.setup.dfu.StmDfu;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD H1 / H4: ArduPilot onto a Betaflight board by the STM32's USB DFU, against a made-up DFU bootloader
 * ({@link FakeDfu}) and a made-up Betaflight board - never a real device. The layout, the HEX file, the write with
 * its erase, read-back and restart, the refusals (armed, under 1 MB, too big, a bad read-back) and that only the
 * picked board is ever opened.
 */
class ArduDfuTest {
    static final StmDfu.Sleeper NO_WAIT = ms -> { };

    /** An Intel HEX file of these blocks, 16 bytes a line, with extended linear address records. */
    static String hex(long[] addresses, byte[][] blocks) {
        StringBuilder sb = new StringBuilder();
        long upper = -1;
        for (int b = 0; b < blocks.length; b++) {
            for (int i = 0; i < blocks[b].length; i += 16) {
                long a = addresses[b] + i;
                if ((a >>> 16) != upper) {
                    upper = a >>> 16;
                    sb.append(record(0, 4, new byte[]{(byte) (upper >>> 8), (byte) upper}));
                }
                byte[] d = java.util.Arrays.copyOfRange(blocks[b], i, Math.min(blocks[b].length, i + 16));
                sb.append(record((int) (a & 0xffff), 0, d));
            }
        }
        sb.append(":00000001FF\r\n");
        return sb.toString();
    }

    static String record(int addr, int type, byte[] d) {
        int sum = d.length + (addr >>> 8) + (addr & 0xff) + type;
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, ":%02X%04X%02X", d.length, addr, type));
        for (byte x : d) {
            sb.append(String.format(Locale.ROOT, "%02X", x & 0xff));
            sum += x & 0xff;
        }
        return sb.append(String.format(Locale.ROOT, "%02X\r\n", (-sum) & 0xff)).toString();
    }

    static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    /** ArduPilot's "_with_bl.hex" in miniature: a bootloader at the flash start, Copter after a gap. */
    static HexImage withBl() throws IOException {
        byte[] bl = random(15_000, 1), copter = random(150_000, 2);
        String text = hex(new long[]{0x08000000L, 0x08004000L}, new byte[][]{bl, copter});
        return HexImage.read(text.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void theLayoutStringIsRead() throws Exception {
        DfuLayout f405 = DfuLayout.parse(FakeDfu.F405);
        assertEquals("Internal Flash", f405.name());
        assertEquals(12, f405.sectors().size());
        assertEquals(1024 * 1024, f405.total());
        assertEquals(0x08010000L, f405.sectors().get(4).address(), "the 64 KB sector after four of 16 KB");
        assertTrue(f405.sectors().get(0).can(DfuLayout.ERASABLE) && f405.sectors().get(0).can(DfuLayout.WRITABLE));
        assertEquals(512 * 1024, DfuLayout.parse(FakeDfu.F411).total());
        DfuLayout ro = DfuLayout.parse("@Option Bytes  /0x1FFFC000/01*016 e");
        assertFalse(ro.sectors().get(0).can(DfuLayout.ERASABLE), "'e': readable and writable, not erasable");
        assertEquals(16, ro.total(), "no unit: bytes");
        assertThrows(IOException.class, () -> DfuLayout.parse("Internal Flash"));
    }

    @Test
    void theHexFileIsReadIntoBlocks() throws Exception {
        HexImage img = withBl();
        assertEquals(2, img.segments().size(), "the gap between bootloader and Copter stays a gap");
        assertEquals(0x08000000L, img.first());
        assertEquals(0x08004000L + 150_000, img.end());
        assertArrayEquals(random(150_000, 2), img.segments().get(1).data());
        String good = hex(new long[]{0x08000000L}, new byte[][]{random(40, 3)});
        assertThrows(IOException.class, () -> HexImage.parseHex(good.replace(":00000001FF", "")), "cut off");
        String bad = good.substring(0, 20) + (good.charAt(20) == '0' ? '1' : '0') + good.substring(21);
        assertThrows(IOException.class, () -> HexImage.parseHex(bad), "a checksum wrong");
        assertEquals(HexImage.FLASH_BASE, HexImage.read(new byte[]{1, 2, 3}).first(), "a .bin goes to the flash start");
    }

    @Test
    void itIsErasedWrittenReadBackAndRestarted() throws Exception {
        FakeDfu chip = new FakeDfu("USB\\VID_0483&PID_DF11\\PICKED", FakeDfu.F405);
        chip.state = FakeDfu.ERROR; // left in an error: cleared first
        chip.status = 0x0F;
        HexImage img = withBl();
        List<String> said = new CopyOnWriteArrayList<>();
        new StmDfu(chip, NO_WAIT).flash(img, said::add);
        assertEquals(12, chip.erased.size(), "every sector: Betaflight's settings go too");
        for (HexImage.Segment s : img.segments()) assertArrayEquals(s.data(), chip.read(s.address(), s.data().length));
        assertEquals((byte) 0xff, chip.read(0x08080000L, 1)[0], "the rest of the flash is erased");
        assertTrue(chip.running, "restarted into ArduPilot");
        assertTrue(said.contains("Checking ..."));
        assertTrue(said.get(said.size() - 1).startsWith("Done"));
    }

    @Test
    void aBadReadBackStopsBeforeTheRestart() throws Exception {
        FakeDfu chip = new FakeDfu("USB\\VID_0483&PID_DF11\\PICKED", FakeDfu.F405);
        chip.corruptAt = 0x08004000L + 70_001;
        IOException e = assertThrows(IOException.class, () -> new StmDfu(chip, NO_WAIT).flash(withBl(), s -> { }));
        assertTrue(e.getMessage().contains("read back differs"), e.getMessage());
        assertFalse(chip.running, "stays in its bootloader, can be written again");
    }

    @Test
    void underOneMegabyteOrTooBigIsRefusedBeforeAnyErase() throws Exception {
        FakeDfu small = new FakeDfu("USB\\VID_0483&PID_DF11\\F411", FakeDfu.F411);
        IOException e = assertThrows(IOException.class, () -> new StmDfu(small, NO_WAIT).flash(withBl(), s -> { }));
        assertTrue(e.getMessage().contains("1 MB"), e.getMessage());
        assertTrue(small.erased.isEmpty() && small.requests.isEmpty(), "nothing sent");
        FakeDfu chip = new FakeDfu("USB\\VID_0483&PID_DF11\\PICKED", FakeDfu.F405);
        HexImage huge = new HexImage(List.of(new HexImage.Segment(0x080F0000L, new byte[128 * 1024])));
        assertThrows(IOException.class, () -> new StmDfu(chip, NO_WAIT).flash(huge, s -> { }));
        assertTrue(chip.erased.isEmpty(), "nothing erased");
    }

    /** A made-up Betaflight board: armed or not; its "bl" brings up its DFU device on the bus. */
    static final class Board implements ArduDfuFlow.Board {
        final FakeDfu.Bus bus;
        final List<FakeDfu> appear = new ArrayList<>();
        boolean armed, blSent;

        Board(FakeDfu.Bus bus) {
            this.bus = bus;
        }

        @Override
        public boolean armed() {
            return armed;
        }

        @Override
        public void bootloader() {
            blSent = true;
            for (FakeDfu d : appear) bus.add(d);
        }
    }

    @Test
    void onlyThePickedBoardIsWrittenAnotherInDfuIsLeftAlone() throws Exception {
        FakeDfu.Bus bus = new FakeDfu.Bus();
        FakeDfu other = new FakeDfu("USB\\VID_0483&PID_DF11\\OTHER", FakeDfu.F405);
        bus.add(other);
        Board board = new Board(bus);
        FakeDfu picked = new FakeDfu("USB\\VID_0483&PID_DF11\\PICKED", FakeDfu.F405);
        board.appear.add(picked);
        String id = ArduDfuFlow.fromBetaflight(board, bus, withBl(), s -> { }, NO_WAIT, 2000);
        assertEquals(picked.id, id);
        assertTrue(board.blSent);
        assertEquals(List.of(picked.id), bus.opened, "only the board that came up after its bl");
        assertTrue(picked.running && picked.closed);
        assertTrue(other.requests.isEmpty() && !other.opened, "the other board in DFU is never touched");
    }

    @Test
    void armedNothingIsSentAndTwoNewBoardsNothingIsOpened() throws Exception {
        FakeDfu.Bus bus = new FakeDfu.Bus();
        Board armed = new Board(bus);
        armed.armed = true;
        armed.appear.add(new FakeDfu("USB\\VID_0483&PID_DF11\\A", FakeDfu.F405));
        IOException e = assertThrows(IOException.class, () -> ArduDfuFlow.fromBetaflight(armed, bus, withBl(), s -> { }, NO_WAIT, 500));
        assertTrue(e.getMessage().contains("armed"));
        assertFalse(armed.blSent, "no bl to an armed drone");

        FakeDfu.Bus bus2 = new FakeDfu.Bus();
        Board two = new Board(bus2);
        two.appear.add(new FakeDfu("USB\\VID_0483&PID_DF11\\A", FakeDfu.F405));
        two.appear.add(new FakeDfu("USB\\VID_0483&PID_DF11\\B", FakeDfu.F405));
        e = assertThrows(IOException.class, () -> ArduDfuFlow.fromBetaflight(two, bus2, withBl(), s -> { }, NO_WAIT, 500));
        assertTrue(e.getMessage().contains("several"), e.getMessage());
        assertTrue(bus2.opened.isEmpty(), "which one is the pilot's is not guessed");

        FakeDfu.Bus bus3 = new FakeDfu.Bus();
        Board none = new Board(bus3);
        e = assertThrows(IOException.class, () -> ArduDfuFlow.fromBetaflight(none, bus3, withBl(), s -> { }, ms -> Thread.sleep(5), 100));
        assertTrue(e.getMessage().contains("didn't come up in DFU"), e.getMessage());
    }

    @Test
    void aBoardAlreadyInDfuIsWrittenOnlyWhileItIsThere() throws Exception {
        FakeDfu.Bus bus = new FakeDfu.Bus();
        FakeDfu chip = new FakeDfu("USB\\VID_0483&PID_DF11\\BOOT", FakeDfu.F405);
        bus.add(chip);
        ArduDfuFlow.inDfu(bus, chip.id, withBl(), s -> { }, NO_WAIT);
        assertTrue(chip.running);
        assertThrows(IOException.class, () -> ArduDfuFlow.inDfu(bus, "USB\\VID_0483&PID_DF11\\GONE", withBl(), s -> { }, NO_WAIT));
    }

    @Test
    void theBenchsMadeUpPortsNeverReachTheRealUsb() {
        de.droiddrone.pc.setup.BetaflightUsb.use(new de.droiddrone.pc.setup.BetaflightUsb.Usb() {
            public List<de.droiddrone.pc.setup.BetaflightUsb.Port> ports() {
                return List.of();
            }

            public de.droiddrone.pc.setup.BetaflightUsb.Wire open(de.droiddrone.pc.setup.BetaflightUsb.Port port) throws IOException {
                throw new IOException("none");
            }
        });
        try {
            assertSame(ArduDfuFlow.NONE, ArduDfuFlow.bus(), "the bench without a made-up DFU: no DFU at all");
            FakeDfu.Bus fake = new FakeDfu.Bus();
            ArduDfuFlow.use(fake);
            assertSame(fake, ArduDfuFlow.bus());
        } finally {
            ArduDfuFlow.use(null);
            de.droiddrone.pc.setup.BetaflightUsb.use(null);
        }
    }

    @Test
    void theNewestCopterWithBootloaderIsPickedForBetaflightsBoardName() {
        String manifest = """
                {"format-version":"1.0.0","firmware":[
                 {"platform":"MatekF405","vehicletype":"Copter","mav-firmware-version-type":"OFFICIAL","format":"apj","board_id":125,
                  "url":"https://firmware.ardupilot.org/Copter/stable/MatekF405/arducopter.apj"},
                 {"platform":"MatekF405","vehicletype":"Copter","mav-firmware-version-type":"BETA","format":"hex",
                  "url":"https://firmware.ardupilot.org/Copter/beta/MatekF405/arducopter_with_bl.hex"},
                 {"platform":"MatekF405","vehicletype":"Plane","mav-firmware-version-type":"OFFICIAL","format":"hex",
                  "url":"https://firmware.ardupilot.org/Plane/stable/MatekF405/arduplane_with_bl.hex"},
                 {"platform":"MatekF405","vehicletype":"Copter","mav-firmware-version-type":"OFFICIAL","format":"hex",
                  "url":"https://firmware.ardupilot.org/Copter/stable/MatekF405/arducopter_with_bl.hex"},
                 {"platform":"MatekF405-Wing","vehicletype":"Copter","mav-firmware-version-type":"OFFICIAL","format":"hex",
                  "url":"https://firmware.ardupilot.org/Copter/stable/MatekF405-Wing/arducopter_with_bl.hex"}
                ]}""";
        assertEquals("https://firmware.ardupilot.org/Copter/stable/MatekF405/arducopter_with_bl.hex", ArduDfuFlow.pickWithBl(manifest, "MATEKF405"));
        assertNull(ArduDfuFlow.pickWithBl(manifest, "KAKUTEF7"));
        assertNull(ArduDfuFlow.pickWithBl(manifest, null));
        assertEquals("MATEKF405", ArduDfuFlow.parseBoardName("# board_name\r\nboard_name MATEKF405"));
        assertNull(ArduDfuFlow.parseBoardName("board_name"));
    }

    @Test
    void theDescriptorsGiveTheTransferSizeAndTheLayoutsString() {
        byte[] cfg = {
                9, 2, 36, 0, 1, 1, 0, (byte) 0xC0, 50,      // configuration
                9, 4, 0, 0, 0, (byte) 0xFE, 1, 2, 4,        // interface 0 alt 0, string 4
                9, 4, 0, 1, 0, (byte) 0xFE, 1, 2, 5,        // alt 1, string 5
                9, 0x21, 0x0B, (byte) 0xFF, 0, 0, 8, 0x1A, 1 // DFU functional: wTransferSize 2048
        };
        assertEquals(2048, DfuDescriptors.transferSize(cfg, cfg.length, 1024));
        assertEquals(4, DfuDescriptors.altName(cfg, cfg.length, 0));
        assertEquals(5, DfuDescriptors.altName(cfg, cfg.length, 1));
        byte[] text = "@Internal Flash".getBytes(StandardCharsets.UTF_16LE);
        byte[] str = new byte[text.length + 2];
        str[0] = (byte) str.length;
        str[1] = 3;
        System.arraycopy(text, 0, str, 2, text.length);
        assertEquals("@Internal Flash", DfuDescriptors.string(str, str.length));
    }
}
