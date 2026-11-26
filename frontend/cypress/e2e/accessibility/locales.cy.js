const locales = ['pl', 'de', 'es', 'fr', 'it', 'ja', 'ru', 'ar'];

describe('Accessibility in every other language, in a real browser', () => {
  beforeEach(() => {
    cy.prepareAxe();
  });

  locales.forEach((locale) => {
    describe(locale, () => {
      it('the sign-in page has no axe violations', () => {
        cy.visit(`/?lng=${locale}`);
        cy.get('input[type="email"]', { timeout: 10000 }).should('be.visible');
        cy.get('html').should('have.attr', 'lang', locale);
        cy.expectNoAxeViolations();
      });

      it('the board has no axe violations', () => {
        cy.loginAsTestUser();
        cy.visit(`/board?lng=${locale}`);
        cy.get('[data-testid="open-add-board-item-form"]', { timeout: 10000 }).should('be.visible');
        cy.get('html').should('have.attr', 'lang', locale);
        cy.expectNoAxeViolations();
      });
    });
  });
});
