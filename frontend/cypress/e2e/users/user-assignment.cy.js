beforeEach(() => {
  cy.loginAsTestUser();
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

describe('User Assignment', () => {
  it('allows assigning a user to a task', () => {
    cy.createTask('Assignment Test Task');

    cy.contains('.task', 'Assignment Test Task').click();
    cy.get('.parent-child-btn').click();

    cy.get('.user-select').select('User One');
    cy.get('.assign-btn-relationships').click();
    cy.get('.assigned-user-card').should('exist');

    cy.get('.close-panel-btn').click();

    cy.contains('.task', 'Assignment Test Task').find('.avatar-preview').should('exist');
  });

  it('allows removing user assignment', () => {
    cy.createTask('Remove Assignment Task');
    cy.contains('.task', 'Remove Assignment Task').click();
    cy.get('.parent-child-btn').click();

    cy.get('.user-select').select('User One');
    cy.get('.assign-btn-relationships').click();
    cy.get('.assigned-user-card').should('exist');

    cy.get('.remove-user-btn').click();
    cy.get('.confirm-btn').click();

    cy.get('.assigned-user-card').should('not.exist');
    cy.get('.close-panel-btn').click();
  });

});
