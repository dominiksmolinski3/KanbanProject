import { Client } from '@stomp/stompjs';

const LIVE_TIMEOUT = 20000;

const COLUMN_NAME = 'Chat Column';
const ROW_NAME = 'Chat Row';

describe('chat reaches the people it is meant for', () => {
  let owner;
  let member;
  let ownerToken;
  let memberToken;
  let boardId;
  let columnId;
  let rowId;
  let memberSocket;

  const as = (token, options) =>
    cy.request({
      ...options,
      headers: { Authorization: `Bearer ${token}`, ...(options.headers || {}) },
    });

  const connectAsMember = () =>
    cy.then(
      { timeout: LIVE_TIMEOUT },
      () =>
        new Cypress.Promise((resolve, reject) => {
          const client = new Client({
            brokerURL: `${Cypress.config('baseUrl').replace(/^http/, 'ws')}/ws/websocket`,
            connectHeaders: { Authorization: `Bearer ${memberToken}` },
            reconnectDelay: 0,
            onConnect: () => resolve(client),
            onStompError: (frame) => reject(new Error(`STOMP error: ${frame.headers.message}`)),
            onWebSocketError: () => reject(new Error('the member socket did not open')),
          });
          client.activate();
        })
    ).then((client) => {
      memberSocket = client;
    });

  const memberSends = (destination, body) =>
    cy.then(() => memberSocket.publish({ destination, body: JSON.stringify(body) }));

  const send = (text) => {
    cy.get('.chat-input').type(text);
    cy.get('.send-button', { timeout: LIVE_TIMEOUT }).should('be.enabled').click();
  };

  const memberSubscribes = (destination, onFrame) =>
    cy.then(
      { timeout: LIVE_TIMEOUT },
      () =>
        new Cypress.Promise((resolve) => {
          const receipt = `subscribed-${Date.now()}`;
          memberSocket.watchForReceipt(receipt, resolve);
          memberSocket.subscribe(destination, onFrame, { receipt });
        })
    );

  const openChat = () => cy.get('.chat-toggle-button').click();

  before(() => {
    cy.fixture('test-account').then((account) => {
      owner = account;
    });
    cy.fixture('member-account').then((account) => {
      member = account;
    });

    cy.loginAsTestUser();
    cy.window().then((win) => {
      ownerToken = win.localStorage.getItem('token');
      expect(ownerToken, 'the browser is signed in as the board owner').to.be.a('string');
    });

    cy.then(() =>
      cy
        .request('POST', '/api/auth/login', { email: member.email, password: member.password })
        .then((response) => {
          memberToken = response.body.token;
        })
    );

    cy.then(() =>
      as(ownerToken, { url: '/api/boards/current' }).then((response) => {
        boardId = response.body.id;
      })
    );

    cy.then(() =>
      as(ownerToken, {
        method: 'POST',
        url: `/api/columns?boardId=${boardId}`,
        body: { name: COLUMN_NAME },
      }).then((response) => {
        columnId = response.body.id;
      })
    );

    cy.then(() =>
      as(ownerToken, {
        method: 'POST',
        url: `/api/rows?boardId=${boardId}`,
        body: { name: ROW_NAME },
      }).then((response) => {
        rowId = response.body.id;
      })
    );
  });

  after(() => {
    cy.then(() => as(ownerToken, { method: 'DELETE', url: `/api/columns/${columnId}` }));
    cy.then(() =>
      as(ownerToken, { method: 'DELETE', url: `/api/rows/${rowId}`, failOnStatusCode: false })
    );
  });

  beforeEach(() => {
    cy.loginAsTestUser();
    cy.contains('th', COLUMN_NAME).should('exist');
    connectAsMember();
  });

  afterEach(() => {
    cy.then(() => memberSocket && memberSocket.deactivate());
  });

  it('shows a message its author sent, and keeps it across a reload', () => {
    const text = `own-${Date.now()}`;

    openChat();
    send(text);

    cy.contains('.own-message .message-content', text, { timeout: LIVE_TIMEOUT }).should('exist');
    cy.get('.chat-input').should('have.value', '');

    cy.reload();
    cy.get('.chat-toggle-button').click();
    cy.contains('.own-message .message-content', text, { timeout: LIVE_TIMEOUT }).should('exist');
  });

  it('shows a message somebody else sends to the board, as it is sent', () => {
    const text = `theirs-${Date.now()}`;

    openChat();
    memberSends('/app/chat.sendMessage', { content: text, boardId });

    cy.contains('.other-message', text, { timeout: LIVE_TIMEOUT })
      .should('not.have.class', 'private-message')
      .find('.message-sender')
      .should('have.text', member.email);
  });

  it('carries a direct message both ways, and to nobody on the board conversation', () => {
    const sent = `direct-out-${Date.now()}`;
    const reply = `direct-back-${Date.now()}`;
    const onTheBoard = `board-after-${Date.now()}`;
    const received = [];

    memberSubscribes('/user/queue/messages', (frame) => received.push(JSON.parse(frame.body)));

    openChat();
    cy.contains('.chat-kind-option', 'Private').click();
    cy.get('.recipient-input').type(member.email).blur();
    send(sent);

    cy.contains('.own-message.private-message', sent, { timeout: LIVE_TIMEOUT }).should('exist');
    cy.wrap(received, { timeout: LIVE_TIMEOUT }).should((messages) => {
      const arrived = messages.find((message) => message.content === sent);
      expect(arrived, 'the direct message reached its recipient').to.include({
        type: 'PRIVATE',
        sender: owner.email,
      });
    });

    memberSends('/app/chat.sendPrivateMessage', { content: reply, recipientId: owner.email });
    cy.contains('.other-message.private-message', reply, { timeout: LIVE_TIMEOUT }).should('exist');

    cy.contains('.chat-kind-option', 'Board').click();
    send(onTheBoard);
    cy.contains('.own-message', onTheBoard, { timeout: LIVE_TIMEOUT }).should('exist');
    cy.contains('.chat-message', sent).should('not.exist');
    cy.contains('.chat-message', reply).should('not.exist');
  });

  it('keeps the board live while the chat is open, and the chat live while the board changes', () => {
    const title = `chat-open-${Date.now()}`;
    const text = `after-task-${Date.now()}`;

    openChat();

    cy.then(() =>
      as(memberToken, {
        method: 'POST',
        url: `/api/tasks?boardId=${boardId}`,
        body: { title, column: { id: columnId }, row: { id: rowId } },
      })
    ).then((response) => {
      cy.contains('.task', title, { timeout: LIVE_TIMEOUT }).should('exist');

      memberSends('/app/chat.sendMessage', { content: text, boardId });
      cy.contains('.other-message', text, { timeout: LIVE_TIMEOUT }).should('exist');

      cy.then(() => as(memberToken, { method: 'DELETE', url: `/api/tasks/${response.body.id}` }));
    });
    cy.contains('.task', title, { timeout: LIVE_TIMEOUT }).should('not.exist');
  });
});
