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

/**
 * The same move as task-movement.cy.js, driven by keys instead of a pointer.
 *
 * This is the one behaviour in the suite that nothing else can assert. The Jest tests stub the
 * context, so they cover the bindings and never that a real card in a real board ends up in
 * another cell; `cy.drag` passing while this failed would mean the keyboard path had drifted from
 * the dragged one, which is what building it on handleMoveTask is meant to prevent.
 *
 * Both tests read the card's own `data-column-id` rather than naming a column, because which
 * column is "one to the right" depends on board order and the point is only that the card moved,
 * or did not.
 */
describe('Moving a task with the keyboard', () => {
  const columnOf = (title) => cy.contains('.task', title).invoke('attr', 'data-column-id');

  it('carries a task into another column', () => {
    cy.createColumn('Keyboard Source', 0);
    cy.createColumn('Keyboard Target', 0);
    cy.createTask('Keyboard Movable Task');

    cy.contains('.task', 'Keyboard Movable Task').should('be.visible');

    columnOf('Keyboard Movable Task').then((before) => {
      // Focus rather than click: a click opens the details panel, and the whole point is that the
      // card is reachable and operable with no pointer at all.
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
