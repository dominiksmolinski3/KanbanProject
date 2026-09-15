beforeEach(() => {
  cy.loginAsTestUser();
  // Own the board before creating a column named "Backlog" - two of the default seeded columns
  // ("Product Backlog", "Sprint Backlog") also contain that word, and an unclean board makes
  // `th:contains("Backlog")` ambiguous. See task-creation.cy.js for the full story.
  cy.get('th', { timeout: 30000 }).should('exist');
  cy.deleteTasks();
  cy.deleteColumns();
  // Rows are left alone and topped up rather than cleared - see cy.ensureRowExists in
  // commands.js - because a task with no row of its own renders nowhere once the board has zero.
  cy.ensureRowExists();

  // cy.createTask needs a column to exist on the board.
  cy.createColumn('Backlog', 0);
});

afterEach(() => {
  cy.on('window:alert', () => true);
  cy.on('window:confirm', () => true);
  cy.get('body').type('{esc}');

  // deleteTasks() closes an open panel first, on every spec's behalf - see commands.js.
  cy.deleteTasks();
  cy.deleteColumns();
  cy.deleteRows();
});

// Assignment moved into the task panel's "relationships" tab (alongside parent/child task
// management) at some point after this spec was last run - it used to be an icon in the panel's
// main view that toggled an inline form. Selecting "User One" needs a second board member too;
// GET /api/users only lists accounts the caller shares a board with (see CLAUDE.md's tenancy
// section), so seed-test-account.js adds one to the seeded account's board before this spec runs.
describe('User Assignment', () => {

  it('allows assigning a user to a task', () => {
    cy.createTask('Assignment Test Task');

    cy.contains('.task', 'Assignment Test Task').click();
    cy.get('.parent-child-btn').click();

    cy.get('.user-select').select('User One');
    cy.get('.assign-btn-relationships').click();
    cy.get('.assigned-user-card').should('exist');

    cy.get('.close-panel-btn').click();

    cy.contains('.task', 'Assignment Test Task').find('.avatar-preview').should('exist');
  });

  it('allows removing user assignment', () => {
    cy.createTask('Remove Assignment Task');
    cy.contains('.task', 'Remove Assignment Task').click();
    cy.get('.parent-child-btn').click();

    cy.get('.user-select').select('User One');
    cy.get('.assign-btn-relationships').click();
    cy.get('.assigned-user-card').should('exist');

    // The remove control lives in the panel's always-visible assigned-users bar, not in the
    // relationships tab the assignment above just used - removing does not need the tab switch.
    cy.get('.remove-user-btn').click();
    cy.get('.confirm-btn').click();

    cy.get('.assigned-user-card').should('not.exist');
    cy.get('.close-panel-btn').click();
  });

});
