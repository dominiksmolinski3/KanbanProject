beforeEach(() => {
  cy.loginAsTestUser();
  cy.wait(300);
  cy.get('th', { timeout: 30000 }).should('exist');
  cy.deleteTasks();
  cy.deleteColumns();
  cy.ensureRowExists();

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

    bump('Race Me', { title: 'Race Me (theirs)' });

    cy.get('.save-description-btn').click();

    cy.get('.Toastify__toast').should('exist');
    cy.contains('Race Me (theirs)').should('exist');
    cy.contains('my slow edit').should('not.exist');
  });
});
