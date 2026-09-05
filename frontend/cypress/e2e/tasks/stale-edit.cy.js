beforeEach(() => {
  cy.loginAsTestUser();
  cy.wait(300);
  // cy.createTask needs a column to exist on the board - see task-creation.cy.js for the same
  // fix and why.
  cy.createColumn('Backlog', 0);
});

afterEach(() => {
  cy.on('window:alert', () => true);
  cy.on('window:confirm', () => true);
  cy.get('body').type('{esc}');

  cy.deleteTasks();
  cy.deleteColumns();
  cy.deleteRows();
});

/**
 * The @Version column already stops two overlapping transactions from clobbering each other. This
 * covers the slower race it cannot see: a task opened in the detail panel, changed by someone else,
 * and then saved from the still-open panel. The panel sends the version it loaded with, so the
 * server refuses the write with a 409 and the panel reloads rather than overwriting silently.
 */
describe('Editing a task that changed underneath you', () => {
  const bump = (title, changes) =>
    cy.window().then((win) =>
      cy.request({
        method: 'GET',
        url: '/api/tasks',
        headers: { Authorization: `Bearer ${win.localStorage.getItem('token')}` }
      }).then(({ body }) => {
        const task = body.find((t) => t.title === title);
        return cy.request({
          method: 'PATCH',
          url: `/api/tasks/${task.id}`,
          headers: { Authorization: `Bearer ${win.localStorage.getItem('token')}` },
          body: changes
        });
      })
    );

  it('reloads with the latest version instead of overwriting it', () => {
    cy.createTask('Race Me');

    cy.contains('.task', 'Race Me').click();
    cy.wait(200);
    cy.get('.edit-description-btn').first().click();
    cy.get('.description-textarea').type('my slow edit');

    // Someone else saves first, which moves the version on.
    bump('Race Me', { title: 'Race Me (theirs)' });

    cy.get('.save-description-btn').click();

    // A conflict is reported, and nothing is broken - the panel shows the value that won.
    cy.get('.Toastify__toast').should('exist');
    cy.contains('Race Me (theirs)').should('exist');
    cy.contains('my slow edit').should('not.exist');
  });
});
