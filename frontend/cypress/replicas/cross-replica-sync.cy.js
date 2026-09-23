const SECOND_REPLICA = 'http://127.0.0.1:8082';

const LIVE_TIMEOUT = 20000;
const NO_LIVE_TIMEOUT = 6000;

const COLUMN_NAME = 'Cross Replica Column';
const ROW_NAME = 'Cross Replica Row';

describe('a board event crosses from one API replica to another', () => {
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

  const asOnTheOtherReplica = (token, options) =>
    cy.request({
      ...options,
      url: `${SECOND_REPLICA}${options.url}`,
      headers: { Authorization: `Bearer ${token}`, ...(options.headers || {}) },
    });

  before(() => {
    cy.request({ url: `${SECOND_REPLICA}/actuator/health/readiness`, failOnStatusCode: false }).then(
      (response) => {
        expect(
          response.status,
          `no second API replica at ${SECOND_REPLICA} - start the stack with ` +
            '`docker compose --profile replicas up -d`'
        ).to.eq(200);
      }
    );

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

  const memberCreatesTaskOnTheOtherReplica = (title) =>
    asOnTheOtherReplica(memberToken, {
      method: 'POST',
      url: `/api/tasks?boardId=${boardId}`,
      body: { title, column: { id: columnId }, row: { id: rowId } },
    });

  it('writes through an API the browser cannot reach', () => {
    cy.request(`${SECOND_REPLICA}/actuator/health/readiness`)
      .its('body.status')
      .should('eq', 'UP');

    cy.request({ url: '/actuator/health/readiness', failOnStatusCode: false })
      .its('status')
      .should('eq', 404);
  });

  it('shows a task created on the replica the browser is not connected to', () => {
    const title = `cross-replica-created-${Date.now()}`;

    openBoard({ live: true });
    cy.then(() => memberCreatesTaskOnTheOtherReplica(title));

    cy.contains('.task', title, { timeout: LIVE_TIMEOUT }).should('exist');
    stillTheSameDocument();
  });

  it('removes a task deleted on the replica the browser is not connected to', () => {
    const title = `cross-replica-deleted-${Date.now()}`;

    openBoard({ live: true });

    cy.then(() => memberCreatesTaskOnTheOtherReplica(title)).then((response) => {
      const taskId = response.body.id;

      cy.contains('.task', title, { timeout: LIVE_TIMEOUT }).should('exist');
      cy.then(() =>
        asOnTheOtherReplica(memberToken, { method: 'DELETE', url: `/api/tasks/${taskId}` })
      );
    });

    cy.contains('.task', title, { timeout: LIVE_TIMEOUT }).should('not.exist');
    stillTheSameDocument();
  });

  const memberCreatesTaskWithSubtask = (title, subtaskTitle) =>
    memberCreatesTaskOnTheOtherReplica(title).then((task) =>
      asOnTheOtherReplica(memberToken, {
        method: 'POST',
        url: '/api/subtasks',
        body: { title: subtaskTitle, completed: false, task: { id: task.body.id } },
      })
    );

  const subtaskLabel = (subtaskTitle, options) => cy.contains('.subtask-item label', subtaskTitle, options);

  it('ticks a subtask in an open task panel when the other replica ticks it', () => {
    const title = `cross-replica-subtask-${Date.now()}`;
    const subtaskTitle = `tick me ${Date.now()}`;

    cy.then(() => memberCreatesTaskWithSubtask(title, subtaskTitle)).then((subtask) => {
      openBoard({ live: true });
      cy.contains('.task', title).click();
      subtaskLabel(subtaskTitle).should('not.have.class', 'completed');

      cy.then(() =>
        asOnTheOtherReplica(memberToken, {
          method: 'PATCH',
          url: `/api/subtasks/${subtask.body.id}/change`,
        })
      );
    });

    subtaskLabel(subtaskTitle, { timeout: LIVE_TIMEOUT }).should('have.class', 'completed');
    stillTheSameDocument();
    cy.get('.close-panel-btn').click();
  });

  it('does not tick it when the board has no live connection', () => {
    const title = `cross-replica-subtask-no-socket-${Date.now()}`;
    const subtaskTitle = `stays open ${Date.now()}`;

    cy.then(() => memberCreatesTaskWithSubtask(title, subtaskTitle)).then((subtask) => {
      openBoard({ live: false });
      cy.contains('.task', title).click();
      subtaskLabel(subtaskTitle).should('not.have.class', 'completed');

      cy.then(() =>
        asOnTheOtherReplica(memberToken, {
          method: 'PATCH',
          url: `/api/subtasks/${subtask.body.id}/change`,
        })
      );
    });

    cy.wait(NO_LIVE_TIMEOUT);
    subtaskLabel(subtaskTitle).should('not.have.class', 'completed');
    stillTheSameDocument();
    cy.get('.close-panel-btn').click();
  });

  it('does not show it when the board has no live connection', () => {
    const title = `cross-replica-no-socket-${Date.now()}`;

    openBoard({ live: false });
    cy.then(() => memberCreatesTaskOnTheOtherReplica(title));

    cy.contains('.task', title, { timeout: NO_LIVE_TIMEOUT }).should('not.exist');
    stillTheSameDocument();

    cy.reload();
    cy.contains('.task', title, { timeout: LIVE_TIMEOUT }).should('exist');
  });
});
