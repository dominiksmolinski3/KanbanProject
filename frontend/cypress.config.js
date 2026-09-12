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
