afterEach(() => {
  cy.resetBoard();
});

describe('Task Movement', () => {
  const cellOf = (title) => cy.contains('.task', title).closest('.grid-cell');

  const expectIn = (title, header, attribute) =>
    cy.get(header).invoke('attr', attribute).then((id) => {
      cellOf(title).should('have.attr', attribute, id);
    });

  it('moves a task between columns', () => {
    cy.openBoard({
      columns: ['Backlog', 'In Progress', 'Done for moving test case'],
      rows: ['Default Row'],
      tasks: ['Movable Task'],
    });

    cy.contains('.task', 'Movable Task').drag('th:contains("In Progress")');
    expectIn('Movable Task', 'th:contains("In Progress")', 'data-column-id');

    cy.contains('.task', 'Movable Task').drag('th:contains("Done for moving test case")');
    expectIn('Movable Task', 'th:contains("Done for moving test case")', 'data-column-id');
  });

  it('drags a card with a ghost the browser can snapshot whole', () => {
    cy.openBoard({ columns: ['Backlog'], rows: ['Default Row'], tasks: ['Ghost Task'] });

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
    cy.openBoard({
      columns: ['Backlog'],
      rows: ['Default Row', 'Bugs for moving test case'],
      tasks: ['Movable Task'],
    });

    cy.contains('.task', 'Movable Task').drag('.grid-row-header:contains("Bugs for moving test case")');
    expectIn('Movable Task', '.grid-row-header:contains("Bugs for moving test case")', 'data-row-id');
  });
});
