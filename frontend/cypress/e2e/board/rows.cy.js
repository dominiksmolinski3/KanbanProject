afterEach(() => {
  cy.resetBoard();
});

describe('Row Management', () => {
  it('allows creating a new row', () => {
    cy.openBoard();
    cy.get('[data-testid="open-add-board-item-form"]').click();
    cy.get('[data-testid="add-row-column-tab-row"]').click();
    cy.get('#item-name').type('New Test Row');
    cy.get('#wip-limit').type('5');
    cy.get('[type="submit"]').click();
    cy.contains('tr', 'New Test Row').should('exist');
  });

  it('allows deleting a row', () => {
    cy.openBoard({ rows: ['Keep Me Row', 'Delete Me Row'] });
    cy.contains('.grid-row-header', 'Delete Me Row').find('.delete-row-btn').click();
    cy.get('.confirm-button').click();
    cy.contains('.grid-row-header', 'Delete Me Row').should('not.exist');
  });

  it('allows editing row name', () => {
    cy.openBoard({ rows: [{ name: 'Edit Row Test', wipLimit: 3 }] });
    cy.contains('tr', 'Edit Row Test')
      .find('.editable-text')
      .dblclick({ force: true });
    cy.get('input').clear().type('Updated Row Name{enter}');
    cy.contains('.grid-row-header', 'Updated Row Name').should('exist');
  });

  it('enforces WIP limits on rows', () => {
    cy.openBoard({
      columns: ['Test Column'],
      rows: ['Start Row', { name: 'Limited Row', wipLimit: 2 }],
      tasks: ['Row Task 1', 'Row Task 2', 'Row Task 3'],
    });

    cy.contains('.task', 'Row Task 1').drag('.grid-row-header:contains("Limited Row")');
    cy.contains('.task', 'Row Task 2').drag('.grid-row-header:contains("Limited Row")');
    cy.get('tr:contains("Limited Row")').find('.wip-limit.exceeded').should('not.exist');

    cy.contains('.task', 'Row Task 3').drag('.grid-row-header:contains("Limited Row")');
    cy.get('tr:contains("Limited Row")').find('.wip-limit.exceeded').should('exist');
  });
});
