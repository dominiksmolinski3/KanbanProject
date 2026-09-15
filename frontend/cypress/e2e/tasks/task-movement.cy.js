beforeEach(() => {
  cy.loginAsTestUser();
  // Own the board before creating columns named "In Progress" or "Backlog" - both collide with
  // words in the default seeded columns ("In Progress" itself, "Product Backlog", "Sprint
  // Backlog"), and an unclean board makes th:contains(...) ambiguous, which cy.trigger() (used by
  // .drag()) refuses outright. See task-creation.cy.js for the full story.
  cy.get('th', { timeout: 30000 }).should('exist');
  cy.deleteTasks();
  cy.deleteColumns();
  // Rows are left alone and topped up rather than cleared - see cy.ensureRowExists in
  // commands.js - because a task with no row of its own renders nowhere once the board has zero,
  // and the first test below moves a task without ever creating a row of its own.
  cy.ensureRowExists();
});

afterEach(() => {
  cy.on('window:alert', () => true);
  cy.on('window:confirm', () => true);
  cy.get('body').type('{esc}');
      
  cy.deleteTasks();
  cy.deleteColumns();
  cy.deleteRows();
});

describe('Task Movement', () => {
  it('moves a task between columns', () => {
    cy.createColumn('In Progress', 0);
    cy.createColumn('Done for moving test case', 0);
    cy.createTask('Movable Task');

    cy.contains('.task', 'Movable Task').should('be.visible');

    cy.contains('.task', 'Movable Task').drag('th:contains("In Progress")');
    cy.wait(500);

    cy.get('th:contains("In Progress")').parents('table')
      .contains('.task', 'Movable Task').should('exist');

    cy.contains('.task', 'Movable Task').drag('th:contains("Done for moving test case")');
    cy.wait(500);

    cy.get('th:contains("Done for moving test case")').parents('table')
      .contains('.task', 'Movable Task').should('exist');
  });
  
  it('moves a task between rows', () => {
    // cy.createTask needs a column to exist on the board - see task-creation.cy.js for the
    // same fix and why. This test only creates a row, unlike the one above, so it has to
    // create its own column too rather than relying on one to already be there.
    cy.createColumn('Backlog', 0);
    cy.createRow('Bugs for moving test case', 0);
    cy.createTask('Movable Task');
    cy.wait(100);
    cy.contains('.task', 'Movable Task').should('be.visible');
      
    cy.contains('.task', 'Movable Task').drag('tr:contains("Bugs for moving test case")');
    cy.wait(500);
      
    cy.contains('tr', 'Bugs for moving test case').parents('table')
      .contains('.task', 'Movable Task').should('exist');
  });
  
});