import { defineConfig } from "cypress";

export default defineConfig({
  component: {
    devServer: {
      framework: "react",
      bundler: "vite",
    },
  },

  e2e: {
    baseUrl: 'http://localhost:5173',

    /*
     * Cypress's own default, written out so that the directory beside it can be explained.
     *
     * `cypress/replicas/` holds the specs that need more than a running stack.
     * `cross-replica-sync.cy.js` needs two API replicas - the `replicas` compose profile - because
     * what it asserts is that a board event published by one of them reaches a browser connected
     * to the other, and at one replica that is not a claim about anything. Leaving it in this
     * pattern would fail `npm run cypress:run` for everybody running the ordinary stack, and the
     * usual way out - skipping when the second replica is missing - is the failure this repository
     * has already filed twice, most expensively as a security sweep that reported success in five
     * seconds having scanned nothing.
     *
     * So it is neither skipped nor in the default run: `npm run cypress:run:replicas` runs it, and
     * kanban-ci.yml's e2e job runs that as a step of its own after bringing the profile up.
     * `CrossReplicaStackTest` fails the build if that step goes, which is the only thing that can
     * see the coupling.
     */
    specPattern: 'cypress/e2e/**/*.cy.{js,jsx,ts,tsx}',

    /*
     * Cypress removes six Content-Security-Policy directives from the responses it proxies -
     * script-src, script-src-elem, default-src, form-action, child-src and frame-src - because they
     * are the ones that can stop it driving the application. Everything else in a policy it leaves
     * alone and the browser enforces normally, which is more than it first appears but is not the
     * whole policy: with those six stripped, a suite that passes says nothing about the directive
     * an injected script would actually run into.
     *
     * Naming them here stops the stripping, so the application under test runs under the same
     * policy a browser gets in production. It works because `script-src 'self'` permits exactly what
     * Cypress injects - its own code, served from the application's origin through the proxy - which
     * is a thing worth checking rather than assuming, and was checked: the full suite passes with
     * this set.
     *
     * `frame-ancestors` is stripped by Cypress regardless and cannot be named here, because the
     * application under test genuinely does run in an iframe. That one directive is verified by
     * reading the header instead (`csp.cy.js`, first test).
     *
     * The reason any of this is worth the words: a CSP is a claim about what the client loads, and
     * a suite that "passes with a CSP" that was quietly removed is the most convincing possible
     * evidence for nothing at all. `cypress/e2e/security/csp.cy.js` carries a control test that
     * provokes a real violation, so a listener that cannot fire is distinguishable from a page that
     * causes no violations.
     */
    experimentalCspAllowList: [
      'script-src',
      'script-src-elem',
      'default-src',
      'form-action',
      'child-src',
      'frame-src',
    ],

    setupNodeEvents(on, config) {
      // implement node event listeners here
    },
  },
});
