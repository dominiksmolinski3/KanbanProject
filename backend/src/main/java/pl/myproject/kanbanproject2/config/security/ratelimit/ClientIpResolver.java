package pl.myproject.kanbanproject2.config.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Works out which address to bill a request to.
 *
 * <p>Behind a reverse proxy {@code getRemoteAddr()} is the proxy, so keying on it would put the
 * whole internet in one bucket. {@code X-Forwarded-For} carries the client instead, but only the
 * entries a trusted proxy appended can be believed: each proxy appends the address it received
 * the request <em>from</em>, to the right-hand end, and leaves whatever the client sent in place
 * to its left. A client that sends {@code X-Forwarded-For: 1.2.3.4} arrives at the app as
 * {@code 1.2.3.4, <real client>} — so the entry to read is counted from the right, never the left.
 *
 * @see AuthRateLimitProperties#trustedProxyCount()
 */
@Component
public class ClientIpResolver {

    static final String UNKNOWN = "unknown";

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    /** Long enough for any address form, short enough that a bad header cannot bloat a cache key. */
    private static final int MAX_KEY_LENGTH = 64;

    private final int trustedProxyCount;

    public ClientIpResolver(AuthRateLimitProperties properties) {
        this.trustedProxyCount = properties.trustedProxyCount();
    }

    public String resolve(HttpServletRequest request) {
        if (trustedProxyCount == 0) {
            return normalise(request.getRemoteAddr());
        }

        String header = request.getHeader(X_FORWARDED_FOR);
        if (header != null) {
            String[] hops = header.split(",");
            int index = hops.length - trustedProxyCount;
            if (index >= 0 && index < hops.length) {
                String candidate = normalise(hops[index]);
                if (!UNKNOWN.equals(candidate)) {
                    return candidate;
                }
            }
        }

        // Either the header is missing or the request took fewer hops than configured, which means
        // it did not come through the expected chain. Nothing in the header is trustworthy, so fall
        // back to the one address the container observed for itself.
        return normalise(request.getRemoteAddr());
    }

    private static String normalise(String value) {
        if (value == null) {
            return UNKNOWN;
        }

        String address = stripPort(value.trim());
        if (address.isEmpty()) {
            return UNKNOWN;
        }

        String key = address.toLowerCase(Locale.ROOT);
        return key.length() > MAX_KEY_LENGTH ? key.substring(0, MAX_KEY_LENGTH) : key;
    }

    /**
     * Proxies vary on whether they append a port. Drops it so {@code 1.2.3.4:5678} and
     * {@code 1.2.3.4} share a bucket, and unwraps the {@code [::1]:8080} form IPv6 uses.
     */
    private static String stripPort(String address) {
        if (address.startsWith("[")) {
            int closing = address.indexOf(']');
            return closing > 0 ? address.substring(1, closing) : address;
        }

        int colon = address.indexOf(':');
        // A second colon means a bare IPv6 address, where every colon belongs to the address itself.
        if (colon > 0 && address.indexOf(':', colon + 1) < 0) {
            return address.substring(0, colon);
        }
        return address;
    }
}
