package dev.ryuya.homepodbridge;

import java.io.IOException;
import java.io.InputStream;

/** Reads framed stereo PCM despite arbitrary pipe read boundaries. */
final class PcmPipe {
    private final InputStream input;
    private final byte[] bytes;
    PcmPipe(InputStream input, int samples) { this.input = input; bytes = new byte[samples * 2]; }
    int read(short[] pcm) throws IOException {
        int count = 0;
        while (count < bytes.length) {
            int read = input.read(bytes, count, bytes.length - count);
            if (read < 0) break;
            if (read == 0) continue;
            count += read;
        }
        if (count % 4 != 0) throw new IOException("Truncated stereo frame");
        for (int i = 0; i < count / 2; i++) pcm[i] = (short) ((bytes[i * 2] & 255) | (bytes[i * 2 + 1] << 8));
        return count == 0 ? -1 : count / 2;
    }
}
