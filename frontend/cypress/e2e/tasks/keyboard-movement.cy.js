beforeEach(() => {
  cy.loginAsTestUser();
});

afterEach(() => {
  cy.on('window:alert', () => true);
  cy.on('window:confirm', () => true);
  cy.get('body').type('{esc}');

  cy.deleteTasks();
  cy.deleteColumns();
  cy.deleteRows();
});

describe('Moving a task with the keyboard', () => {
  const columnOf = (title) => cy.contains('.task', title).invoke('attr', 'data-column-id');

  it('carries a task into another column', () => {
    cy.createColumn('Keyboard Source', 0);
    cy.createColumn('Keyboard Target', 0);
    cy.createTask('Keyboard Movable Task');

    cy.contains('.task', 'Keyboard Movable Task').should('be.visible');

    columnOf('Keyboard Movable Task').then((before) => {
      cy.contains('.task', 'Keyboard Movable Task').focus();
      cy.focused().should('have.class', 'task');

      cy.focused().type(' ');
      cy.contains('.task', 'Keyboard Movable Task').should('have.class', 'keyboard-held');
      cy.get('.grid-cell.keyboard-move-target').should('have.length', 1);

      cy.contains('.task', 'Keyboard Movable Task').type('{rightarrow}');
      cy.get('.grid-cell.keyboard-move-target').should('have.length', 1);

      cy.contains('.task', 'Keyboard Movable Task').type(' ');
      cy.wait(500);

      cy.get('.task.keyboard-held').should('not.exist');
      columnOf('Keyboard Movable Task').should('not.equal', before);
    });
  });

  it('leaves the task where it was when the move is cancelled', () => {
    cy.createColumn('Keyboard Stay', 0);
    cy.createColumn('Keyboard Elsewhere', 0);
    cy.createTask('Unmoved Task');

    cy.contains('.task', 'Unmoved Task').should('be.visible');

    columnOf('Unmoved Task').then((before) => {
      cy.contains('.task', 'Unmoved Task').focus().type(' ');
      cy.contains('.task', 'Unmoved Task').type('{rightarrow}');
      cy.contains('.task', 'Unmoved Task').type('{esc}');
      cy.wait(500);

      cy.get('.task.keyboard-held').should('not.exist');
      cy.get('.grid-cell.keyboard-move-target').should('not.exist');
      columnOf('Unmoved Task').should('equal', before);
    });
  });
});
