const PIN = '246810';

const setUpMessaging = ({ account, pin }) => {
  const dialog = () => cy.get('[role="dialog"]');
  cy.visit('/');
  cy.get('input[type="email"]').first().type(account.email);
  cy.get('input[type="password"]').first().type(account.password);
  cy.contains('button', 'Sign In').click();
  cy.url().should('include', '/board');
  cy.visit('/sessions');
  cy.window().then((win) => Promise.all(['kanban-messaging-keys', 'kanban-messaging-archive'].map((name) =>
    new Promise((resolve) => {
      const request = win.indexedDB.deleteDatabase(name);
      request.onsuccess = resolve;
      request.onerror = resolve;
      request.onblocked = resolve;
    }))));
  cy.reload();
  cy.contains('h2', 'Encrypted messages', { timeout: 15000 }).closest('section')
      .should(($panel) => expect($panel.text()).to.match(/Set up on this browser|Start over/)).then(($panel) => {
    const button = $panel.text().includes('Start over') ? 'Start over' : 'Set up on this browser';
    cy.wrap($panel).contains('button', button).click();
    dialog().contains('button', button).click();
  });
  dialog().find('#messaging-pin').type(pin);
  dialog().find('#messaging-pin-repeat').type(pin);
  dialog().contains('button', 'Finish setup').click();
  cy.get('[role="dialog"]', { timeout: 30000 }).should('not.exist');
  cy.contains('This browser is set up for encrypted messages.', { timeout: 15000 });
};

const memberOrigin = () => Cypress.config('baseUrl').replace('localhost', '127.0.0.1');

const openConversationWith = (name) => {
  cy.visit('/messages');
  cy.contains('.conversation-row', name, { timeout: 30000 }).click();
};

const say = (text) => {
  cy.get('textarea[aria-label="Message"]', { timeout: 30000 }).should('not.be.disabled').type(`${text}{enter}`);
  cy.contains('.message-row.is-mine .message-text', text, { timeout: 30000 });
};

describe('Encrypted direct messages', () => {
  it('two people on a shared board set up, start a conversation and read each other', () => {
    cy.fixture('test-account').then((owner) => cy.fixture('member-account').then((member) => {
      setUpMessaging({ account: owner, pin: PIN });
      cy.origin(memberOrigin(), { args: { account: member, pin: PIN } }, ({ account, pin }) => {
        const dialog = () => cy.get('[role="dialog"]');
        cy.visit('/');
        cy.get('input[type="email"]').first().type(account.email);
        cy.get('input[type="password"]').first().type(account.password);
        cy.contains('button', 'Sign In').click();
        cy.url().should('include', '/board');
        cy.visit('/sessions');
        cy.window().then((win) => Promise.all(['kanban-messaging-keys', 'kanban-messaging-archive'].map((name) =>
          new Promise((resolve) => {
            const request = win.indexedDB.deleteDatabase(name);
            request.onsuccess = resolve;
            request.onerror = resolve;
            request.onblocked = resolve;
          }))));
        cy.reload();
        cy.contains('h2', 'Encrypted messages', { timeout: 15000 }).closest('section')
      .should(($panel) => expect($panel.text()).to.match(/Set up on this browser|Start over/)).then(($panel) => {
          const button = $panel.text().includes('Start over') ? 'Start over' : 'Set up on this browser';
          cy.wrap($panel).contains('button', button).click();
          dialog().contains('button', button).click();
        });
        dialog().find('#messaging-pin').type(pin);
        dialog().find('#messaging-pin-repeat').type(pin);
        dialog().contains('button', 'Finish setup').click();
        cy.get('[role="dialog"]', { timeout: 30000 }).should('not.exist');
        cy.contains('This browser is set up for encrypted messages.', { timeout: 15000 });
      });

      cy.visit('/messages');
      cy.contains('button', 'New').click();
      cy.get('[role="dialog"]').contains('label', member.username, { timeout: 15000 }).find('input').check();
      cy.get('[role="dialog"]').contains('button', 'Start').click();
      cy.get('[role="dialog"]').should('not.exist');
      say('Hello from the owner');

      cy.origin(memberOrigin(), { args: { owner: owner.username } }, ({ owner: ownerName }) => {
        cy.visit('/messages');
        cy.contains('.conversation-row', ownerName, { timeout: 30000 }).click();
        cy.contains('.message-text', 'Hello from the owner', { timeout: 30000 });
        cy.get('textarea[aria-label="Message"]').should('not.be.disabled').type('Hi back{enter}');
        cy.contains('.message-row.is-mine .message-text', 'Hi back', { timeout: 30000 });
      });

      openConversationWith(member.username);
      cy.contains('.message-text', 'Hi back', { timeout: 30000 });
      cy.contains('.message-text', 'Hello from the owner');
      cy.get('button[aria-label="Conversation details"]').click();
      cy.contains('button', 'Verify').click();
      cy.get('[aria-label="Security code"] span').should('have.length', 12);
    }));
  });
});
