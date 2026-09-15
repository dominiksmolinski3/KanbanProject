package pl.myproject.kanbanproject2.config;

/**
 * The paths React Router serves from the single-page app.
 *
 * <p>This list used to be load-bearing twice over: {@code WebConfig} forwarded each route to
 * {@code /index.html} and {@code SecurityConfiguration} permitted each one, so a route added to
 * {@code App.jsx} and forgotten here was a deep link that 403'd. Neither is true now. nginx serves
 * the shell with a {@code try_files} that needs no list at all — which is why the routes are
 * deliberately <em>not</em> enumerated in the edge config, a list in two places being the drift
 * every guard in this repository exists to catch.
 *
 * <p>What survives is the claim itself, and it has two readers:
 *
 * <ul>
 *   <li>{@code .github/scripts/deployed_contract_check.py} reads this array out of the Java and
 *       asks the deployed origin whether each route still answers with the shell. That is the one
 *       direction nothing else covers — every other guard here compares source with source.</li>
 *   <li>{@code SpaRoutesMatchTheClientTest} reads {@code frontend/src/App.jsx} and fails the build
 *       when the two disagree. That check is <em>new with the split</em>, and it is what stops
 *       this from rotting quietly: while Spring served the shell, a route missing from here
 *       produced a 403 somebody would notice, and now it would produce nothing at all except a
 *       contract sweep that silently checks one route fewer.</li>
 * </ul>
 */
public final class SpaRoutes {

    /** Every top-level path {@code App.jsx} declares a {@code <Route>} for, except {@code /}. */
    public static final String[] ALL = {"/board", "/users", "/sessions", "/activity"};

    private SpaRoutes() {
    }
}
