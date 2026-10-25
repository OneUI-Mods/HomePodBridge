package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.Test;

public final class PcmPipeTest {
    @Test public void fragmentedReadsPreserveStereoFramesAndEndian() throws Exception {
        byte[] raw = {0, (byte) 128, (byte) 255, 127, 1, 0, (byte) 255, (byte) 255};
        var input = new ByteArrayInputStream(raw) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) { return super.read(bytes, offset, Math.min(length, 3)); }
        };
        PcmPipe pipe = new PcmPipe(input, 4);
        short[] pcm = new short[4];
        assertEquals(4, pipe.read(pcm));
        assertArrayEquals(new short[]{Short.MIN_VALUE, Short.MAX_VALUE, 1, -1}, pcm);
        assertEquals(-1, pipe.read(pcm));
    }
    @Test(expected = IOException.class) public void partialFrameAtEofIsRejected() throws Exception {
        new PcmPipe(new ByteArrayInputStream(new byte[]{1, 2, 3}), 4).read(new short[4]);
    }
}
