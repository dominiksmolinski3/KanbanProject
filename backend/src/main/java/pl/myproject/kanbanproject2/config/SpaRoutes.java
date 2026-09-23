package pl.myproject.kanbanproject2.config;

/**
 * The paths React Router serves from the single-page app. nginx's {@code try_files} now serves the
 * shell with no list at all, so these routes are deliberately <em>not</em> enumerated in the edge
 * config - a list in two places is the drift every guard here exists to catch. What survives is the
 * claim itself, read by two guards: {@code .github/scripts/deployed_contract_check.py}, which asks
 * the deployed origin whether each route still answers with the shell, and
 * {@code SpaRoutesMatchTheClientTest}, which fails the build when this array and
 * {@code frontend/src/App.jsx} disagree.
 */
public final class SpaRoutes {

    /** Every top-level path {@code App.jsx} declares a {@code <Route>} for, except {@code /}. */
    public static final String[] ALL = {"/board", "/users", "/sessions", "/activity", "/flow"};

    private SpaRoutes() {
    }
}
