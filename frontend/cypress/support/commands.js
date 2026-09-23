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

  cy.wrap(subject, { log: false }).then($el => {
    if (Cypress.dom.isAttached($el)) {
      cy.wrap($el, { log: false }).trigger('dragend', { dataTransfer, force: true });
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
    cy.get('.subtask-input').should('have.value', '').type(`${subtask}`);
    cy.get('.add-subtask-btn').click();
    cy.contains('.subtask-item', subtask).should('exist');
  });
    
  cy.get('.close-panel-btn').click();
  cy.wait(300);
});

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
    const remaining = $body.find('.delete-btn').length;
    if (remaining === 0) {
      return;
    }
    cy.get('.delete-btn').first().click();
    cy.get('.confirm-delete-btn').first().click({ force: true });
    cy.get('.delete-btn', { timeout: 10000 }).should('have.length', remaining - 1);
    cy.deleteTasks();
  });
});

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

Cypress.Commands.add('ensureRowExists', () => {
  cy.get('.grid-row-header').then($rowHeaders => {
    if ($rowHeaders.length <= 1) {
      cy.createRow('Cypress-Default-Row', 0);
    }
  });
});