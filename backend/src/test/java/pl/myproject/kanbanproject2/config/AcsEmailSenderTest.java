package pl.myproject.kanbanproject2.config;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.RetryOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.service.EmailDeliveryException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SDK is driven for real, against a fake transport rather than a mocked {@link EmailClient}.
 *
 * <p>Mocking the client would assert that this class calls a method, which is not where the risk
 * is. The risks are that the message is assembled into something the service would reject, that the
 * request goes out unauthenticated, that a rejection is replayed until the recipient gets three
 * copies - and above all the claim {@link AcsEmailSender} rests on: that {@code beginSend} has
 * already posted by the time it hands back a poller nobody keeps. Substituting the HTTP client
 * leaves every one of those in the test and takes out only the network.
 *
 * <p>A stub HTTP <em>server</em> was the first attempt and cannot work: the key credential policy
 * refuses any endpoint that is not HTTPS, so the pipeline never reaches the socket. Which transport
 * that pipeline would have used is not testable here either, and is checked separately in {@link
 * AzureTransportTest}.
 */
class AcsEmailSenderTest {

    /** The credential policy only requires that the access key is decodable base64. */
    private static final String CONNECTION_STRING = "endpoint=https://stub.communication.azure.com/;accesskey="
            + Base64.getEncoder().encodeToString("stub-access-key-for-tests-only!!".getBytes(StandardCharsets.UTF_8));

    private record Recorded(String method, String url, String authorization, String body) {
    }

    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    /** Answers every request with {@code status}, so a persistent failure stays persistent. */
    private HttpClient transportAnswering(int status) {
        return request -> {
            requests.add(new Recorded(request.getHttpMethod().name(), request.getUrl().toString(),
                    request.getHeaders().getValue(HttpHeaderName.AUTHORIZATION),
                    request.getBodyAsBinaryData() == null ? "" : request.getBodyAsBinaryData().toString()));
            return Mono.just(response(request, status));
        };
    }

    private HttpResponse response(HttpRequest request, int status) {
        String payload = status < 300
                ? "{\"id\":\"stub-operation-id\",\"status\":\"Succeeded\"}"
                : "{\"error\":{\"code\":\"InvalidSender\",\"message\":\"unknown sender\"}}";
        HttpHeaders headers = new HttpHeaders().set(HttpHeaderName.CONTENT_TYPE, "application/json");
        if (status < 300) {
            headers.set(HttpHeaderName.fromString("Operation-Location"),
                    "https://stub.communication.azure.com/emails/operations/stub-operation-id");
        }
        return new HttpResponse(request) {
            @Override
            public int getStatusCode() {
                return status;
            }

            @Override
            @SuppressWarnings("deprecation")
            public String getHeaderValue(String name) {
                return headers.getValue(HttpHeaderName.fromString(name));
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }

            @Override
            public Flux<ByteBuffer> getBody() {
                return Flux.just(ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8)));
            }

            @Override
            public Mono<byte[]> getBodyAsByteArray() {
                return Mono.just(payload.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public Mono<String> getBodyAsString() {
                return Mono.just(payload);
            }

            @Override
            public Mono<String> getBodyAsString(Charset charset) {
                return Mono.just(payload);
            }
        };
    }

    private AcsEmailSender senderOver(HttpClient transport, int maxRetries) {
        EmailClient client = new EmailClientBuilder()
                .connectionString(CONNECTION_STRING)
                .httpClient(transport)
                .retryOptions(new RetryOptions(new ExponentialBackoffOptions()
                        .setMaxRetries(maxRetries)
                        .setBaseDelay(Duration.ofMillis(1))
                        .setMaxDelay(Duration.ofMillis(5))))
                .buildClient();
        return new AcsEmailSender(client, "DoNotReply@stub.azurecomm.net");
    }

    @Test
    @DisplayName("the message the service receives carries the sender, the recipient, the subject and the html")
    void theMessageIsAssembledInFull() {
        senderOver(transportAnswering(202), 1)
                .send("someone@example.test", "Account Verification", "<p>123456</p>");

        assertThat(requests).hasSize(1);
        Recorded posted = requests.get(0);
        assertThat(posted.method()).isEqualTo("POST");
        assertThat(posted.url()).contains("emails");
        assertThat(posted.body())
                .contains("DoNotReply@stub.azurecomm.net")
                .contains("someone@example.test")
                .contains("Account Verification")
                .contains("<p>123456</p>");
    }

    @Test
    @DisplayName("the request is signed from the connection string, so a wrong key fails here and not at the mailbox")
    void theRequestIsAuthenticated() {
        senderOver(transportAnswering(202), 1).send("someone@example.test", "Subject", "<p>body</p>");

        assertThat(requests.get(0).authorization()).startsWith("HMAC-SHA256");
    }

    @Test
    @DisplayName("beginSend posts before it returns, so dropping the poller drops nothing - and polls nothing either")
    void theSendHappensWithoutPollingForIt() {
        // The claim AcsEmailSender rests on: activation runs inside the SyncPoller's constructor.
        // Were it lazy instead, every verification mail would be composed, handed over and never
        // sent, and nothing else in this suite would notice. Exactly one request also says the
        // poller is not being walked to completion on the request thread.
        senderOver(transportAnswering(202), 1).send("someone@example.test", "Subject", "<p>body</p>");

        assertThat(requests).hasSize(1);
    }

    @Test
    @DisplayName("a message the service rejects is reported as a delivery failure rather than swallowed")
    void aRejectedMessageIsReported() {
        assertThatThrownBy(() -> senderOver(transportAnswering(400), 1)
                .send("someone@example.test", "Subject", "<p>body</p>"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining("would not accept");
    }

    @Test
    @DisplayName("a rejection is not replayed - one 400 is one request, however many retries are allowed")
    void aRejectionIsNotRetried() {
        // Retries are for failures that might go away. A 400 replayed is three identical
        // rejections, and on a provider that answers 5xx after accepting, three copies of the mail.
        assertThatThrownBy(() -> senderOver(transportAnswering(400), 3)
                .send("someone@example.test", "Subject", "<p>body</p>"))
                .isInstanceOf(EmailDeliveryException.class);

        assertThat(requests).hasSize(1);
    }

    @Test
    @DisplayName("a transient failure is retried, and the retries are bounded by the configured number")
    void aTransientFailureIsRetriedAndBounded() {
        // Two retries on top of the first attempt. Bounded matters more than the number: every
        // attempt is time a signup spends waiting, which is the argument for the queue.
        assertThatThrownBy(() -> senderOver(transportAnswering(503), 2)
                .send("someone@example.test", "Subject", "<p>body</p>"))
                .isInstanceOf(EmailDeliveryException.class);

        assertThat(requests).hasSize(3);
    }

    @Test
    @DisplayName("with nothing configured the sender is the disabled one, and sending is a no-op")
    void withoutCredentialsMailIsDroppedRatherThanFailing() {
        AcsMailProperties unconfigured = new AcsMailProperties("", "", Duration.ofSeconds(10), 1);

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThat(new EmailConfiguration().emailSender(unconfigured)).isInstanceOf(DisabledEmailSender.class);
        assertThatCode(() -> new DisabledEmailSender().send("someone@example.test", "Subject", "<p>body</p>"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("half-configured is not configured - a connection string with no sender address sends nothing")
    void aSenderAddressIsRequiredToo() {
        AcsMailProperties halfConfigured =
                new AcsMailProperties(CONNECTION_STRING, "  ", Duration.ofSeconds(10), 1);

        assertThat(halfConfigured.isConfigured()).isFalse();
        assertThat(new EmailConfiguration().emailSender(halfConfigured)).isInstanceOf(DisabledEmailSender.class);
    }

    @Test
    @DisplayName("a configured account gets the real sender, built without reaching the network to do it")
    void aConfiguredAccountGetsTheRealSender() {
        AcsMailProperties configured = new AcsMailProperties(
                CONNECTION_STRING, "DoNotReply@stub.azurecomm.net", Duration.ofSeconds(10), 1);

        assertThat(new EmailConfiguration().emailSender(configured)).isInstanceOf(AcsEmailSender.class);
    }
}
