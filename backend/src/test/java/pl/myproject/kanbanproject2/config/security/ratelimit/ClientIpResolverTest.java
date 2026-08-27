package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitTestSupport.properties;

class ClientIpResolverTest {

    private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    @DisplayName("with no trusted proxy the socket address is used and the header is ignored")
    void ignoresForwardedForWhenNoProxyIsTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(properties(0));

        String resolved = resolver.resolve(request("203.0.113.9", "1.2.3.4"));

        assertThat(resolved).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("behind one proxy the entry that proxy appended wins, not the one the client sent")
    void takesTheEntryTheTrustedProxyAppended() {
        ClientIpResolver resolver = new ClientIpResolver(properties(1));

        // The client forged "1.2.3.4"; ingress appended the address it actually saw.
        String resolved = resolver.resolve(request("10.0.0.5", "1.2.3.4, 198.51.100.7"));

        assertThat(resolved).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("a forged chain cannot push the real client out of the key")
    void forgedChainDoesNotChangeTheKey() {
        ClientIpResolver resolver = new ClientIpResolver(properties(1));

        String first = resolver.resolve(request("10.0.0.5", "9.9.9.1, 198.51.100.7"));
        String second = resolver.resolve(request("10.0.0.5", "9.9.9.2, 9.9.9.3, 198.51.100.7"));

        assertThat(first).isEqualTo(second).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("with two proxies trusted the second entry from the right wins")
    void countsHopsFromTheRight() {
        ClientIpResolver resolver = new ClientIpResolver(properties(2));

        String resolved = resolver.resolve(request("10.0.0.5", "1.2.3.4, 198.51.100.7, 203.0.113.1"));

        assertThat(resolved).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("a request with fewer hops than configured falls back to the socket address")
    void fallsBackWhenTheChainIsShorterThanConfigured() {
        ClientIpResolver resolver = new ClientIpResolver(properties(2));

        String resolved = resolver.resolve(request("10.0.0.5", "198.51.100.7"));

        assertThat(resolved).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("a missing header falls back to the socket address even when a proxy is expected")
    void fallsBackWhenTheHeaderIsMissing() {
        ClientIpResolver resolver = new ClientIpResolver(properties(1));

        String resolved = resolver.resolve(request("10.0.0.5", null));

        assertThat(resolved).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("a blank entry falls back rather than bucketing everyone under the empty string")
    void fallsBackOnABlankEntry() {
        ClientIpResolver resolver = new ClientIpResolver(properties(1));

        String resolved = resolver.resolve(request("10.0.0.5", "1.2.3.4,   "));

        assertThat(resolved).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("an appended port does not split one caller across two buckets")
    void stripsThePort() {
        ClientIpResolver resolver = new ClientIpResolver(properties(1));

        assertThat(resolver.resolve(request("10.0.0.5", "198.51.100.7:44321")))
                .isEqualTo(resolver.resolve(request("10.0.0.5", "198.51.100.7")));
    }

    @Test
    @DisplayName("IPv6 survives in both its bare and its bracketed form")
    void handlesIpv6() {
        ClientIpResolver resolver = new ClientIpResolver(properties(1));

        assertThat(resolver.resolve(request("10.0.0.5", "2001:db8::1"))).isEqualTo("2001:db8::1");
        assertThat(resolver.resolve(request("10.0.0.5", "[2001:db8::1]:44321"))).isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("case differences do not buy a second bucket")
    void isCaseInsensitive() {
        ClientIpResolver resolver = new ClientIpResolver(properties(1));

        assertThat(resolver.resolve(request("10.0.0.5", "2001:DB8::AB")))
                .isEqualTo(resolver.resolve(request("10.0.0.5", "2001:db8::ab")));
    }

    @Test
    @DisplayName("an over-long header value is truncated instead of becoming an unbounded key")
    void truncatesAnOverLongValue() {
        ClientIpResolver resolver = new ClientIpResolver(properties(1));

        String resolved = resolver.resolve(request("10.0.0.5", "a".repeat(500)));

        assertThat(resolved).hasSize(64);
    }

    @Test
    @DisplayName("a request with no address at all still produces a usable key")
    void handlesAMissingRemoteAddress() {
        ClientIpResolver resolver = new ClientIpResolver(properties(0));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        assertThat(resolver.resolve(request)).isEqualTo(ClientIpResolver.UNKNOWN);
    }
}
