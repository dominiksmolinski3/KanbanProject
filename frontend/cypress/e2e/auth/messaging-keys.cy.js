const KEY_DATABASE = 'kanban-messaging-keys';

const panel = () => cy.contains('h2', 'Encrypted messages').closest('section');

const saveTheRecoveryKey = () => {
  cy.get('[role="dialog"]').within(() => {
    cy.get('[aria-label="Recovery key"] span').should('have.length', 12).then(($groups) => {
      const groups = [...$groups].map((group) => group.textContent);
      cy.wrap(groups.join('')).as('recoveryKey');
      cy.get('input[type="checkbox"]').check();
      cy.get('#messaging-key-check').type(groups.at(-1));
    });
    cy.contains('button', 'Finish setup').click();
  });
  cy.get('[role="dialog"]', { timeout: 30000 }).should('not.exist');
};

const forgetThisBrowser = () => cy.window().then((win) => new Cypress.Promise((resolve) => {
  const request = win.indexedDB.deleteDatabase(KEY_DATABASE);
  request.onsuccess = resolve;
  request.onerror = resolve;
}));

describe('Encrypted messaging keys', () => {
  beforeEach(() => {
    cy.loginAsTestUser();
    cy.visit('/sessions');
  });

  it('sets this browser up behind a recovery key, and that key alone brings it back', () => {
    panel().should(($panel) => expect($panel.text()).to.match(/Set up on this browser|Start over/));
    panel().then(($panel) => {
      const button = $panel.text().includes('Start over') ? 'Start over' : 'Set up on this browser';
      cy.wrap($panel).contains('button', button).click();
      cy.get('[role="dialog"]').contains('button', button).click();
    });
    saveTheRecoveryKey();

    panel().should('contain', 'This browser is set up for encrypted messages.');
    panel().find('code').invoke('text').should('match', /^([0-9a-f]{4} ){7}[0-9a-f]{4}$/).then((fingerprint) => {
      cy.get('.device-current .device-messaging-key').should('have.text', fingerprint);
    });

    forgetThisBrowser();
    cy.reload();
    panel().contains('button', 'Use my recovery key').click();

    cy.get('[role="dialog"]').within(() => {
      cy.get('textarea').type('A'.repeat(48));
      cy.contains('button', 'Set up this browser').click();
      cy.contains('That recovery key does not match this account.', { timeout: 20000 });
    });
    cy.get('@recoveryKey').then((recoveryKey) => {
      cy.get('[role="dialog"] textarea').clear().type(recoveryKey.toLowerCase().match(/.{4}/g).join(' '));
    });
    cy.get('[role="dialog"]').contains('button', 'Set up this browser').click();
    cy.get('[role="dialog"]', { timeout: 30000 }).should('not.exist');

    panel().should('contain', 'This browser is set up for encrypted messages.');
  });
});
