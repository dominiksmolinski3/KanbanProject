/**
 * The Content-Security-Policy, checked the only way a CSP can be checked: by a browser enforcing
 * it while the application runs.
 *
 * A policy is a claim about what the client loads, and nothing that reads source code can settle
 * it. `CspMatchesTheClientTest` catches a host somebody adds to a stylesheet; jsdom enforces no CSP
 * at all, so Jest cannot see a violation; and a passing e2e suite only says nothing broke *enough*
 * to fail an assertion - a blocked font, a blocked image or a blocked background request leaves
 * every test green and a console full of refusals.
 *
 * So this listens for the refusals themselves. `securitypolicyviolation` fires on the document for
 * every resource the policy stops, and the listener is installed in `onBeforeLoad` - before any
 * application code runs, because a violation caused by the bundle's own first import would
 * otherwise happen before anything was watching.
 *
 * The first draft of the CSP was verified by running this suite and finding it 100% red, which
 * turned out to be a mistake in the test environment rather than in the policy: the stack had been
 * built with a reCAPTCHA site key and no server-side captcha, so the widget rendered and held the
 * Sign In button disabled. That is SEC-06's pairing from the other side, and it is written here
 * because the next person to see a red suite under a new CSP will reach for the CSP first.
 */
describe('Content Security Policy', () => {
  const violations = [];

  const watchForViolations = (win) => {
    win.document.addEventListener('securitypolicyviolation', (event) => {
      violations.push(
        `${event.violatedDirective} refused ${event.blockedURI || '(inline)'}`,
      );
    });
  };

  beforeEach(() => {
    violations.length = 0;
  });

  it('serves the policy and the cross-origin headers on the shell', () => {
    cy.request('/').then((response) => {
      const csp = response.headers['content-security-policy'];
      expect(csp, 'Content-Security-Policy').to.be.a('string');
      // The two that carry the weight. Everything else in the policy is a list of hosts that can
      // be argued about; these two are what an injected <script> runs into.
      expect(csp).to.include("object-src 'none'");
      expect(csp).to.not.include("'unsafe-inline' https://www.google.com");
      expect(response.headers['permissions-policy']).to.be.a('string');
      expect(response.headers['cross-origin-opener-policy']).to.equal('same-origin');
      expect(response.headers['cross-origin-resource-policy']).to.equal('same-origin');
    });
  });

  it('loads the sign-in screen without the browser refusing anything', () => {
    cy.visit('/', { onBeforeLoad: watchForViolations });
    cy.get('input[type="email"]').should('exist');
    // A moment for the stylesheet's @import and the fonts it pulls, which are the requests most
    // likely to be refused and the least likely to fail a test.
    cy.wait(1000);
    cy.then(() => {
      expect(violations, 'CSP violations on the sign-in screen').to.deep.equal([]);
    });
  });

  it('would notice a refusal - the control for the two assertions above', () => {
    // Three tests that record no violations are only evidence if a violation would have been
    // recorded, and "the listener is attached" is a reason to believe that rather than a
    // demonstration of it. So: ask the page for an image from a host img-src does not name, and
    // watch the same listener catch it. If this test ever passes by finding nothing, the two above
    // are worth nothing either.
    cy.visit('/', { onBeforeLoad: watchForViolations });
    cy.window().then((win) => {
      const image = win.document.createElement('img');
      image.src = 'https://example.com/blocked-by-csp.png';
      win.document.body.appendChild(image);
    });
    cy.wrap(null, { timeout: 5000 }).should(() => {
      expect(violations.join('\n')).to.include('img-src');
    });
  });

  it('loads the board without the browser refusing anything', () => {
    // The board is where the rest of it happens: the API calls that connect-src governs, the
    // SockJS connection that 'self' has to match on a ws:// URL, the avatars that arrive as blob
    // URLs, and every image and font the sign-in screen never reaches.
    cy.loginAsTestUser();
    cy.visit('/board', { onBeforeLoad: watchForViolations });
    cy.get('[data-testid="open-add-task-form"]', { timeout: 15000 }).should('exist');
    cy.wait(2000);
    cy.then(() => {
      expect(violations, 'CSP violations on the board').to.deep.equal([]);
    });
  });
});
