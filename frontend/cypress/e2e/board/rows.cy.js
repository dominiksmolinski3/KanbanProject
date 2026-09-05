beforeEach(() => {
  cy.wait(500);
  cy.loginAsTestUser();
});

afterEach(() => {
  cy.on('window:alert', () => true);
  cy.on('window:confirm', () => true);
  
  cy.deleteTasks();
  cy.deleteColumns();
  cy.deleteRows();
});

describe('Row Management', () => {
  it('allows creating a new row', () => {
    cy.get('[data-testid="open-add-board-item-form"]').click();
    cy.get('[data-testid="add-row-column-tab-row"]').click();
    cy.get('#item-name').type('New Test Row');
    cy.get('#wip-limit').type('5');
    cy.get('[type="submit"]').click();
    cy.contains('tr', 'New Test Row').should('exist');
  });

  it('allows deleting a row', () => {
    cy.createRow('Delete Me Row', 0);
    cy.wait(300);
    // Deletion is confirmed through a react-toastify toast (Board.jsx's
    // handleDeleteRowClick), not a native window.confirm() - the delete button alone only
    // opens it, so the toast's own `.confirm-button` has to be clicked too.
    cy.contains('.grid-row-header', 'Delete Me Row').find('.delete-row-btn').click();
    cy.get('.confirm-button').click();
    cy.contains('.grid-row-header', 'Delete Me Row').should('not.exist');
  });

  it('allows editing row name', () => {
    cy.createRow('Edit Row Test', 3);
    cy.contains('tr', 'Edit Row Test')
    .find('.editable-text')
    .dblclick({force: true});
    cy.get('input').clear().type('Updated Row Name{enter}');
    cy.contains('.grid-row-header', 'Updated Row Name').should('exist');
  });

  it('enforces WIP limits on rows', () => {
    cy.createColumn('Test Column', 0);
    cy.createRow('Limited Row', 2);

    cy.createTask('Row Task 1');
    cy.createTask('Row Task 2');
    cy.createTask('Row Task 3');

    cy.contains('.task', 'Row Task 1').drag('tr:contains("Limited Row")');
    cy.contains('.task', 'Row Task 2').drag('tr:contains("Limited Row")');
    cy.contains('.task', 'Row Task 3').drag('tr:contains("Limited Row")');
    
    cy.get('tr:contains("Limited Row")').find('.wip-limit.exceeded').should('exist');
  });

});