package dev.justbuild.provider;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * How much a CLI or endpoint may send before JustBuild stops reading, so a runaway or hostile one cannot fill the
 * game's or server's memory. A plan the relay accepts is at most AiResponsePayload.MAX_CHARS (2,000,000)
 * characters; streamed events wrap each piece of it in a few hundred bytes of JSON, so 64 MB leaves room for the
 * largest real plan.
 */
final class OutputLimit {
    /** A CLI's stdout, or one HTTP response body. */
    static final long MAX_BYTES = 64L * 1024 * 1024;
    /** Codex writes only its final message to a file: the plan itself, without stream events around it. */
    static final long MAX_REPLY_FILE_BYTES = 16L * 1024 * 1024;
    /** stderr only supplies the last lines of an error, so only its end is kept. */
    static final int STDERR_TAIL_BYTES = 1024 * 1024;

    private OutputLimit() {
    }

    /** Thrown by a {@link #limit}ed stream once more than its limit has been read. */
    static final class ExceededException extends IOException {
        ExceededException(long maxBytes) {
            super("more than " + describe(maxBytes));
        }
    }

    /** {@code in}, failing with {@link ExceededException} past {@code maxBytes}; counting sits under any reader. */
    static InputStream limit(InputStream in, long maxBytes) {
        return new FilterInputStream(in) {
            private long count;

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) counted(1);
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = super.read(buffer, offset, length);
                if (n > 0) counted(n);
                return n;
            }

            @Override
            public long skip(long n) throws IOException {
                long skipped = super.skip(n);
                counted(skipped);
                return skipped;
            }

            private void counted(long n) throws ExceededException {
                count += n;
                if (count > maxBytes) throw new ExceededException(maxBytes);
            }
        };
    }

    /** "64 MB", "64 KB", or "100 bytes". */
    static String describe(long bytes) {
        long mb = 1024 * 1024;
        if (bytes >= mb && bytes % mb == 0) return bytes / mb + " MB";
        if (bytes >= 1024 && bytes % 1024 == 0) return bytes / 1024 + " KB";
        return bytes + " bytes";
    }
}
