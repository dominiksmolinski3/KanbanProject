afterEach(() => {
  cy.on('window:alert', () => true);
  cy.on('window:confirm', () => true);
  cy.get('body').type('{esc}');

  cy.deleteTasks();
  cy.deleteColumns();
  cy.deleteRows();
});

describe('Complete User Journey', () => {
  it('allows a user to set up and use a kanban board', () => {
    cy.visit('/');
    cy.wait(1000);
    cy.loginAsTestUser();
    cy.wait(1000);

    // This spec owns its board rather than inheriting one. Every fresh board ships with the eight
    // columns BoardService.DEFAULT_COLUMNS seeds, one of which is named "In Progress" - so the
    // column created below is the *second* one by that name, and `th:contains("In Progress")`
    // matches two elements, which cy.trigger() refuses outright.
    //
    // Until now it passed only because the board/ specs happened to run first and their afterEach
    // had already emptied the board. Nothing guaranteed that: Cypress discovers specs by walking
    // the filesystem and the order is not sorted, so on 14 Sep this spec moved from sixth to third
    // - ahead of every spec that cleans - and trunk went red on two consecutive merges whose
    // changes could not have touched it. Cleaning here removes the dependency instead of
    // re-freezing the order that happened to satisfy it.
    //
    // Wait for the grid itself rather than trusting the cy.wait(1000) above: on a cold start the
    // board can still be mounting, and cy.deleteColumns' own `cy.get('th')` gives up after the
    // default four seconds. The corner cell and the "+ Add column" placeholder are always there
    // once it has rendered, so this asserts the board exists without assuming it holds anything.
    cy.get('th', { timeout: 30000 }).should('exist');

    cy.deleteTasks();
    cy.deleteColumns();
    cy.deleteRows();

    cy.createColumn('Backlog', 0);
    cy.createColumn('In Progress', 3);
    cy.createColumn('Done', 0);

    cy.createRow('Features', 4);
    cy.createRow('Bugs', 2);
      
    cy.wait(1000);
    cy.get('.header-nav > :nth-child(1)').click();
    cy.get('#task-title').type('Implement login');
    cy.get('[type="submit"]').click();
      
    cy.contains('.task', 'Implement login').drag('th:contains("In Progress")');
    cy.wait(300);
    cy.contains('.task', 'Implement login').click();
    cy.get('.subtask-input').type('Create UI{enter}');
    cy.get('.add-subtask-btn').click();
    // Adding a subtask reloads the whole panel (TaskDetails' `loading` state swaps it for a
    // "Loading kanban board..." placeholder), which unmounts .subtask-input mid-flight. Waiting
    // for the subtask to actually land, instead of a fixed cy.wait(), is what keeps the next
    // cy.get('.subtask-input') from racing that reload and finding a stale, disabled element.
    cy.contains('.subtask-item', 'Create UI').should('exist');

    cy.get('.subtask-input').type('Validate inputs{enter}');
    cy.get('.add-subtask-btn').click();
    cy.contains('.subtask-item', 'Validate inputs').should('exist');

    cy.get('.subtask-item').first().find('input[type="checkbox"]').check();

    cy.get('.close-panel-btn').click();
      
    cy.createTask('Fix bug #123');
    cy.contains('.task', 'Fix bug #123').drag('th:contains("In Progress")');

    cy.createTask('Update docs');
    cy.contains('.task', 'Update docs').drag('th:contains("In Progress")');

    cy.createTask('WIP limit test');
    cy.contains('.task', 'WIP limit test').drag('th:contains("In Progress")');

      
    cy.get('th:contains("In Progress")').find('.wip-limit.exceeded').should('exist');
  });
});