const LIVE_TIMEOUT = 20000;

const NO_LIVE_TIMEOUT = 6000;

const COLUMN_NAME = 'Live Sync Column';
const ROW_NAME = 'Live Sync Row';

describe('a board is the same board for everybody on it', () => {
  let ownerToken;
  let memberToken;
  let boardId;
  let columnId;
  let rowId;

  const as = (token, options) =>
    cy.request({
      ...options,
      headers: { Authorization: `Bearer ${token}`, ...(options.headers || {}) },
    });

  before(() => {
    cy.loginAsTestUser();

    cy.window().then((win) => {
      ownerToken = win.localStorage.getItem('token');
      expect(ownerToken, 'the browser is signed in as the board owner').to.be.a('string');
    });

    cy.fixture('member-account').then((member) =>
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
  });

  afterEach(() => {
    cy.then(() =>
      as(ownerToken, { url: `/api/tasks?boardId=${boardId}` }).then((response) =>
        response.body.forEach((task) =>
          as(ownerToken, { method: 'DELETE', url: `/api/tasks/${task.id}` })
        )
      )
    );
  });

  const openBoard = ({ live }) => {
    if (live) {
      cy.intercept('GET', '**/ws/info*').as('sockjsHandshake');
    } else {
      cy.intercept('**/ws/**', { forceNetworkError: true });
    }

    cy.visit('/board');

    cy.contains('th', COLUMN_NAME).should('exist');
    cy.contains('.grid-row-header', ROW_NAME).should('exist');

    if (live) {
      cy.wait('@sockjsHandshake');

      cy.wait(1500);
    }

    cy.window().then((win) => {
      win.__documentStamp = 'same document';
    });
  };

  const stillTheSameDocument = () =>
    cy.window().its('__documentStamp').should('eq', 'same document');

  const memberCreatesTask = (title) =>
    as(memberToken, {
      method: 'POST',
      url: `/api/tasks?boardId=${boardId}`,
      body: { title, column: { id: columnId }, row: { id: rowId } },
    });

  it('shows a task somebody else created, with nobody touching this browser', () => {
    const title = `live-created-${Date.now()}`;

    openBoard({ live: true });
    cy.then(() => memberCreatesTask(title));

    cy.contains('.task', title, { timeout: LIVE_TIMEOUT }).should('exist');
    stillTheSameDocument();
  });

  it('removes a task somebody else deleted', () => {
    const title = `live-deleted-${Date.now()}`;

    openBoard({ live: true });

    cy.then(() => memberCreatesTask(title)).then((response) => {
      const taskId = response.body.id;

      cy.contains('.task', title, { timeout: LIVE_TIMEOUT }).should('exist');
      cy.then(() => as(memberToken, { method: 'DELETE', url: `/api/tasks/${taskId}` }));
    });

    cy.contains('.task', title, { timeout: LIVE_TIMEOUT }).should('not.exist');
    stillTheSameDocument();
  });

  it('shows a column somebody else renamed', () => {
    const renamed = `Renamed ${Date.now()}`;

    openBoard({ live: true });

    cy.then(() =>
      as(memberToken, { url: `/api/columns/${columnId}` }).then((response) =>
        as(memberToken, {
          method: 'PATCH',
          url: `/api/columns/${columnId}`,
          body: { ...response.body, name: renamed },
        })
      )
    );

    cy.contains('th', renamed, { timeout: LIVE_TIMEOUT }).should('exist');
    cy.contains('th', COLUMN_NAME).should('not.exist');
    stillTheSameDocument();

    cy.then(() =>
      as(ownerToken, { url: `/api/columns/${columnId}` }).then((response) =>
        as(ownerToken, {
          method: 'PATCH',
          url: `/api/columns/${columnId}`,
          body: { ...response.body, name: COLUMN_NAME },
        })
      )
    );
  });

  it('does not show it when the board has no live connection', () => {
    const title = `no-socket-${Date.now()}`;

    openBoard({ live: false });
    cy.then(() => memberCreatesTask(title));

    cy.contains('.task', title, { timeout: NO_LIVE_TIMEOUT }).should('not.exist');
    stillTheSameDocument();

    cy.reload();
    cy.contains('.task', title, { timeout: LIVE_TIMEOUT }).should('exist');
  });
});
