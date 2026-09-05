// ***********************************************
// This example commands.js shows you how to
// create various custom commands and overwrite
// existing commands.
//
// For more comprehensive examples of custom
// commands please read more here:
// https://on.cypress.io/custom-commands
// ***********************************************
//
//
// -- This is a parent command --
// Cypress.Commands.add('login', (email, password) => { ... })
//
//
// -- This is a child command --
// Cypress.Commands.add('drag', { prevSubject: 'element'}, (subject, options) => { ... })
//
//
// -- This is a dual command --
// Cypress.Commands.add('dismiss', { prevSubject: 'optional'}, (subject, options) => { ... })
//
//
// -- This will overwrite an existing command --
// Cypress.Commands.overwrite('visit', (originalFn, url, options) => { ... })


// Cypress's default test isolation clears localStorage before every single test, and nearly
// every spec logs in from its own beforeEach - across the whole suite that is dozens of real
// POST /api/auth/login calls inside a couple of minutes, which is exactly what AuthRateLimiter's
// escalating cooldown (see CLAUDE.md, "The rate limiter is a burst then a doubling cooldown") is
// built to slow down. cy.session() is the Cypress-native fix: it logs in for real once, caches
// the resulting localStorage, and restores that cache for every later test instead of re-running
// the login form - `cacheAcrossSpecs` extends that cache to the whole `cypress run`, not just one
// spec file, since every spec here shares the one seeded account.
Cypress.Commands.add('login', (email, password) => {
  cy.session(
    [email, password],
    () => {
      cy.visit('/');
      cy.get('input[type="email"]').first().type(email);
      cy.get('input[type="password"]').first().type(password);
      cy.contains('button', 'Sign In').click();
      cy.url().should('include', '/board');
    },
    {
      cacheAcrossSpecs: true,
      validate: () => {
        cy.window().then((win) => {
          expect(win.localStorage.getItem('token')).to.exist;
        });
      },
    }
  );
  cy.visit('/board');
  cy.wait(300);
});

// The one seeded, project-owned account the whole suite signs in as - see
// frontend/cypress/fixtures/test-account.json and frontend/cypress/seed-test-account.js for how
// it gets created and verified before the run starts. Specs should call this rather than
// cy.login() with credentials of their own, so the account lives in exactly one place.
Cypress.Commands.add('loginAsTestUser', () => {
  cy.fixture('test-account').then((account) => {
    cy.login(account.email, account.password);
  });
});

Cypress.Commands.add('createColumn', (name, wipLimit) => {
  cy.get('[data-testid="open-add-board-item-form"]').click();
  cy.wait(500);
  cy.get('[data-testid="add-row-column-tab-column"]').click();
  cy.get('#item-name').type(name);
  if (wipLimit > 0) {
    cy.get('#wip-limit').type(wipLimit);
  }
  cy.get('[type="submit"]').click();
  cy.contains('th', name).should('exist');
  cy.wait(300);
});

Cypress.Commands.add('createRow', (name, wipLimit) => {
  cy.get('[data-testid="open-add-board-item-form"]').click();
  cy.wait(500);

  cy.get('[data-testid="add-row-column-tab-row"]').click();
  cy.wait(500);

  cy.get('#item-name').clear().type(name);
  if (wipLimit > 0) {
    cy.get('#wip-limit').clear().type(wipLimit);
  }

  cy.get('[type="submit"]').click();

  cy.contains('tr', name).should('exist');
  cy.wait(300);
});

Cypress.Commands.add('createTask', (title) => {
    cy.wait(100);
    cy.get('[data-testid="open-add-task-form"]').click();
    cy.get('#task-title').type(title);
    cy.get('[type="submit"]').click();
    cy.contains('.task', title).should('exist');
    cy.wait(300);
  });
  
Cypress.Commands.add('drag', { prevSubject: 'element' }, (subject, targetSelector) => {
  const target = cy.get(targetSelector);
    
  const dataTransfer = {
    data: {},
    setData(format, data) {
      this.data[format] = data;
      this.types.push(format);
    },
    getData(format) {
      return this.data[format];
    },
    clearData() {
      this.data = {};
      this.types = [];
    },
    types: []
  };
    
  cy.wrap(subject).trigger('mousedown', { which: 1 });
  cy.wrap(subject).trigger('dragstart', { dataTransfer });

  target.trigger('dragover', { dataTransfer });
  target.trigger('drop', { dataTransfer });

  // The drop above can make the app re-render and replace this exact node - moving a task
  // optimistically re-renders the column/row it left, and if that happens before dragend fires,
  // `subject` (captured once, at the top of this command) is a detached element. dragend is
  // native drag-and-drop cleanup on the *source*, and a source that is no longer in the document
  // has nothing left to clean up, so it is only fired when the node is still attached - doing
  // that unconditionally is what "the page updated while this command was executing" means.
  cy.wrap(subject, { log: false }).then($el => {
    if (Cypress.dom.isAttached($el)) {
      cy.wrap($el, { log: false }).trigger('dragend', { dataTransfer });
    }
  });
  cy.wait(300);
});

Cypress.Commands.add('createTestBoard', () => {
  cy.createColumn('To Do', 3);
  cy.wait(100);
  cy.createColumn('In Progress', 2);
  cy.wait(100);
  cy.createColumn('Done', 0);
  cy.wait(100);
  cy.createRow('Features', 3);
  cy.wait(100);
  cy.createRow('Bugs', 3);
  cy.wait(300);
});
  
Cypress.Commands.add('setupTaskWithSubtasks', (title, subtasks = []) => {
  cy.createTask(title);
  cy.contains('.task', title).click();
    
  subtasks.forEach(subtask => {
    cy.get('.subtask-input').type(`${subtask}`);
    cy.wait(300);
    cy.get('.add-subtask-btn').click();
  });
    
  cy.get('.close-panel-btn').click();
  cy.wait(300);
});

// Cleanup's own first move, regardless of which spec called it: a task panel left open by a
// failed test sits in the same component tree as the board grid (see Board.jsx/Task.jsx), so it
// does not block clicks on `.delete-btn`/`.delete-column-btn` underneath it - but every command
// below still expects a clean board, and closing the panel first is one assertion cheaper than
// discovering later that it was in the way.
Cypress.Commands.add('closePanelIfOpen', () => {
  cy.get('body').then($body => {
    if ($body.find('.close-panel-btn').length > 0) {
      cy.get('.close-panel-btn').click();
    }
  });
});

Cypress.Commands.add('deleteTasks', () => {
  cy.closePanelIfOpen();
  cy.get('body').then($body => {
    if ($body.find('.task').length > 0) {
      cy.get('.delete-btn').first().click();
      cy.wait(100);
      cy.get('body').then($newBody => {
        if ($newBody.find('.confirm-delete-btn').length > 0) {
          cy.get('.confirm-delete-btn').click({ force: true });
          cy.wait(300);
          cy.deleteTasks();
        }
      });
    }
  });
  cy.wait(300);
});

// Deleting a column or row asks for confirmation through a react-toastify toast
// (Board.jsx's handleDeleteColumnClick/handleDeleteRowClick render a `.confirm-button` inside
// the toast) rather than a native window.confirm(). cy.on('window:confirm', ...) in the specs'
// afterEach hooks never sees that dialog, so the delete button alone only opens the toast - the
// item is never actually removed. Clicking `.confirm-button` is what completes the deletion;
// without it these commands recurse forever, since their own exit condition never becomes true.
//
// The toast's exit animation outlives a fixed cy.wait(), so the still-fading-out toast from one
// iteration can still be in the DOM when the next delete opens a new one - `.first()` picks the
// live one over the stale one, and asserting the toast is gone (rather than just waiting a fixed
// amount of time) is what stops two of them from ever being on screen at once.
//
// `th` here is [grid-corner, column, column, ..., add-placeholder-header] - a real column never
// sits at index 0 (the corner) or the last index (the "+ Add column" placeholder, which has no
// .delete-column-btn), so eq(1) is always a real one to delete, all the way down to none left.
Cypress.Commands.add('deleteColumns', () => {
  cy.get('th').then($columns => {
    if ($columns.length > 2) {
      cy.get('th').eq(1).find('.delete-column-btn').click({ force: true });
      cy.get('.confirm-button').first().click({ force: true });
      cy.get('.confirm-button', { timeout: 10000 }).should('not.exist');
      cy.deleteColumns();
    }
  });
  cy.wait(300);
});

// `.grid-row-header` here is [row, row, ..., add-placeholder-row] - no leading structural cell
// the way columns have a corner, but the trailing "+ Add row" placeholder carries the same class
// and has no .delete-row-btn. The app itself refuses to delete the last row (Board.jsx's
// handleDeleteRowClick), so this stops one row early: length > 2 means at least two real rows
// remain, and eq(0) is always one of them.
Cypress.Commands.add('deleteRows', () => {
  cy.get('.grid-row-header').then($rowHeaders => {
    if ($rowHeaders.length > 2) {
      cy.get('.grid-row-header').eq(0).find('.delete-row-btn').click({ force: true });
      cy.get('.confirm-button').first().click({ force: true });
      cy.get('.confirm-button', { timeout: 10000 }).should('not.exist');
      cy.deleteRows();
    }
  });
  cy.wait(300);
});