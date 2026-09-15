beforeEach(() => {
  cy.loginAsTestUser();
  cy.wait(300);
  // Own the board before creating a column named "Backlog" - two of the default seeded columns
  // ("Product Backlog", "Sprint Backlog") also contain that word, and an unclean board makes
  // `th:contains("Backlog")` ambiguous. See task-creation.cy.js for the full story.
  cy.get('th', { timeout: 30000 }).should('exist');
  cy.deleteTasks();
  cy.deleteColumns();
  // Rows are left alone and topped up rather than cleared - see cy.ensureRowExists in
  // commands.js - because a task with no row of its own renders nowhere once the board has zero.
  cy.ensureRowExists();

  // cy.setupTaskWithSubtasks creates a task with no column of its own, which the app refuses
  // when the board has none at all.
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

describe('Subtasks', () => {
  it('creates subtasks within a task', () => {
    cy.setupTaskWithSubtasks('Parent Task', ['Subtask 1', 'Subtask 2', 'Subtask 3']);
      
    cy.contains('.task', 'Parent Task').click();
    cy.get('.subtask-item').should('have.length', 3);
    cy.contains('.subtask-item', 'Subtask 1').should('exist');
    cy.contains('.subtask-item', 'Subtask 2').should('exist');
    cy.contains('.subtask-item', 'Subtask 3').should('exist');
    cy.get('.close-panel-btn').click();
  });
  
  it('marks subtasks as complete', () => {
    cy.setupTaskWithSubtasks('Completion Task', ['Mark me complete']);
      
    cy.wait(300);
    cy.contains('.task', 'Completion Task').click();
    cy.wait(300);
    cy.contains('.subtask-item', 'Mark me complete').find('input[type="checkbox"]').check();
    cy.wait(300);
    cy.contains('.subtask-item', 'Mark me complete').find('label').should('have.class', 'completed');
      

      
    cy.get('.close-panel-btn').click();
  });
  
  it('deletes subtasks', () => {
    cy.setupTaskWithSubtasks('Deletion Task', ['Delete me']);
      
    cy.contains('.task', 'Deletion Task').click();
    cy.contains('.subtask-item', 'Delete me').find('.delete-subtask-btn').click();
    cy.get('.confirm-btn').click();
    cy.contains('.subtask-item', 'Delete me').should('not.exist');
      
    cy.get('.close-panel-btn').click();
  });
  
});