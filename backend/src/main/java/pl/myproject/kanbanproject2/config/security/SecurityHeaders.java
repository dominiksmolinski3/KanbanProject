package pl.myproject.kanbanproject2.config.security;

public final class SecurityHeaders {
    public static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

    public static final long STRICT_TRANSPORT_SECURITY_MAX_AGE = 31536000L;

    private SecurityHeaders() {
    }
}
