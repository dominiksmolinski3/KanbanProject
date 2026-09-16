package pl.myproject.kanbanproject2.config.security;

/**
 * The response headers that tell a browser what this application is allowed to do. Held here
 * rather than inline in {@link SecurityConfiguration} because each is a claim about what the
 * client actually loads: {@code SecurityHeadersTest} reads them, and
 * {@code CspMatchesTheClientTest} reads the <em>client</em> and fails when the two disagree - the
 * failure mode this kind of policy has, going wrong not on the day it's written but the day
 * somebody adds a font.
 */
public final class SecurityHeaders {

    /**
     * What the browser may load, and from where. Every source here is something the client
     * provably fetches, and each is narrow for a reason:
     *
     * <ul>
     *   <li>{@code script-src 'self' www.google.com www.gstatic.com} - no {@code 'unsafe-inline'},
     *       the half of a CSP worth anything, since the bundle is a hashed Vite module and
     *       {@code index.html} carries no inline script. The Google hosts are reCAPTCHA loading
     *       its implementation from gstatic.</li>
     *   <li>{@code style-src} keeps {@code 'unsafe-inline'} deliberately - removing it needs nonces,
     *       which needs a server-rendered shell this application does not have, and injected CSS
     *       can only restyle a page, not read a token. {@code fonts.googleapis.com} /
     *       {@code fonts.gstatic.com} back the stylesheet's own {@code @import}.</li>
     *   <li>{@code img-src} allows {@code data:} and {@code blob:} because avatars and attachment
     *       previews are authenticated, fetched, and handed to the DOM as object URLs.</li>
     *   <li>{@code connect-src 'self'} covers the WebSocket too - CSP level 3 matches
     *       {@code ws://}/{@code wss://} against {@code 'self'} when the host matches, which is the
     *       chat and board-sync sockets.</li>
     *   <li>{@code frame-src www.google.com} is the reCAPTCHA challenge iframe;
     *       {@code frame-ancestors 'none'} is the other direction, superseding
     *       {@code X-Frame-Options: DENY}.</li>
     *   <li>{@code object-src 'none'}, {@code base-uri 'self'} and {@code form-action 'self'} close
     *       plugin content, a re-pointed {@code <base>} tag, and a form posting elsewhere.</li>
     * </ul>
     */
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

    /**
     * The browser features this application never uses, switched off for it and for anything it
     * embeds. An empty allow-list is the point: an injected script or compromised third-party frame
     * cannot ask for a camera either.
     */
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

    /**
     * How long a browser should refuse to reach this origin over anything but HTTPS - a year, the
     * conventional value. What matters is that the header is sent at all: Spring's default writer
     * gates on {@code request.isSecure()}, which is always false behind the Container Apps ingress
     * (declared {@code transport = "http"}), so the default had fired on nothing until
     * {@code SecurityConfiguration} started writing it unconditionally. Safe rather than sloppy - a
     * user agent ignores HSTS received over plain HTTP. {@code preload} stays off since this origin
     * is a subdomain of {@code azurecontainerapps.io}, which this deployment does not own.
     */
    public static final long STRICT_TRANSPORT_SECURITY_MAX_AGE = 31536000L;

    private SecurityHeaders() {
    }
}
