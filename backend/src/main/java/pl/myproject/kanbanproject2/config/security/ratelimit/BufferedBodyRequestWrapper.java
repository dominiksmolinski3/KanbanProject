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

final class BufferedBodyRequestWrapper extends HttpServletRequestWrapper {
    private final byte[] prefix;
    private final ServletInputStream remainder;

    private ServletInputStream stream;
    private BufferedReader reader;

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
