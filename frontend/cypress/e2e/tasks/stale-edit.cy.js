afterEach(() => {
  cy.resetBoard();
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
    cy.openBoard({ columns: ['Backlog'], rows: ['Default Row'], tasks: ['Race Me'] });

    cy.contains('.task', 'Race Me').click();
    cy.get('.edit-description-btn').first().click();
    cy.get('.description-textarea').type('my slow edit');

    bump('Race Me', { title: 'Race Me (theirs)' });

    cy.get('.save-description-btn').click();

    cy.get('.Toastify__toast').should('exist');
    cy.contains('Race Me (theirs)').should('exist');
    cy.contains('my slow edit').should('not.exist');
  });
});
