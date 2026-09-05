beforeEach(() => {
  cy.visit('/');
});

describe('Login Functionality', () => {
  it('logs in with valid credentials', () => {
    cy.fixture('test-account').then((account) => {
      cy.get('input[type="email"]').first().type(account.email);
      cy.get('input[type="password"]').first().type(account.password);
      cy.contains('button', 'Sign In').click();
      cy.url().should('include', '/board');
    });
  });

  it('shows error with invalid credentials', () => {
    cy.get('input[type="email"]').first().type('wrong@example.com');
    cy.get('input[type="password"]').first().type('wrongpassword');
    cy.contains('button', 'Sign In').click();
    cy.contains('Invalid email or password').should('be.visible');
    cy.url().should('not.include', '/board');
  });

  it('navigates to registration page', () => {
    cy.get('.tabs > :nth-child(2)').click();
    cy.contains('Register').should('be.visible');
  });

  it('validates required fields', () => {
    cy.contains('button', 'Sign In').click();
    cy.get(':nth-child(1) > input').should('be.visible');
    cy.get(':nth-child(2) > input').should('be.visible');
  });
});