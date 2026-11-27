afterEach(() => {
  cy.resetBoard();
});

describe('Moving a task with the keyboard', () => {
  const columnOf = (title) => cy.contains('.task', title).invoke('attr', 'data-column-id');

  it('carries a task into another column', () => {
    cy.openBoard({
      columns: ['Keyboard Source', 'Keyboard Target'],
      rows: ['Default Row'],
      tasks: ['Keyboard Movable Task'],
    });

    cy.contains('.task', 'Keyboard Movable Task').should('be.visible');

    columnOf('Keyboard Movable Task').then((before) => {
      cy.contains('.task', 'Keyboard Movable Task').find('.task-grip').focus();
      cy.focused().should('have.class', 'task-grip');

      cy.focused().type(' ');
      cy.contains('.task', 'Keyboard Movable Task').should('have.class', 'keyboard-held');
      cy.get('.grid-cell.keyboard-move-target').should('have.length', 1);

      cy.focused().type('{rightarrow}');
      cy.get('.grid-cell.keyboard-move-target').should('have.length', 1);

      cy.focused().type(' ');

      cy.get('.task.keyboard-held').should('not.exist');
      columnOf('Keyboard Movable Task').should('not.equal', before);
    });
  });

  it('leaves the task where it was when the move is cancelled', () => {
    cy.openBoard({
      columns: ['Keyboard Stay', 'Keyboard Elsewhere'],
      rows: ['Default Row'],
      tasks: ['Unmoved Task'],
    });

    cy.contains('.task', 'Unmoved Task').should('be.visible');

    columnOf('Unmoved Task').then((before) => {
      cy.contains('.task', 'Unmoved Task').find('.task-grip').focus().type(' ');
      cy.focused().type('{rightarrow}');
      cy.focused().type('{esc}');
      cy.wait(500);

      cy.get('.task.keyboard-held').should('not.exist');
      cy.get('.grid-cell.keyboard-move-target').should('not.exist');
      columnOf('Unmoved Task').should('equal', before);
    });
  });
});
