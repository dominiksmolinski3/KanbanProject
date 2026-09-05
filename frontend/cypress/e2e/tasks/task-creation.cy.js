beforeEach(() => {
  cy.loginAsTestUser();
  // Every test here creates a task with no column of its own, which the app refuses when the
  // board has none at all (notifications.noColumnError) - it isn't optional the way a task's own
  // column is. The suite's own cleanup (deleteColumns) now genuinely empties the board after
  // every test, so each test that needs one has to create it, rather than relying on a column
  // left behind by whichever spec happened to run before this one.
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