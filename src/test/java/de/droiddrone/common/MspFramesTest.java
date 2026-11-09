package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The MSP frames both apps read and write (the phone's Msp, the PC's BetaflightUsb). */
class MspFramesTest {
    /** A reply: the request's shape with '>' (the checksum covers neither). */
    private static byte[] reply(byte[] request) {
        byte[] r = request.clone();
        r[2] = MspFrames.RESPONSE;
        return r;
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    @Test
    void theRequestsAreTheBytesThePhoneSentBefore() {
        // the phone's getMspRequest(MSP_STATUS = 101): $X< flag 0, code 101, size 0, CRC
        byte[] status = MspFrames.v2Request(101, null);
        assertArrayEquals(new byte[]{'$', 'X', '<', 0, 101, 0, 0, 0, (byte) crc(new int[]{0, 101, 0, 0, 0})}, status);
        assertArrayEquals(status, MspFrames.v2Request(101, new byte[0]), "an empty payload is none");
        byte[] rc = MspFrames.v2Request(200, new byte[]{(byte) 0xdc, 0x05});
        assertEquals(11, rc.length);
        assertEquals(2, rc[6]);
        assertEquals((byte) crc(new int[]{0, 200, 0, 2, 0, 0xdc, 0x05}), rc[10]);
        assertArrayEquals(new byte[]{'$', 'M', '<', 0, 101, 101}, MspFrames.v1Request(101, null));
        assertThrows(IllegalArgumentException.class, () -> MspFrames.v1Request(300, null));
        assertThrows(IllegalArgumentException.class, () -> MspFrames.v2Request(0x10000, null));
    }

    private static int crc(int[] bytes) {
        int c = 0;
        for (int b : bytes) c = MspFrames.crc8DvbS2(c, b);
        return c;
    }

    @Test
    void bothVersionsAreReadWithTheirPayloads() {
        byte[] v2 = reply(MspFrames.v2Request(0x3001, new byte[]{4, 0, 1, 2, 3}));
        byte[] v1 = reply(MspFrames.v1Request(104, new byte[]{(byte) 0xe8, 0x03, (byte) 0xff}));
        List<MspFrames.Frame> frames = new MspFrames().feed(join(v2, v1), v2.length + v1.length);
        assertEquals(2, frames.size());
        assertEquals(MspFrames.V2, frames.get(0).version);
        assertEquals(0x3001, frames.get(0).code);
        assertArrayEquals(new byte[]{4, 0, 1, 2, 3}, frames.get(0).payload);
        assertEquals(MspFrames.V1, frames.get(1).version);
        assertEquals(104, frames.get(1).code);
        assertArrayEquals(new byte[]{(byte) 0xe8, 0x03, (byte) 0xff}, frames.get(1).payload,
                "an odd count of bytes over 127: the phone's own v1 checksum never matched it");
        assertEquals(MspFrames.RESPONSE, frames.get(1).type);
    }

    @Test
    void aFrameCutByTheEndOfAReadIsKeptForTheNext() {
        byte[] names = reply(MspFrames.v2Request(116, "ARM;ANGLE;HORIZON;BEEPER;".repeat(10).getBytes()));
        MspFrames r = new MspFrames();
        List<MspFrames.Frame> got = new ArrayList<>();
        for (int at = 0; at < names.length; at += 64) {
            byte[] read = Arrays.copyOfRange(names, at, Math.min(names.length, at + 64));
            got.addAll(r.feed(read, read.length));
        }
        assertEquals(1, got.size(), "found whole across the reads");
        assertEquals(names.length - 9, got.get(0).payloadSize);
    }

    @Test
    void onlyTheFirstLengthBytesOfABufferCount() {
        byte[] f = reply(MspFrames.v2Request(101, null));
        byte[] buf = Arrays.copyOf(f, 256);
        assertEquals(1, new MspFrames().feed(buf, f.length).size());
        assertTrue(new MspFrames().feed(buf, f.length - 1).isEmpty(), "the last byte not read yet");
    }

    @Test
    void noiseAndBadChecksumsAreSteppedOver() {
        byte[] good = reply(MspFrames.v2Request(108, new byte[]{1, 2, 3, 4, 5, 6}));
        byte[] bad = good.clone();
        bad[9] ^= 0x40;
        byte[] stream = join(new byte[]{'#', '$', '$', 'Q'}, bad, good);
        List<MspFrames.Frame> frames = new MspFrames().feed(stream, stream.length);
        assertEquals(1, frames.size());
        assertEquals(108, frames.get(0).code);
    }

    @Test
    void aVersionReaderSkipsTheOthersHeaders() {
        byte[] v2 = reply(MspFrames.v2Request(0x3001, new byte[]{1}));
        byte[] stream = join(new byte[]{'$', 'M', '>'}, v2);
        assertEquals(1, new MspFrames(MspFrames.V2).feed(stream, stream.length).size(), "\"$M>\" is no frame start here");
        assertTrue(new MspFrames().feed(stream, stream.length).isEmpty(), "both versions: \"$M>\" waits for its size in bytes");
    }

    @Test
    void anImpossibleSizeIsNoFrameStartAndAResetForgetsTheCut() {
        byte[] good = reply(MspFrames.v2Request(101, null));
        byte[] huge = {'$', 'X', '>', 0, 1, 0, (byte) 0xff, (byte) 0xff};
        byte[] stream = join(huge, good);
        assertEquals(1, new MspFrames().feed(stream, stream.length).size());
        MspFrames r = new MspFrames();
        assertTrue(r.feed(good, 5).isEmpty());
        r.reset();
        byte[] rest = Arrays.copyOfRange(good, 5, good.length);
        assertTrue(r.feed(rest, rest.length).isEmpty(), "the cut start is gone");
    }

    @Test
    void errorsAndRequestsAreFramesToo() {
        byte[] err = MspFrames.v1Request(214, null);
        err[2] = MspFrames.ERROR;
        byte[] req = MspFrames.v2Request(101, null);
        byte[] stream = join(err, req);
        List<MspFrames.Frame> frames = new MspFrames().feed(stream, stream.length);
        assertTrue(frames.get(0).isError());
        assertEquals(MspFrames.REQUEST, frames.get(1).type);
        assertNull(frames.get(1).payload, "empty: null");
    }
}
