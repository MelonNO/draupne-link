package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BytesTest {
    @Test
    void anIntGoesInBigEndianAsTheLinkReadsIt() {
        for (int v : new int[]{0, 1, -1, 0x12345678, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x80, 0xff00}) {
            byte[] d = new byte[7];
            Bytes.putInt(d, 2, v);
            assertArrayEquals(ByteBuffer.allocate(4).putInt(v).array(), Arrays.copyOfRange(d, 2, 6), "as a DataOutputStream writes it");
            assertEquals(0, d[0] | d[1] | d[6], "nothing else touched");
            assertEquals(v, Bytes.readInt(d, 2));
        }
    }
}
