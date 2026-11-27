const themes = ['light', 'dark'];

const BOARD_READY = '[data-testid="open-add-board-item-form"]';

const opened = [
  {
    name: 'the board switcher',
    open: () => cy.get('.board-switcher-toggle').click(),
    shows: '.board-switcher-toggle[aria-expanded="true"]',
  },
  {
    name: 'the settings menu',
    open: () => cy.get('[data-testid="header-menu-toggle"]').click(),
    shows: '.header-menu-panel',
  },
  {
    name: 'the language menu',
    open: () => {
      cy.get('[data-testid="header-menu-toggle"]').click();
      cy.get('.language-switcher-button').click();
    },
    shows: '.language-switcher-button[aria-expanded="true"]',
  },
  {
    name: 'the add column and row form',
    open: () => cy.get(BOARD_READY).click(),
    shows: '[data-testid="add-row-column-tab-column"]',
  },
  {
    name: 'the add task form',
    open: () => cy.get('[data-testid="open-add-task-form"]').click(),
    shows: '#task-title',
  },
  {
    name: 'the WIP limit form',
    open: () => cy.get('[data-testid="open-wip-limit-form"]').click(),
    shows: 'form',
  },
  {
    name: 'task search',
    open: () => cy.get('[data-testid="open-task-search"]').click(),
    shows: '[data-testid="task-search"]',
  },
  {
    name: 'the message dock',
    open: () => cy.get('.dock-toggle').click(),
    shows: '.dock-panel',
  },
];

describe('Accessibility of what opens on the board, in a real browser', () => {
  beforeEach(() => {
    cy.prepareAxe();
    cy.signInAsTestUser();
  });

  afterEach(() => {
    cy.resetBoard();
  });

  themes.forEach((theme) => {
    describe(`${theme} theme`, () => {
      opened.forEach(({ name, open, shows }) => {
        it(`${name} has no axe violations`, () => {
          cy.seedBoard({ columns: ['Accessibility column'], rows: ['Accessibility row'] });
          cy.visitThemed('/board', theme, BOARD_READY);
          cy.contains('th', 'Accessibility column').should('exist');
          open();
          cy.get(shows).should('be.visible');
          cy.expectNoAxeViolations();
        });
      });

      describe('with a card on the board', () => {
        beforeEach(() => {
          cy.seedBoard({
            columns: ['Accessibility column'],
            rows: ['Accessibility row'],
            tasks: ['Accessibility card'],
          });
          cy.visitThemed('/board', theme, BOARD_READY);
          cy.contains('.task', 'Accessibility card').should('be.visible');
        });

        it('the board has no axe violations', () => {
          cy.expectNoAxeViolations();
        });

        it('the task panel has no axe violations', () => {
          cy.contains('.task', 'Accessibility card').click();
          cy.get('[role="dialog"]').should('be.visible');
          cy.expectNoAxeViolations();
          cy.get('body').type('{esc}');
          cy.get('[role="dialog"]').should('not.exist');
        });
      });
    });
  });
});
