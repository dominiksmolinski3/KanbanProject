package pl.myproject.kanbanproject2.config;

/**
 * The paths React Router serves from the bundled single-page app.
 *
 * <p>Spring knows nothing about client-side routing: a browser asking for one of these directly —
 * a refresh, a bookmark, a shared link — is a request Spring has to answer with the app shell
 * rather than a 404. Two places need the same list to make that work, so it lives here rather
 * than being written out twice:
 *
 * <ul>
 *   <li>{@code WebConfig} forwards each one to {@code /index.html}.</li>
 *   <li>{@code SecurityConfiguration} permits each one, because the shell is public even where
 *       the route behind it is not — the app's data sits under {@code /api/**}, which stays
 *       authenticated, and the client decides what to render once it has a token.</li>
 * </ul>
 *
 * <p>Adding a route to {@code App.jsx} means adding it here too, or the deep link 403s.
 */
public final class SpaRoutes {

    /** Every top-level path {@code App.jsx} declares a {@code <Route>} for, except {@code /}. */
    public static final String[] ALL = {"/board", "/users", "/sessions"};

    private SpaRoutes() {
    }
}
