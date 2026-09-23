package pl.myproject.kanbanproject2.config;

import com.azure.core.http.HttpClient;
import com.azure.core.util.HttpClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
