package pl.myproject.kanbanproject2.config.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class ClientIpResolver {

    static final String UNKNOWN = "unknown";

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

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

    private static String stripPort(String address) {
        if (address.startsWith("[")) {
            int closing = address.indexOf(']');
            return closing > 0 ? address.substring(1, closing) : address;
        }

        int colon = address.indexOf(':');
        if (colon > 0 && address.indexOf(':', colon + 1) < 0) {
            return address.substring(0, colon);
        }
        return address;
    }
}
