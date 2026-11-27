Cypress.Commands.add('signIn', (email, password) => {
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
});

Cypress.Commands.add('login', (email, password) => {
  cy.signIn(email, password);
  cy.visit('/board');
});

Cypress.Commands.add('signInAsTestUser', () => {
  cy.fixture('test-account').then((account) => {
    cy.signIn(account.email, account.password);
  });
});

Cypress.Commands.add('loginAsTestUser', () => {
  cy.fixture('test-account').then((account) => {
    cy.login(account.email, account.password);
  });
});

const signedInStorage = () =>
  cy.getAllLocalStorage({ log: false }).then((all) => {
    const stored = all[new URL(Cypress.config('baseUrl')).origin] || {};
    expect(stored.token, 'a signed-in session to call the API with').to.be.a('string');
    return stored;
  });

const onActiveBoard = (path, stored) =>
  (stored.activeBoardId ? `${path}?boardId=${stored.activeBoardId}` : path);

const api = (stored, options) =>
  cy.request({
    log: false,
    ...options,
    headers: { Authorization: `Bearer ${stored.token}`, ...(options.headers || {}) },
  });

const named = (item) => (typeof item === 'string' ? { name: item } : item);

Cypress.Commands.add('resetBoard', () => {
  signedInStorage().then((stored) => {
    ['tasks', 'columns', 'rows'].forEach((kind) => {
      api(stored, { url: onActiveBoard(`/api/${kind}`, stored) }).then(({ body }) => {
        body.forEach(({ id }) => {
          api(stored, { method: 'DELETE', url: `/api/${kind}/${id}`, failOnStatusCode: false })
            .its('status').should('be.oneOf', [204, 404]);
        });
      });
    });
  });
});

Cypress.Commands.add('seedBoard', ({ columns = [], rows = [], tasks = [] } = {}) => {
  cy.resetBoard();
  signedInStorage().then((stored) => {
    const ids = { columns: {}, rows: {} };
    const create = (kind, items) => items.map(named).forEach(({ name, wipLimit = 0 }) => {
      api(stored, { method: 'POST', url: onActiveBoard(`/api/${kind}`, stored), body: { name, wipLimit } })
        .then(({ body }) => { ids[kind][name] = body.id; });
    });
    create('columns', columns);
    create('rows', rows);
    cy.then(() => {
      tasks.map((task) => (typeof task === 'string' ? { title: task } : task)).forEach(({ title, column, row }) => {
        api(stored, {
          method: 'POST',
          url: onActiveBoard('/api/tasks', stored),
          body: {
            title,
            column: { id: ids.columns[column ?? named(columns[0]).name] },
            row: { id: ids.rows[row ?? named(rows[0]).name] },
          },
        });
      });
    });
  });
});

Cypress.Commands.add('openBoard', (layout = {}) => {
  cy.signInAsTestUser();
  cy.seedBoard(layout);
  cy.visit('/board');
  cy.get('[data-testid="open-add-board-item-form"]').should('be.visible');
  (layout.columns || []).map(named).forEach(({ name }) => cy.contains('th', name).should('exist'));
  (layout.rows || []).map(named).forEach(({ name }) => cy.contains('.grid-row-header', name).should('exist'));
  (layout.tasks || []).forEach((task) => cy.contains('.task', task.title ?? task).should('exist'));
});

Cypress.Commands.add('createColumn', (name, wipLimit) => {
  cy.get('[data-testid="open-add-board-item-form"]').click();
  cy.get('[data-testid="add-row-column-tab-column"]').click();
  cy.get('#item-name').type(name);
  if (wipLimit > 0) {
    cy.get('#wip-limit').type(wipLimit);
  }
  cy.get('[type="submit"]').click();
  cy.contains('th', name).should('exist');
  cy.get('#item-name').should('not.exist');
});

Cypress.Commands.add('createRow', (name, wipLimit) => {
  cy.get('[data-testid="open-add-board-item-form"]').click();
  cy.get('[data-testid="add-row-column-tab-row"]').click();
  cy.get('#item-name').clear().type(name);
  if (wipLimit > 0) {
    cy.get('#wip-limit').clear().type(wipLimit);
  }
  cy.get('[type="submit"]').click();
  cy.contains('tr', name).should('exist');
  cy.get('#item-name').should('not.exist');
});

Cypress.Commands.add('createTask', (title) => {
  cy.get('[data-testid="open-add-task-form"]').click();
  cy.get('#task-title').type(title);
  cy.get('[type="submit"]').click();
  cy.contains('.task', title).should('exist');
  cy.get('#task-title').should('not.exist');
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

Cypress.Commands.add('addSubtasks', (title, subtasks) => {
  cy.contains('.task', title).click();
  subtasks.forEach((subtask) => {
    cy.get('.subtask-input').should('have.value', '').type(subtask);
    cy.get('.add-subtask-btn').click();
    cy.contains('.subtask-item', subtask).should('exist');
  });
  cy.get('.close-panel-btn').click();
  cy.get('.close-panel-btn').should('not.exist');
});

Cypress.Commands.add('expectNoAxeViolations', () => {
  cy.window({ log: false }).should((win) => {
    const running = win.document.getAnimations()
      .filter((animation) => animation.playState === 'running'
        && animation.effect.getComputedTiming().endTime <= 1000)
      .map((animation) => animation.animationName || animation.transitionProperty);
    expect(running, 'fades and transitions still running').to.have.length(0);
  });
  cy.readFile('node_modules/axe-core/axe.min.js', 'utf8', { log: false }).then((source) => {
    cy.window({ log: false })
      .then((win) => {
        win.eval(source);
        return win.axe.run(win.document);
      })
      .then(({ violations }) => {
        const report = violations
          .map((violation) => [
            `${violation.id} (${violation.impact}): ${violation.help}`,
            ...violation.nodes.map((node) => `    ${node.target.join(' ')}`),
          ].join('\n'))
          .join('\n');
        expect(violations, `\n${report}\n`).to.have.length(0);
      });
  });
});

Cypress.Commands.add('prepareAxe', () => {
  cy.intercept({ resourceType: 'document' }, (req) => {
    req.on('response', (res) => {
      delete res.headers['content-security-policy'];
    });
  });
});

Cypress.Commands.add('visitThemed', (path, theme, ready) => {
  cy.visit(path, {
    onBeforeLoad(win) {
      win.localStorage.setItem('kanban-theme', theme);
    },
  });
  cy.get('html').should('have.attr', 'data-theme', theme);
  cy.get(ready, { timeout: 10000 }).should('be.visible');
});
