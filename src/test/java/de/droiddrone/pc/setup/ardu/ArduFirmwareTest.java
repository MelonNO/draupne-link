package de.droiddrone.pc.setup.ardu;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet AS-1: an .apj onto a made-up ArduPilot bootloader - read, checked, erased, written, compared, restarted. */
class ArduFirmwareTest {
    /** The bootloader: its flash, board id; it answers as ArduPilot's does. */
    static final class FakeBootloader implements ArduFirmware.Wire {
        final byte[] flash;
        final int boardId;
        final ArrayDeque<Integer> out = new ArrayDeque<>();
        int at;
        boolean rebooted;
        boolean corrupt;

        FakeBootloader(int boardId, int flashSize) {
            this.boardId = boardId;
            flash = new byte[flashSize];
            java.util.Arrays.fill(flash, (byte) 0x55); // not erased yet
        }

        private void ok() {
            out.add(ArduFirmware.INSYNC);
            out.add(ArduFirmware.OK);
        }

        private void int32(int v) {
            for (int i = 0; i < 4; i++) out.add((v >>> (8 * i)) & 0xff);
        }

        public void write(byte[] d) {
            switch (d[0] & 0xff) {
                case ArduFirmware.GET_SYNC -> ok();
                case ArduFirmware.GET_DEVICE -> {
                    int what = d[1];
                    int32(what == ArduFirmware.INFO_BOARD_ID ? boardId : what == ArduFirmware.INFO_FLASH_SIZE ? flash.length : 5);
                    ok();
                }
                case ArduFirmware.CHIP_ERASE -> {
                    java.util.Arrays.fill(flash, (byte) 0xff);
                    at = 0;
                    ok();
                }
                case ArduFirmware.PROG_MULTI -> {
                    int n = d[1] & 0xff;
                    System.arraycopy(d, 2, flash, at, n);
                    if (corrupt && at == 0) flash[0] ^= 1;
                    at += n;
                    ok();
                }
                case ArduFirmware.GET_CRC -> {
                    int32(ArduFirmware.crc32(flash, flash.length, 0));
                    ok();
                }
                case ArduFirmware.REBOOT -> rebooted = true;
                default -> {
                    out.add(ArduFirmware.INSYNC);
                    out.add(ArduFirmware.INVALID);
                }
            }
        }

        public int read(int timeoutMs) {
            Integer b = out.poll();
            return b == null ? -1 : b;
        }
    }

    static byte[] apj(int boardId, byte[] image) {
        Deflater d = new Deflater();
        d.setInput(image);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) z.write(buf, 0, d.deflate(buf));
        String json = "{\"board_id\": " + boardId + ", \"image_size\": " + image.length + ", \"summary\": \"CubeTest\", \"image\": \""
                + Base64.getEncoder().encodeToString(z.toByteArray()) + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anApjIsWrittenCheckedAndTheBoardRestarts() throws IOException {
        byte[] image = new byte[10_001];
        new Random(4).nextBytes(image);
        ArduFirmware.Apj fw = ArduFirmware.parseApj(apj(140, image));
        assertEquals(140, fw.boardId());
        assertEquals(10_004, fw.image().length, "padded to 4");
        FakeBootloader bl = new FakeBootloader(140, 65_536);
        List<String> said = new ArrayList<>();
        ArduFirmware.upload(bl, fw, said::add);
        assertTrue(bl.rebooted);
        assertArrayEquals(image, java.util.Arrays.copyOf(bl.flash, image.length));
        assertTrue(said.get(said.size() - 1).startsWith("Done"), said.toString());
    }

    @Test
    void anotherBoardsFirmwareABadCheckAndNoBootloaderAreRefused() throws IOException {
        ArduFirmware.Apj fw = ArduFirmware.parseApj(apj(140, new byte[400]));
        IOException other = assertThrows(IOException.class, () -> ArduFirmware.upload(new FakeBootloader(9, 65_536), fw, s -> { }));
        assertTrue(other.getMessage().contains("board"), other.getMessage());
        FakeBootloader bad = new FakeBootloader(140, 65_536);
        bad.corrupt = true;
        IOException check = assertThrows(IOException.class, () -> ArduFirmware.upload(bad, fw, s -> { }));
        assertTrue(check.getMessage().contains("check"), check.getMessage());
        assertFalse(bad.rebooted, "a board that failed its check stays in its bootloader");
        ArduFirmware.Wire silent = new ArduFirmware.Wire() {
            public void write(byte[] d) {
            }

            public int read(int t) {
                return -1;
            }
        };
        assertThrows(IOException.class, () -> ArduFirmware.upload(silent, fw, s -> { }));
        assertThrows(IOException.class, () -> ArduFirmware.parseApj("{\"board_id\": 1}".getBytes()));
        assertThrows(IOException.class, () -> ArduFirmware.upload(new FakeBootloader(140, 100), fw, s -> { }), "doesn't fit");
    }

    @Test
    void theNewestStableCopterForTheBoardIsPicked() {
        String m = "{\"format-version\":\"1.0.0\",\"firmware\":["
                + "{\"board_id\":140,\"vehicletype\":\"Plane\",\"format\":\"apj\",\"mav-firmware-version-type\":\"OFFICIAL\",\"url\":\"https://x/Plane/stable/a.apj\"},"
                + "{\"board_id\":140,\"vehicletype\":\"Copter\",\"format\":\"apj\",\"mav-firmware-version-type\":\"BETA\",\"url\":\"https://x/Copter/beta/b.apj\"},"
                + "{\"board_id\":140,\"vehicletype\":\"Copter\",\"format\":\"hex\",\"mav-firmware-version-type\":\"OFFICIAL\",\"url\":\"https://x/Copter/stable/c.hex\"},"
                + "{\"board_id\":140,\"vehicletype\":\"Copter\",\"format\":\"apj\",\"mav-firmware-version-type\":\"OFFICIAL\",\"url\":\"https://x/Copter/stable/d.apj\"},"
                + "{\"board_id\":9,\"vehicletype\":\"Copter\",\"format\":\"apj\",\"mav-firmware-version-type\":\"OFFICIAL\",\"url\":\"https://x/Copter/stable/e.apj\"}]}";
        assertEquals("https://x/Copter/stable/d.apj", ArduFirmwareFlow.pick(m, 140));
        assertNull(ArduFirmwareFlow.pick(m, 77));
    }

    @Test
    void theCrcIsZlibsWithoutTheFinalXor() {
        byte[] b = "123456789".getBytes(StandardCharsets.US_ASCII);
        java.util.zip.CRC32 z = new java.util.zip.CRC32();
        z.update(b);
        // zlib's CRC32 = ~crc(state ~0); the bootloader's runs from 0 with no final xor
        assertEquals((int) z.getValue(), ~ArduFirmware.crc32(b, b.length, ~0));
    }
}
