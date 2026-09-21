/**
 * The claim phase 04 of the container split moved a replica ceiling on and never verified: that a
 * board event published by *one* API replica reaches a browser whose WebSocket is held by
 * *another*.
 *
 * `live-sync.cy.js` already proves a card arrives without a reload, and it cannot prove this one.
 * At a single replica the publisher and the subscriber are the same JVM and the frame never leaves
 * it - so the in-memory `enableSimpleBroker` that PR #173 removed would pass that spec exactly as
 * the RabbitMQ relay does. The only thing separating the two is a second process, which is why
 * this spec needs the `replicas` profile and why it is the one spec here that addresses a replica
 * by its own port instead of going through the edge.
 *
 * **The asymmetry is the whole design.** The browser reaches the application through nginx on
 * :8080, and nginx's upstream is the compose service `app` - it has never heard of `app2`. So the
 * document's SockJS connection, its STOMP CONNECT and its SUBSCRIBE to `/topic/boards.{id}` are
 * all held by `app`. Every mutation this spec makes goes to `app2` directly. A card that appears
 * in the browser therefore crossed two JVMs and the broker between them; there is no path that
 * does not.
 *
 * **What would quietly turn this into a same-replica test** is the second address drifting onto
 * the first - :8081 is `app`'s own published port, one digit away. Nothing at runtime could tell:
 * the spec would pass, faster, and prove nothing. `CrossReplicaStackTest` is the guard, and it
 * reads the compose file and this file and fails the build when they stop naming two different
 * ports - the same rule-in-two-files-checked-in-one shape `SecurityHeadersMatchTheEdgeTest` and
 * `DeadLetterAlertTest` already have.
 *
 * Everything else - the member account making the changes, the window stamp that separates "the
 * socket delivered it" from "the page reloaded", the socket-less control that makes the positive
 * assertions mean something - is `live-sync.cy.js`'s, deliberately, so the two specs cannot drift
 * on what counts as evidence.
 */

/**
 * The second replica's own address, published by the `app2` service in the `replicas` profile.
 *
 * A constant rather than something configurable, deliberately. There is nothing here to configure:
 * the port is `docker-compose.yml`'s and `CrossReplicaStackTest` fails the build when the two stop
 * agreeing. A spec whose target can be supplied from outside is a spec that can be pointed at the
 * wrong replica - or at nothing - and still report success, which is the failure this repository
 * has already paid for twice, most expensively as a security sweep that scanned nothing for a
 * month and passed in five seconds.
 */
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

  /** Through the edge, like every other spec: this is the replica the browser is talking to. */
  const as = (token, options) =>
    cy.request({
      ...options,
      headers: { Authorization: `Bearer ${token}`, ...(options.headers || {}) },
    });

  /** Straight at `app2`, bypassing nginx: this is the replica the browser is *not* talking to. */
  const asOnTheOtherReplica = (token, options) =>
    cy.request({
      ...options,
      url: `${SECOND_REPLICA}${options.url}`,
      headers: { Authorization: `Bearer ${token}`, ...(options.headers || {}) },
    });

  before(() => {
    // Fail here, with the command that fixes it, rather than twenty seconds later on a card that
    // never arrives - a missing second replica and a broken relay look identical from the board.
    cy.request({ url: `${SECOND_REPLICA}/actuator/health/readiness`, failOnStatusCode: false }).then(
      (response) => {
        expect(
          response.status,
          `no second API replica at ${SECOND_REPLICA} - start the stack with ` +
            '`docker compose --profile replicas up -d`'
        ).to.eq(200);
      }
    );

    // The owner's token is read out of the browser rather than asked for over the API, for the
    // reason live-sync.cy.js records: the seed has already spent two of this account's five
    // CREDENTIALS attempts, and a sixth in one run is a 429 that reads like a broken sign-in page.
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

    // A card renders in a (column, row) cell, so a board with no row of its own draws nothing -
    // built over the API for the same reason live-sync.cy.js does it that way.
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
      // The handshake is the transport opening, not the SUBSCRIBE landing, and boardEvents.js
      // exposes no state to wait on. A frame published before the subscription exists is dropped
      // rather than queued, so getting this wrong fails the test and never falsely passes it.
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

  /**
   * The precondition, asserted rather than assumed, and it is not the same as "8082 answers".
   *
   * The edge refuses /actuator by design, so the address the browser uses cannot be the address
   * this spec writes through: if the two had collapsed onto one origin, this would answer the SPA
   * shell or a 404 rather than the readiness group. That is the cheapest available proof from
   * inside a browser test that the write is not going to the replica holding the socket.
   */
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

  /**
   * The control. Without it the three tests above are evidence that a card can appear, not that
   * the socket is what made it appear - and here that matters more than it does at one replica,
   * because a cross-replica failure is silent by construction: the row is written, the API answers
   * correctly, and only somebody else's screen is wrong.
   */
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
