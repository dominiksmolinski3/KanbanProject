/**
 * The one claim about live board sync that no other test in this repository can make: that a real
 * browser, with nobody touching it, shows a change somebody else made.
 *
 * PR 124 was verified with two Node clients over the real SockJS transport, which is the mechanism
 * and is not a screen. The unit tests are narrower still - `KanbanLiveSync.test.jsx` asserts that
 * the context re-reads when a fake `BoardEvents` hands it a frame, and `boardEvents.test.js`
 * asserts the client subscribes where it says it does. Neither has a browser, a WebSocket, or a
 * second account in it.
 *
 * **The second person is an API caller, not a second browser.** Cypress drives one browser at a
 * time, and that is the right shape here rather than a limitation worked around: what has to be
 * proved is that *this* document updates without being reloaded, and the change only has to come
 * from somebody who is not it. The seeded member account makes every change over HTTP; the browser
 * is signed in as the board's owner and is never told to do anything after it lands on /board.
 *
 * **How "without a reload" is asserted.** Each test stamps a value on `window` once the board is
 * open and asserts it is still there after the change has arrived. A reload, a navigation or a
 * fresh document would wipe it, so the stamp surviving alongside the new DOM is what separates
 * "the socket delivered it" from "something happened to load the board again".
 *
 * **And what keeps those assertions honest is the last test, which takes the socket away.** An
 * assertion that a card appears is only evidence about live sync if it would fail without it, and
 * `KanbanContext` having no polling is a reason to believe that rather than a demonstration of it.
 * So the final test fails every request to `/ws` and makes exactly the same change: the card does
 * not appear, and a reload then proves it had really been written. That is the same discipline as
 * deleting a suite and watching a JaCoCo floor go red, in the form of a test that stays.
 *
 * **Why the layout is built over the API rather than through the UI.** A card renders in a cell,
 * and a cell is a (column, row) pair - `Board.jsx` iterates `rows` and matches
 * `task.columnId === columnId && task.rowId === rowId`, so a task with no row has nowhere to be
 * drawn and a board with no rows draws nothing at all. Building that through the forms would put
 * three of this spec's own mutations in front of the one it is testing; doing it over HTTP keeps
 * the only live change in each test the one under test.
 */

const LIVE_TIMEOUT = 20000;

/*
 * How long "it did not arrive" is given in the socket-less control. It does not need to be
 * LIVE_TIMEOUT: the tests above land in a couple of seconds, so this is already well past when a
 * working connection would have delivered it, and it keeps the control from costing twenty seconds
 * of every run.
 */
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

  /*
   * Exactly one real sign-in that this spec adds, and it is the member's.
   *
   * The owner's token is read out of the browser after cy.session has restored it rather than
   * asked for over the API, and that is not a tidiness point - it is the difference between
   * passing and failing. AuthRateLimiter allows five credential attempts *per account* before the
   * first cooldown, and the seed script has already spent two of the owner's (a /auth/verify and
   * a /auth/login, both CREDENTIALS). An API login here made it six across one run and
   * cy.session's own sign-in was refused with a 429 - which surfaces as the login form simply not
   * navigating, and reads exactly like a broken page. Rev 22 and rev 23 each recorded walking into
   * this; the cheapest way not to walk into it again is to want one fewer token.
   */
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

    // The owner's board. The member belongs to it, but their *own* board is a different one, and a
    // write without ?boardId= means "the caller's own board" - so getting this wrong would put the
    // task somewhere the browser is not looking and fail for a reason that is not the socket.
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

  // Put the board back as it was found, so the specs either side of this one still start from a
  // board they recognise. Over the API for the same reason the setup is: the UI's delete asks for
  // confirmation through a toast, which would be more of this spec's own mutations. The row delete
  // tolerates a refusal - the app will not remove a board's last swimlane.
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

  /**
   * Opens the board in a document whose live connection is either established or unreachable.
   *
   * The visit is this spec's own rather than the one inside cy.login, because an intercept has to
   * be registered before the document that opens the socket loads - `KanbanProvider` connects on
   * mount. Re-visiting /board discards the connection cy.login's visit made and builds the one
   * this test wants.
   */
  const openBoard = ({ live }) => {
    if (live) {
      // SockJS opens with a GET /ws/info, which is the earliest observable sign that the board's
      // connection is being established.
      cy.intercept('GET', '**/ws/info*').as('sockjsHandshake');
    } else {
      cy.intercept('**/ws/**', { forceNetworkError: true });
    }

    cy.visit('/board');

    cy.contains('th', COLUMN_NAME).should('exist');
    cy.contains('.grid-row-header', ROW_NAME).should('exist');

    if (live) {
      cy.wait('@sockjsHandshake');

      /*
       * The handshake is the transport opening, not the STOMP SUBSCRIBE landing, and there is
       * nothing in the page to wait on for the latter: `boardEvents.js` keeps its client private
       * and exposes no state, deliberately. So this is a fixed settle for the WebSocket upgrade,
       * the CONNECT frame and the SUBSCRIBE after it - the one piece of timing here that is a
       * judgement rather than an observation. It fails safe: a frame published before the
       * subscription exists is dropped rather than queued, so getting this wrong shows up as a
       * failing test and never as a false pass.
       */
      cy.wait(1500);
    }

    cy.window().then((win) => {
      win.__documentStamp = 'same document';
    });
  };

  /** The stamp from openBoard, still on the window the change arrived in. */
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

    // Created by the member as well, so the browser has received this card over the socket before
    // the deletion is asked for. That makes the second half a live *removal* rather than a card
    // the page happened to load with.
    cy.then(() => memberCreatesTask(title)).then((response) => {
      const taskId = response.body.id;

      cy.contains('.task', title, { timeout: LIVE_TIMEOUT }).should('exist');
      // cy.then after the assertion rather than around it: returning taskId straight out of a
      // callback that has queued a command is what Cypress refuses as mixing async and sync code.
      cy.then(() => as(memberToken, { method: 'DELETE', url: `/api/tasks/${taskId}` }));
    });

    cy.contains('.task', title, { timeout: LIVE_TIMEOUT }).should('not.exist');
    stillTheSameDocument();
  });

  // The other kind of frame, and the one that takes the wider re-read: COLUMNS arrives as
  // refreshBoard() rather than refreshTasks(), because deleting a column takes its cards with it.
  // A rename is the cheapest change that proves the layout half of that path.
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

    // Put the name back, or the next test does not recognise the board it opens.
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

  /**
   * The control, and the reason to believe the three tests above.
   *
   * With every request to /ws failed the board has no live connection, and nothing else in the
   * client re-reads on its own. The card therefore must not appear - and the reload afterwards is
   * what stops this from being a test that passes because the task was never created: same
   * request, same board, and the card is there the moment the document is built again.
   */
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
