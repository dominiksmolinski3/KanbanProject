package pl.myproject.kanbanproject2.config.security.ratelimit;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Replays a prefix of the request body that has already been read, then hands the rest of the
 * stream through untouched.
 *
 * <p>The filter has to look at the body to find which account a request targets, but the body is a
 * one-shot stream — reading it would leave nothing for {@code @RequestBody} to bind. Buffering the
 * <em>whole</em> body instead would hand an unauthenticated caller a way to make the app allocate
 * however much it sends, so only a bounded prefix is kept and anything past it stays streaming.
 */
final class BufferedBodyRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] prefix;
    private final ServletInputStream remainder;

    private ServletInputStream stream;
    private BufferedReader reader;

    /**
     * @param prefix    bytes already consumed from {@code remainder}, to be served again first
     * @param remainder the original stream, positioned immediately after {@code prefix}
     */
    BufferedBodyRequestWrapper(HttpServletRequest request, byte[] prefix, ServletInputStream remainder) {
        super(request);
        this.prefix = prefix;
        this.remainder = remainder;
    }

    @Override
    public ServletInputStream getInputStream() {
        if (stream == null) {
            stream = new ReplayingServletInputStream(prefix, remainder);
        }
        return stream;
    }

    @Override
    public BufferedReader getReader() {
        if (reader == null) {
            reader = new BufferedReader(new InputStreamReader(getInputStream(), charset()));
        }
        return reader;
    }

    private Charset charset() {
        String encoding = getCharacterEncoding();
        if (encoding == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (IllegalArgumentException e) {
            // Matches what the container does with an encoding it cannot honour: fall back rather
            // than fail, since the body has already been accepted by this point.
            return StandardCharsets.UTF_8;
        }
    }

    private static final class ReplayingServletInputStream extends ServletInputStream {

        private final byte[] prefix;
        private final ServletInputStream remainder;
        private int position;

        private ReplayingServletInputStream(byte[] prefix, ServletInputStream remainder) {
            this.prefix = prefix;
            this.remainder = remainder;
        }

        @Override
        public int read() throws IOException {
            return position < prefix.length ? prefix[position++] & 0xFF : remainder.read();
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (position >= prefix.length) {
                return remainder.read(target, offset, length);
            }
            // Stop at the prefix boundary rather than topping the read up from the stream behind it,
            // which keeps every byte in one call coming from a single source.
            int available = Math.min(length, prefix.length - position);
            System.arraycopy(prefix, position, target, offset, available);
            position += available;
            return available;
        }

        @Override
        public int available() throws IOException {
            return (prefix.length - position) + remainder.available();
        }

        @Override
        public boolean isFinished() {
            return position >= prefix.length && remainder.isFinished();
        }

        @Override
        public boolean isReady() {
            return position < prefix.length || remainder.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            remainder.setReadListener(readListener);
        }
    }
}
