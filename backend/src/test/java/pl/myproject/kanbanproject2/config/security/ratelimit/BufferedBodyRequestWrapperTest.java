package pl.myproject.kanbanproject2.config.security.ratelimit;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wrapper stands between an unauthenticated request and the controller that binds it, and every
 * method here is one a real servlet container may call even though {@code MockHttpServletRequest}
 * never does. A mistake in any of them is a 500 on a login attempt, so they are pinned directly.
 */
class BufferedBodyRequestWrapperTest {

    private static final String BODY = "{\"email\":\"a@example.com\",\"password\":\"correct horse\"}";

    /** Split so that half the body is replayed from the prefix and half comes off the live stream. */
    private static final int SPLIT = 20;

    @Test
    @DisplayName("the stream serves the buffered prefix and the rest of the body as one body")
    void replaysTheWholeBodyThroughTheStream() throws Exception {
        BufferedBodyRequestWrapper wrapper = wrap(BODY, SPLIT);

        String read = new String(wrapper.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(read).isEqualTo(BODY);
    }

    @Test
    @DisplayName("the reader serves the whole body too, for anything that binds through it")
    void replaysTheWholeBodyThroughTheReader() throws Exception {
        BufferedBodyRequestWrapper wrapper = wrap(BODY, SPLIT);

        assertThat(readFully(wrapper)).isEqualTo(BODY);
    }

    @Test
    @DisplayName("reading a byte at a time crosses the prefix boundary without losing or repeating one")
    void replaysCorrectlyOneByteAtATime() throws Exception {
        ServletInputStream stream = wrap(BODY, SPLIT).getInputStream();

        StringBuilder read = new StringBuilder();
        int next;
        while ((next = stream.read()) >= 0) {
            read.append((char) next);
        }

        assertThat(read.toString()).isEqualTo(BODY);
    }

    @Test
    @DisplayName("mixing single-byte and bulk reads across the boundary still yields the body")
    void replaysCorrectlyForMixedReads() throws Exception {
        ServletInputStream stream = wrap(BODY, SPLIT).getInputStream();

        StringBuilder read = new StringBuilder();
        read.append((char) stream.read());
        byte[] chunk = new byte[8];
        int count;
        while ((count = stream.read(chunk, 0, chunk.length)) > 0) {
            read.append(new String(chunk, 0, count, StandardCharsets.UTF_8));
        }

        assertThat(read.toString()).isEqualTo(BODY);
    }

    @Test
    @DisplayName("the container gets one stream and one reader, not a fresh one per call")
    void returnsTheSameStreamAndReader() throws Exception {
        BufferedBodyRequestWrapper wrapper = wrap(BODY, SPLIT);

        assertThat(wrapper.getInputStream()).isSameAs(wrapper.getInputStream());
        assertThat(wrapper.getReader()).isSameAs(wrapper.getReader());
    }

    @Test
    @DisplayName("available counts what is left in the prefix and behind it")
    void reportsWhatIsAvailableAcrossBothSources() throws Exception {
        ServletInputStream stream = wrap(BODY, SPLIT).getInputStream();

        assertThat(stream.available()).isEqualTo(BODY.length());
        assertThat(stream.readNBytes(5)).hasSize(5);
        assertThat(stream.available()).isEqualTo(BODY.length() - 5);
    }

    @Test
    @DisplayName("the stream is ready from the start and only finished once both sources are drained")
    void reportsReadinessAndCompletion() throws Exception {
        ServletInputStream stream = wrap(BODY, SPLIT).getInputStream();

        assertThat(stream.isReady()).isTrue();
        assertThat(stream.isFinished()).isFalse();

        // Still inside the prefix, so the stream behind it has not even been touched yet.
        stream.readNBytes(SPLIT - 1);
        assertThat(stream.isFinished()).isFalse();

        stream.readAllBytes();
        assertThat(stream.isFinished()).isTrue();
    }

    @Test
    @DisplayName("a read listener is handed to the stream that still has bytes to deliver")
    void delegatesTheReadListener() throws Exception {
        RecordingServletInputStream remainder = new RecordingServletInputStream(new byte[]{'x'});
        BufferedBodyRequestWrapper wrapper = new BufferedBodyRequestWrapper(
                new MockHttpServletRequest(), new byte[]{'a'}, remainder);
        ReadListener listener = new ReadListener() {
            @Override
            public void onDataAvailable() {
            }

            @Override
            public void onAllDataRead() {
            }

            @Override
            public void onError(Throwable throwable) {
            }
        };

        wrapper.getInputStream().setReadListener(listener);

        assertThat(remainder.readListener).isSameAs(listener);
    }

    @Test
    @DisplayName("the reader honours the encoding the request declared")
    void decodesUsingTheDeclaredEncoding() throws Exception {
        Charset latin1 = StandardCharsets.ISO_8859_1;
        String body = "{\"email\":\"café@example.com\"}";
        BufferedBodyRequestWrapper wrapper = wrap(body, latin1, 10, latin1.name());

        assertThat(readFully(wrapper)).isEqualTo(body);
    }

    @Test
    @DisplayName("an encoding the JVM cannot honour falls back instead of failing the request")
    void fallsBackOnAnUnusableEncoding() throws Exception {
        BufferedBodyRequestWrapper wrapper =
                wrap(BODY, StandardCharsets.UTF_8, SPLIT, "not-a-real-charset");

        assertThat(readFully(wrapper)).isEqualTo(BODY);
    }

    private static String readFully(BufferedBodyRequestWrapper wrapper) throws IOException {
        StringBuilder read = new StringBuilder();
        int next;
        while ((next = wrapper.getReader().read()) >= 0) {
            read.append((char) next);
        }
        return read.toString();
    }

    private static BufferedBodyRequestWrapper wrap(String body, int prefixLength) {
        return wrap(body, StandardCharsets.UTF_8, prefixLength, null);
    }

    private static BufferedBodyRequestWrapper wrap(
            String body, Charset charset, int prefixLength, String declaredEncoding) {

        byte[] bytes = body.getBytes(charset);
        byte[] prefix = Arrays.copyOf(bytes, Math.min(prefixLength, bytes.length));
        RecordingServletInputStream remainder =
                new RecordingServletInputStream(Arrays.copyOfRange(bytes, prefix.length, bytes.length));

        MockHttpServletRequest request = new MockHttpServletRequest();
        if (declaredEncoding != null) {
            request.setCharacterEncoding(declaredEncoding);
        }
        return new BufferedBodyRequestWrapper(request, prefix, remainder);
    }

    /** A stand-in for the container's stream, with the contract methods defined unambiguously. */
    private static final class RecordingServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream source;
        private ReadListener readListener;
        private boolean finished;

        private RecordingServletInputStream(byte[] bytes) {
            this.source = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() {
            int next = source.read();
            finished = next < 0;
            return next;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            int count = source.read(target, offset, length);
            finished = count < 0;
            return count;
        }

        @Override
        public int available() {
            return source.available();
        }

        @Override
        public boolean isFinished() {
            return finished || source.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            this.readListener = readListener;
        }
    }
}
