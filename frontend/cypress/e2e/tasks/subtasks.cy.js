afterEach(() => {
  cy.resetBoard();
});

const openBoardWith = (title) =>
  cy.openBoard({ columns: ['Backlog'], rows: ['Default Row'], tasks: [title] });

describe('Subtasks', () => {
  it('creates subtasks within a task', () => {
    openBoardWith('Parent Task');
    cy.addSubtasks('Parent Task', ['Subtask 1', 'Subtask 2', 'Subtask 3']);

    cy.contains('.task', 'Parent Task').click();
    cy.get('.subtask-item').should('have.length', 3);
    cy.contains('.subtask-item', 'Subtask 1').should('exist');
    cy.contains('.subtask-item', 'Subtask 2').should('exist');
    cy.contains('.subtask-item', 'Subtask 3').should('exist');
    cy.get('.close-panel-btn').click();
  });

  it('marks subtasks as complete', () => {
    openBoardWith('Completion Task');
    cy.addSubtasks('Completion Task', ['Mark me complete']);

    cy.contains('.task', 'Completion Task').click();
    cy.contains('.subtask-item', 'Mark me complete').find('input[type="checkbox"]').check();
    cy.contains('.subtask-item', 'Mark me complete').find('label').should('have.class', 'completed');

    cy.get('.close-panel-btn').click();
  });

  it('deletes subtasks', () => {
    openBoardWith('Deletion Task');
    cy.addSubtasks('Deletion Task', ['Delete me']);

    cy.contains('.task', 'Deletion Task').click();
    cy.contains('.subtask-item', 'Delete me').find('.delete-subtask-btn').click();
    cy.get('.confirm-btn').click();
    cy.contains('.subtask-item', 'Delete me').should('not.exist');

    cy.get('.close-panel-btn').click();
  });
});
