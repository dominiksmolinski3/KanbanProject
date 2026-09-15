beforeEach(() => {
  cy.loginAsTestUser();
  // Own the board rather than inheriting one. Every fresh board ships with the eight columns
  // BoardService.DEFAULT_COLUMNS seeds, two of which - "Product Backlog" and "Sprint Backlog" -
  // contain the word "Backlog", so if this spec runs before anything has cleaned the board,
  // creating a column named "Backlog" leaves three columns matching that substring and
  // `th:contains("Backlog")` becomes ambiguous the same way complete-workflow.cy.js's
  // "In Progress" collision was (see that spec for the full story). Cypress does not sort specs
  // by filename, so nothing guarantees a cleaning spec runs first.
  //
  // Wait for the grid itself rather than assuming it is already there: on a cold start the board
  // can still be mounting, and cy.deleteColumns' own `cy.get('th')` gives up after the default
  // four seconds.
  // Rows are left alone deliberately: Board.jsx only draws a grid band per row in `rows`, so a
  // task with no row of its own renders nowhere at all once every row is gone (`enhancedRows`
  // maps `rows`, not the tasks). None of the tests below create a row - they have always leaned
  // on one already being there - so clearing rows here would make every task in this spec
  // invisible on a genuinely fresh board.
  cy.get('th', { timeout: 30000 }).should('exist');
  cy.deleteTasks();
  cy.deleteColumns();
  cy.ensureRowExists();

  // Every test here creates a task with no column of its own, which the app refuses when the
  // board has none at all (notifications.noColumnError) - it isn't optional the way a task's own
  // column is.
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

describe('Task Creation', () => {
  it('creates a task with the button in header', () => {
    cy.get('.header-nav > :nth-child(1)').click();
    cy.get('#task-title').type('Header Button Task');
    cy.get('[type="submit"]').click();
    cy.contains('.task', 'Header Button Task').should('exist');
  });
  
  it('creates a task with the custom command', () => {
    cy.createTask('Command Created Task');
    cy.contains('.task', 'Command Created Task').should('exist');
  });
  
  it('creates a task with description', () => {
    cy.get('[data-testid="open-add-task-form"]').click();
    cy.get('#task-title').type('Task with Description');
    cy.get('[type="submit"]').click();

    cy.contains('.task', 'Task with Description').should('exist');

    cy.contains('.task', 'Task with Description').click();
    cy.wait(100);
    cy.get('.edit-description-btn').click();
    cy.wait(100);
    cy.get('.description-textarea').type('This is a detailed description of the task');
    cy.wait(100);
    cy.contains('This is a detailed description of the task').should('be.visible');
    cy.get('.close-panel-btn').click();
  });
  
  it('creates a task with label', () => {
    cy.get('[data-testid="open-add-task-form"]').click();
    cy.get('#task-title').type('High Priority Task');
        
    cy.get('[type="submit"]').click();
    cy.wait(100);
      
    cy.contains('.task', 'High Priority Task').should('exist');
    cy.contains('.task', 'High Priority Task').click();
      
    cy.wait(100);
    cy.get('.add-label-button').click();
    cy.wait(100);
      
    cy.get('.label-section > :nth-child(2)').click();
    cy.wait(100);
        
    cy.get('.labels-container .label').should('exist');
    cy.get('.label-color-dot').should('be.visible');
    cy.contains('.label-text', 'High Priority').should('be.visible');
        
    cy.get('.close-panel-btn').click();
  });

});