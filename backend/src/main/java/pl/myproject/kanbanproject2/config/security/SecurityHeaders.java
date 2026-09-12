package pl.myproject.kanbanproject2.config.security;

/**
 * The response headers that tell a browser what this application is allowed to do.
 *
 * <p>They are here rather than inline in {@link SecurityConfiguration} because each one is a claim
 * about what the client actually loads, and the claims need somewhere to be written down and
 * checked. {@code SecurityHeadersTest} reads them; the ZAP baseline scan reads the running
 * application; and {@code CspMatchesTheClientTest} reads the <em>client</em> and fails when the two
 * disagree, which is the failure mode a policy like this really has - not being wrong on the day it
 * is written, but being right until somebody adds a font.
 *
 * <p><b>Why now.</b> {@code dast.yml} has run an OWASP ZAP baseline sweep on a schedule for some
 * time and files what it finds as a GitHub issue. Nobody reads it. The first one sat open for eight
 * days, and its first finding was that this application sends no {@code Content-Security-Policy}
 * header at all - which is worth knowing about an application that serves its own bundle from the
 * same origin as its API and, since the attachment work, streams uploaded files from it too.
 */
public final class SecurityHeaders {

    /**
     * What the browser may load, and from where.
     *
     * <p>Every source here is something the client provably fetches, and each is narrow for a
     * reason:
     *
     * <ul>
     *   <li>{@code script-src 'self' www.google.com www.gstatic.com} - and <b>no
     *       {@code 'unsafe-inline'}</b>, which is the half of a CSP that is worth anything. The
     *       bundle is a module script Vite emits with a hashed name; {@code index.html} carries no
     *       inline script and this is what keeps it that way. The two Google hosts are reCAPTCHA:
     *       {@code recaptchaLoader} injects {@code www.google.com/recaptcha/api.js}, which then
     *       pulls its own implementation from {@code www.gstatic.com}.</li>
     *   <li>{@code style-src} keeps {@code 'unsafe-inline'}, and that is a trade rather than an
     *       oversight. Removing it means nonces, which means a server-rendered shell this
     *       application does not have - Spring serves Vite's {@code index.html} as a static file.
     *       Injected CSS is also a far weaker vector than injected script: it can restyle a page,
     *       not read a token. {@code fonts.googleapis.com} is there because
     *       {@code styles/index.css} opens with an {@code @import url(...)} of it, and the font
     *       files that stylesheet then references come from {@code fonts.gstatic.com}.</li>
     *   <li>{@code img-src} allows {@code data:} and {@code blob:} because avatars and attachment
     *       previews are read through {@code fetch} - the routes are authenticated, so an
     *       {@code <img src>} pointing at one would arrive without a token - and handed to the DOM
     *       as object URLs.</li>
     *   <li>{@code connect-src 'self'} covers the WebSocket too. CSP level 3 matches {@code ws://}
     *       and {@code wss://} against {@code 'self'} when the host is the same, which is exactly
     *       the chat and board-sync sockets, both of which point at
     *       {@code window.location.origin}.</li>
     *   <li>{@code frame-src www.google.com} is the reCAPTCHA challenge, which is an iframe.
     *       {@code frame-ancestors 'none'} is the other direction and says nobody may frame
     *       <em>us</em> - the same claim as the {@code X-Frame-Options: DENY} Spring Security
     *       already sends, in the header that superseded it.</li>
     *   <li>{@code object-src 'none'}, {@code base-uri 'self'} and {@code form-action 'self'} close
     *       the three things a CSP is nearly always wrong to leave open: plugin content, a
     *       {@code <base>} tag that silently re-points every relative URL on the page, and a form
     *       that posts somewhere else.</li>
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
     * embeds.
     *
     * <p>An empty allow-list is the point: a Kanban board has no business asking for a camera, and
     * the value of saying so is that an injected script or a compromised third-party frame cannot
     * ask either. reCAPTCHA is the only cross-origin frame here and needs none of these.
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

    private SecurityHeaders() {
    }
}
