const KEY_DATABASE = 'kanban-messaging-keys';
const PIN = '135790';

const panel = (options) => cy.contains('h2', 'Encrypted messages', options).closest('section', options);
const dialog = () => cy.get('[role="dialog"]');
const settled = () => cy.get('[role="dialog"]', { timeout: 30000 }).should('not.exist');
const ready = () => panel({ timeout: 15000 }).should('contain', 'This browser is set up for encrypted messages.');

const forgetThisBrowser = () => cy.window().then((win) => new Cypress.Promise((resolve) => {
  const request = win.indexedDB.deleteDatabase(KEY_DATABASE);
  request.onsuccess = resolve;
  request.onerror = resolve;
}));

const enterPin = (value) => {
  dialog().find('#messaging-unlock-pin').clear().type(value);
  dialog().contains('button', 'Unlock').click();
};

describe('Encrypted messaging keys', () => {
  after(() => {
    Cypress.session.clearAllSavedSessions();
  });

  beforeEach(() => {
    cy.loginAsTestUser();
    cy.visit('/sessions');
    forgetThisBrowser();
    cy.reload();
  });

  it('sets up with a PIN, keeps the key through sign-out, and unlocks a new browser with the PIN or the recovery key', () => {
    panel({ timeout: 10000 }).should(($panel) => expect($panel.text()).to.match(/Set up on this browser|Start over/));
    panel().then(($panel) => {
      const button = $panel.text().includes('Start over') ? 'Start over' : 'Set up on this browser';
      cy.wrap($panel).contains('button', button).click();
      dialog().contains('button', button).click();
    });
    dialog().find('#messaging-pin').type(PIN);
    dialog().find('#messaging-pin-repeat').type(PIN);
    dialog().contains('label', 'Also create a recovery key').find('input').check();
    dialog().contains('button', 'Continue').click();
    dialog().find('[aria-label="Recovery key"] span').should('have.length', 12).then(($groups) => {
      const groups = [...$groups].map((group) => group.textContent);
      cy.wrap(groups.join('')).as('recoveryKey');
      dialog().contains('label', 'I saved my recovery key').find('input').check();
      dialog().find('#messaging-key-check').type(groups.at(-1));
    });
    dialog().contains('button', 'Finish setup').click();
    settled();
    ready();
    panel().find('code').first().invoke('text').then((fingerprint) => {
      cy.get('.device-current .device-messaging-key').should('have.text', fingerprint);
    });

    cy.get('[data-testid="header-menu-toggle"]').click();
    cy.get('.logout-btn').click();
    cy.fixture('test-account').then((account) => {
      cy.get('input[type="email"]').first().type(account.email);
      cy.get('input[type="password"]').first().type(account.password);
      cy.contains('button', 'Sign In').click();
    });
    cy.url().should('include', '/board');
    cy.visit('/sessions');
    ready();
    cy.get('.device-current .device-messaging-key').should('exist');

    forgetThisBrowser();
    cy.reload();
    panel({ timeout: 15000 }).contains('button', 'Enter my PIN').click();
    enterPin('000000');
    dialog().contains('That PIN is not correct.', { timeout: 20000 });
    dialog().contains('Tries left before the PIN locks: 9');
    enterPin(PIN);
    settled();
    ready();

    forgetThisBrowser();
    cy.reload();
    panel({ timeout: 15000 }).contains('button', 'Use my recovery key').click();
    cy.get('@recoveryKey').then((recoveryKey) => {
      dialog().find('textarea').type(recoveryKey.toLowerCase().match(/.{4}/g).join(' '));
    });
    dialog().contains('button', 'Set up this browser').click();
    settled();
    ready();
  });
});
