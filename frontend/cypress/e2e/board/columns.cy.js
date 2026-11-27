afterEach(() => {
  cy.resetBoard();
});

describe('Column Management', () => {
  it('allows creating a new column', () => {
    cy.openBoard();
    cy.get('[data-testid="open-add-board-item-form"]').click();
    cy.get('[data-testid="add-row-column-tab-column"]').should('be.visible');
    cy.get('#item-name').type('New Test Column');
    cy.get('#wip-limit').type('3');
    cy.get('[type="submit"]').click();
    cy.contains('th', 'New Test Column').should('exist');
  });

  it('allows editing column name', () => {
    cy.openBoard({ columns: [{ name: 'Edit Test', wipLimit: 3 }] });
    cy.contains('th', 'Edit Test')
      .find('.editable-text')
      .dblclick({ force: true });
    cy.get('input').clear().type('Updated Column{enter}');
    cy.contains('th', 'Updated Column').should('exist');
  });

  it('keeps the board on screen when a rename is refused', () => {
    cy.openBoard({ columns: [{ name: 'Refused Rename', wipLimit: 3 }] });
    cy.intercept('PATCH', '/api/columns/*', {
      statusCode: 400,
      body: { code: 'VALIDATION_ERROR', message: 'name: size must be between 1 and 255' },
    }).as('refusedRename');
    cy.contains('th', 'Refused Rename')
      .find('.editable-text')
      .dblclick({ force: true });
    cy.get('input.editable-text-input').clear().type('Not Accepted{enter}');
    cy.wait('@refusedRename');
    cy.get('.board-error').should('not.exist');
    cy.contains('th', 'Refused Rename').should('exist');
    cy.get('.Toastify__toast--error').should('be.visible');
  });

  it('enforces WIP limits on columns', () => {
    cy.openBoard({
      columns: ['Start', { name: 'Limited', wipLimit: 2 }],
      rows: ['Test Row'],
      tasks: ['Task 1', 'Task 2', 'Task 3'],
    });

    cy.get('th:contains("Limited")').find('.wip-limit.exceeded').should('not.exist');

    cy.contains('.task', 'Task 1').drag('th:contains("Limited")');
    cy.contains('.task', 'Task 2').drag('th:contains("Limited")');
    cy.get('th:contains("Limited")').find('.wip-limit.exceeded').should('not.exist');

    cy.contains('.task', 'Task 3').drag('th:contains("Limited")');
    cy.get('th:contains("Limited")').find('.wip-limit.exceeded').should('exist');
  });
});
