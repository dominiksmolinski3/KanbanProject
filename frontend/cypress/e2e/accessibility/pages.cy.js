const themes = ['light', 'dark'];

const signedOut = [
  { name: 'the sign-in page', path: '/', ready: 'input[type="email"]' },
  { name: 'a page that does not exist', path: '/no-such-page', ready: 'h1' },
];

const signedIn = [
  { name: 'the board', path: '/board', ready: '[data-testid="open-add-board-item-form"]' },
  { name: 'the users page', path: '/users', ready: 'h1' },
  { name: 'the activity feed', path: '/activity', ready: 'h1' },
  { name: 'flow metrics', path: '/flow', ready: 'h1' },
  { name: 'the sessions page', path: '/sessions', ready: 'h1' },
  { name: 'the account page', path: '/account', ready: 'h1' },
  { name: 'messages', path: '/messages', ready: 'h1' },
  { name: 'device linking', path: '/link', ready: 'h1' },
];

const visitIn = (theme, path, ready) => {
  cy.visit(path, {
    onBeforeLoad(win) {
      win.localStorage.setItem('kanban-theme', theme);
    },
  });
  cy.get('html').should('have.attr', 'data-theme', theme);
  cy.get(ready, { timeout: 10000 }).should('be.visible');
};

describe('Accessibility, in a real browser', () => {
  beforeEach(() => {
    cy.intercept({ resourceType: 'document' }, (req) => {
      req.on('response', (res) => {
        delete res.headers['content-security-policy'];
      });
    });
  });

  themes.forEach((theme) => {
    describe(`${theme} theme, signed out`, () => {
      signedOut.forEach(({ name, path, ready }) => {
        it(`${name} has no axe violations`, () => {
          visitIn(theme, path, ready);
          cy.expectNoAxeViolations();
        });
      });
    });

    describe(`${theme} theme, signed in`, () => {
      beforeEach(() => {
        cy.loginAsTestUser();
      });

      signedIn.forEach(({ name, path, ready }) => {
        it(`${name} has no axe violations`, () => {
          visitIn(theme, path, ready);
          cy.expectNoAxeViolations();
        });
      });

      it('the board with the add column and row form open has no axe violations', () => {
        visitIn(theme, '/board', '[data-testid="open-add-board-item-form"]');
        cy.get('[data-testid="open-add-board-item-form"]').click();
        cy.get('[data-testid="add-row-column-tab-column"]').should('be.visible');
        cy.expectNoAxeViolations();
      });
    });
  });
});
