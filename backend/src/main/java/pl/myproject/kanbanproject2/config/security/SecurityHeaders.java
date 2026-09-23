package pl.myproject.kanbanproject2.config.security;

public final class SecurityHeaders {
    public static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' https://www.google.com https://www.gstatic.com",
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
            "font-src 'self' https://fonts.gstatic.com data:",
            "img-src 'self' data: blob:",
            "connect-src 'self'",
            "frame-src https://www.google.com",
            "worker-src 'self' blob:",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    public static final String PERMISSIONS_POLICY = String.join(", ",
            "accelerometer=()",
            "camera=()",
            "display-capture=()",
            "geolocation=()",
            "gyroscope=()",
            "magnetometer=()",
            "microphone=()",
            "payment=()",
            "usb=()");

    public static final long STRICT_TRANSPORT_SECURITY_MAX_AGE = 31536000L;

    private SecurityHeaders() {
    }
}
