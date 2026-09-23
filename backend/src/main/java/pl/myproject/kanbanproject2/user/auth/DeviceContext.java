package pl.myproject.kanbanproject2.user.auth;

public record DeviceContext(String ipAddress, String userAgent) {
    public static final int MAX_USER_AGENT_LENGTH = 255;

    public static final int MAX_IP_ADDRESS_LENGTH = 45;

    public DeviceContext {
        ipAddress = trimmedToNull(ipAddress, MAX_IP_ADDRESS_LENGTH);
        userAgent = trimmedToNull(userAgent, MAX_USER_AGENT_LENGTH);
    }

    public static DeviceContext unknown() {
        return new DeviceContext(null, null);
    }

    // ClientIpResolver answers "unknown" rather than null; that names no device.
    public boolean isIdentifiable() {
        return ipAddress != null && userAgent != null && !"unknown".equals(ipAddress);
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
