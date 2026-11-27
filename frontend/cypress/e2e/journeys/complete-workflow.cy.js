afterEach(() => {
  cy.resetBoard();
});

describe('Complete User Journey', () => {
  it('allows a user to set up and use a kanban board', () => {
    cy.openBoard();

    cy.createColumn('Backlog', 0);
    cy.createColumn('In Progress', 3);
    cy.createColumn('Done', 0);

    cy.createRow('Features', 4);
    cy.createRow('Bugs', 2);

    cy.get('[data-testid="open-add-task-form"]').click();
    cy.get('#task-title').type('Implement login');
    cy.get('[type="submit"]').click();
      
    cy.contains('.task', 'Implement login').drag('th:contains("In Progress")');
    cy.contains('th', 'In Progress').invoke('attr', 'data-column-id').then((columnId) => {
      cy.contains('.task', 'Implement login').closest('.grid-cell').should('have.attr', 'data-column-id', columnId);
    });
    cy.contains('.task', 'Implement login').click();
    cy.get('.subtask-input').type('Create UI{enter}');
    cy.get('.add-subtask-btn').click();
    cy.contains('.subtask-item', 'Create UI').should('exist');

    cy.get('.subtask-input').type('Validate inputs{enter}');
    cy.get('.add-subtask-btn').click();
    cy.contains('.subtask-item', 'Validate inputs').should('exist');

    cy.get('.subtask-item').first().find('input[type="checkbox"]').check();

    cy.get('.close-panel-btn').click();
      
    cy.createTask('Fix bug #123');
    cy.contains('.task', 'Fix bug #123').drag('th:contains("In Progress")');

    cy.createTask('Update docs');
    cy.contains('.task', 'Update docs').drag('th:contains("In Progress")');

    cy.createTask('WIP limit test');
    cy.contains('.task', 'WIP limit test').drag('th:contains("In Progress")');

      
    cy.get('th:contains("In Progress")').find('.wip-limit.exceeded').should('exist');
  });
});