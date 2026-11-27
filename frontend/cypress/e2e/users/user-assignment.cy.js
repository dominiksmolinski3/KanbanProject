afterEach(() => {
  cy.resetBoard();
});

describe('User Assignment', () => {
  it('allows assigning a user to a task', () => {
    cy.openBoard({ columns: ['Backlog'], rows: ['Default Row'], tasks: ['Assignment Test Task'] });

    cy.contains('.task', 'Assignment Test Task').click();

    cy.get('.user-select').select('User One');
    cy.get('.assign-btn-relationships').click();
    cy.get('.assigned-user-card').should('exist');

    cy.get('.close-panel-btn').click();

    cy.contains('.task', 'Assignment Test Task').find('.avatar-preview').should('exist');
  });

  it('allows removing user assignment', () => {
    cy.openBoard({ columns: ['Backlog'], rows: ['Default Row'], tasks: ['Remove Assignment Task'] });
    cy.contains('.task', 'Remove Assignment Task').click();

    cy.get('.user-select').select('User One');
    cy.get('.assign-btn-relationships').click();
    cy.get('.assigned-user-card').should('exist');

    cy.get('.remove-user-btn-card').click();
    cy.get('.confirm-btn').click();

    cy.get('.assigned-user-card').should('not.exist');
    cy.get('.close-panel-btn').click();
  });
});
