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

describe('Accessibility, in a real browser', () => {
  beforeEach(() => {
    cy.prepareAxe();
  });

  themes.forEach((theme) => {
    describe(`${theme} theme, signed out`, () => {
      signedOut.forEach(({ name, path, ready }) => {
        it(`${name} has no axe violations`, () => {
          cy.visitThemed(path, theme, ready);
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
          cy.visitThemed(path, theme, ready);
          cy.expectNoAxeViolations();
        });
      });
    });
  });
});
