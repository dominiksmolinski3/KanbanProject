package pl.myproject.kanbanproject2.config;

import com.azure.core.http.HttpClient;
import com.azure.core.util.HttpClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the transport swap in the POM, which nothing else can see.
 *
 * <p>The Azure SDK picks its HTTP client through a {@code ServiceLoader} at runtime, not at compile
 * time. The Email client asks for Netty by default; the POM excludes it and puts the JDK client
 * there instead, so that a Tomcat application does not carry Netty and Reactor Netty for the sake
 * of a handful of messages a minute. Nothing about that arrangement is checked by the compiler: get
 * the exclusion wrong and either both transports are present and the choice is whichever the
 * classpath happens to yield, or neither is and the first message fails at runtime with no provider
 * found.
 *
 * <p>So this is a build-time assertion about a runtime lookup, in the same spirit as {@code
 * PublicBundlePathsTest} and {@code ApiPathPrefixTest}: a dependency change that undoes the
 * intention fails here rather than in production.
 */
class AzureTransportTest {

    @Test
    @DisplayName("the SDK resolves the JDK HTTP client, which is the one the POM leaves on the classpath")
    void theJdkTransportIsTheOneWiredUp() {
        HttpClient resolved = HttpClient.createDefault(new HttpClientOptions()
                .setConnectTimeout(Duration.ofSeconds(1)));

        assertThat(resolved.getClass().getName()).startsWith("com.azure.core.http.jdk.httpclient");
    }

    @Test
    @DisplayName("Netty is not on the classpath at all, so it cannot win the lookup by accident")
    void nettyIsNotOnTheClasspath() {
        assertThatThrownBy(() -> Class.forName("com.azure.core.http.netty.NettyAsyncHttpClientProvider"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
