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

describe('Encrypted board channel', () => {
  it('the people on a board talk in its channel, from the dock and from Messages', () => {
    cy.fixture('test-account').then((owner) => cy.fixture('member-account').then((member) => {
      setUpMessaging({ account: owner, pin: PIN });
      cy.origin(memberOrigin(), { args: { account: member, pin: PIN } }, ({ account, pin }) => {
        const dialog = () => cy.get('[role="dialog"]');
        cy.visit('/');
        cy.clearCookies();
        cy.clearLocalStorage();
        cy.reload();
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

      cy.visit('/board');
      cy.get('button[aria-label="Open messages"]', { timeout: 30000 }).click();
      cy.get('.dock-panel', { timeout: 30000 }).within(() => {
        cy.contains('h3', 'Kanban', { timeout: 30000 });
        cy.get('textarea[aria-label="Message"]', { timeout: 30000 }).should('not.be.disabled')
          .type('Standup at ten{enter}');
        cy.contains('.message-row.is-mine .message-text', 'Standup at ten', { timeout: 30000 });
      });

      cy.origin(memberOrigin(), () => {
        cy.visit('/messages');
        cy.contains('section', 'Boards', { timeout: 30000 }).find('.conversation-row').first().click();
        cy.contains('.message-text', 'Standup at ten', { timeout: 30000 });
        cy.get('textarea[aria-label="Message"]').should('not.be.disabled').type('Understood{enter}');
        cy.contains('.message-row.is-mine .message-text', 'Understood', { timeout: 30000 });
      });

      cy.visit('/board');
      cy.get('button[aria-label="Open messages"]').click();
      cy.get('.dock-panel').contains('.message-text', 'Understood', { timeout: 30000 });
      cy.get('.dock-panel').contains('.message-text', 'Standup at ten')
        .closest('.message-bubble').find('[aria-label="Read"]', { timeout: 30000 }).should('exist');
      cy.get('.dock-panel a[aria-label="Open in Messages"]').click();
      cy.url().should('include', '/messages?c=');
      cy.contains('.message-text', 'Understood', { timeout: 30000 });
    }));
  });
});
