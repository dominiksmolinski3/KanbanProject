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
    cy.wait(1000);
    cy.then(() => {
      expect(violations, 'CSP violations on the sign-in screen').to.deep.equal([]);
    });
  });

  it('would notice a refusal - the control for the two assertions above', () => {
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
    cy.loginAsTestUser();
    cy.visit('/board', { onBeforeLoad: watchForViolations });
    cy.get('[data-testid="open-add-task-form"]', { timeout: 15000 }).should('exist');
    cy.wait(2000);
    cy.then(() => {
      expect(violations, 'CSP violations on the board').to.deep.equal([]);
    });
  });
});
