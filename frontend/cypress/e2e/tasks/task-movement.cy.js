beforeEach(() => {
  cy.loginAsTestUser();
  cy.get('th', { timeout: 30000 }).should('exist');
  cy.deleteTasks();
  cy.deleteColumns();
  cy.ensureRowExists();
});

afterEach(() => {
  cy.on('window:alert', () => true);
  cy.on('window:confirm', () => true);
  cy.get('body').type('{esc}');
      
  cy.deleteTasks();
  cy.deleteColumns();
  cy.deleteRows();
});

describe('Task Movement', () => {
  it('moves a task between columns', () => {
    cy.createColumn('In Progress', 0);
    cy.createColumn('Done for moving test case', 0);
    cy.createTask('Movable Task');

    cy.contains('.task', 'Movable Task').should('be.visible');

    cy.contains('.task', 'Movable Task').drag('th:contains("In Progress")');
    cy.wait(500);

    cy.get('th:contains("In Progress")').parents('table')
      .contains('.task', 'Movable Task').should('exist');

    cy.contains('.task', 'Movable Task').drag('th:contains("Done for moving test case")');
    cy.wait(500);

    cy.get('th:contains("Done for moving test case")').parents('table')
      .contains('.task', 'Movable Task').should('exist');
  });
  
  it('drags a card with a ghost the browser can snapshot whole', () => {
    cy.createColumn('Backlog', 0);
    cy.createTask('Ghost Task');

    cy.contains('.task', 'Ghost Task').then(($task) => {
      let ghost;
      const dataTransfer = {
        data: {},
        types: [],
        setData(format, data) { this.data[format] = data; this.types.push(format); },
        getData(format) { return this.data[format]; },
        setDragImage(el) {
          Promise.resolve().then(() => {
            const style = el.ownerDocument.defaultView.getComputedStyle(el);
            ghost = { position: style.position, opacity: style.opacity, animation: style.animationName };
          });
        }
      };

      cy.wrap($task).trigger('dragstart', { dataTransfer });
      cy.then(() => {
        expect(ghost, 'the style the browser snapshots').to.deep.equal({
          position: 'fixed', opacity: '1', animation: 'none'
        });
      });
      cy.wrap($task).trigger('dragend', { dataTransfer, force: true });
    });
  });

  it('moves a task between rows', () => {
    cy.createColumn('Backlog', 0);
    cy.createRow('Bugs for moving test case', 0);
    cy.createTask('Movable Task');
    cy.wait(100);
    cy.contains('.task', 'Movable Task').should('be.visible');
      
    cy.contains('.task', 'Movable Task').drag('tr:contains("Bugs for moving test case")');
    cy.wait(500);
      
    cy.contains('tr', 'Bugs for moving test case').parents('table')
      .contains('.task', 'Movable Task').should('exist');
  });
  
});