const BOARD_NAME = `Shared board ${Date.now()}`;

describe('a board shared through the members screen', () => {
  let ownerToken;
  let boardId;
  let member;

  const asOwner = (options) =>
    cy.request({ ...options, headers: { Authorization: `Bearer ${ownerToken}` } });

  const openBoard = (name) => {
    cy.get('.board-switcher-toggle').click();
    cy.contains('.board-switcher-menu .board-option', name).click();
    cy.get('.board-switcher-name').should('have.text', name);
  };

  before(() => {
    cy.fixture('member-account').then((account) => {
      member = account;
    });

    cy.loginAsTestUser();
    cy.window().then((win) => {
      ownerToken = win.localStorage.getItem('token');
      expect(ownerToken, 'the browser is signed in as the board owner').to.be.a('string');
    });

    cy.then(() =>
      asOwner({ method: 'POST', url: '/api/boards', body: { name: BOARD_NAME } }).then((response) => {
        boardId = response.body.id;
      })
    );
  });

  after(() => {
    cy.then(() => asOwner({ method: 'DELETE', url: `/api/boards/${boardId}`, failOnStatusCode: false }));
  });

  it('the owner invites by email, and the invitee accepts and lands on the board', () => {
    cy.loginAsTestUser();
    cy.visit('/users');
    openBoard(BOARD_NAME);

    cy.get('#board-invite-email').type(member.email);
    cy.get('#board-invite-role').select('MEMBER');
    cy.get('.board-invite button[type="submit"]').click();
    cy.contains('.board-invitation.pending', member.email).should('be.visible');

    cy.login(member.email, member.password);
    cy.visit('/users');
    cy.contains('.board-invitation.incoming', BOARD_NAME).find('.board-invitation-accept').click();

    cy.get('.board-switcher-name').should('have.text', BOARD_NAME);
    cy.contains('.board-invitation.incoming', BOARD_NAME).should('not.exist');
    cy.contains('.board-member', member.email).should('be.visible');
  });

  it('the owner removes the member, and the board leaves the member\'s list', () => {
    cy.loginAsTestUser();
    cy.visit('/users');
    openBoard(BOARD_NAME);

    cy.contains('.board-member', member.email).find('.board-member-remove').click();
    cy.contains('.board-member', member.email).should('not.exist');

    cy.login(member.email, member.password);
    cy.visit('/users');
    cy.get('.board-switcher-toggle').click();
    cy.get('.board-switcher-menu .board-option').should('have.length.at.least', 1);
    cy.contains('.board-switcher-menu .board-option', BOARD_NAME).should('not.exist');
  });
});
