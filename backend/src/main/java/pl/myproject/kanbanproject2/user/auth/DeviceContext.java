package pl.myproject.kanbanproject2.user.auth;

/**
 * Where a session is being used from, as far as one HTTP request can say.
 *
 * <p>Both values are advisory and both are attacker-controlled on an unauthenticated route, which
 * is why they are a label and never a check. Nothing here decides whether a token is valid; the row
 * decides that. This is what a person reads when they are looking at their own list of sessions and
 * trying to work out which one is the browser they are not sitting in front of.
 *
 * <p>The address is resolved by {@code ClientIpResolver} — the same answer the rate limiter bills,
 * so a deployment behind a proxy gets the caller's address rather than the proxy's in both places,
 * and a deployment that is not configured for proxies ignores {@code X-Forwarded-For} in both.
 *
 * <p>The user agent is truncated rather than rejected. It is free text of unbounded length, and the
 * column is 255 characters; a header longer than that is a browser being verbose or somebody
 * probing, and neither is a reason to refuse a login.
 */
public record DeviceContext(String ipAddress, String userAgent) {

    /** Matches {@code user_agent varchar(255)} in {@code V9}. */
    public static final int MAX_USER_AGENT_LENGTH = 255;

    /** Matches {@code ip_address varchar(45)} — a full IPv6 address in its longest textual form. */
    public static final int MAX_IP_ADDRESS_LENGTH = 45;

    public DeviceContext {
        ipAddress = trimmedToNull(ipAddress, MAX_IP_ADDRESS_LENGTH);
        userAgent = trimmedToNull(userAgent, MAX_USER_AGENT_LENGTH);
    }

    /**
     * For callers with no request to read — the scheduled sweeps, and tests that are asking about
     * rotation rather than about devices. A session with nothing recorded is listed like any other;
     * it just has nothing to say about itself, which is also true of every row issued before
     * {@code V9}.
     */
    public static DeviceContext unknown() {
        return new DeviceContext(null, null);
    }

    private static String trimmedToNull(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
