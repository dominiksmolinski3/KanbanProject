const COLUMN_NAME = 'Attachment Column';
const ROW_NAME = 'Attachment Row';
const TASK_TITLE = `Task with a file ${Date.now()}`;
const FILE_NAME = 'release-notes.txt';
const FILE_BODY = 'Shipped: an attachment survives the round trip.\n';

describe('a file attached to a task', () => {
  let ownerToken;
  let boardId;
  let columnId;
  let rowId;
  let taskId;

  const as = (options) =>
    cy.request({ ...options, headers: { Authorization: `Bearer ${ownerToken}`, ...(options.headers || {}) } });

  before(() => {
    cy.loginAsTestUser();
    cy.window().then((win) => {
      ownerToken = win.localStorage.getItem('token');
      expect(ownerToken, 'the browser is signed in as the board owner').to.be.a('string');
    });

    cy.then(() => as({ url: '/api/boards/current' }).then((response) => {
      boardId = response.body.id;
    }));
    cy.then(() => as({ method: 'POST', url: `/api/columns?boardId=${boardId}`, body: { name: COLUMN_NAME } })
      .then((response) => {
        columnId = response.body.id;
      }));
    cy.then(() => as({ method: 'POST', url: `/api/rows?boardId=${boardId}`, body: { name: ROW_NAME } })
      .then((response) => {
        rowId = response.body.id;
      }));
    cy.then(() => as({
      method: 'POST',
      url: `/api/tasks?boardId=${boardId}`,
      body: { title: TASK_TITLE, column: { id: columnId }, row: { id: rowId } },
    }).then((response) => {
      taskId = response.body.id;
    }));
  });

  after(() => {
    cy.then(() => as({ method: 'DELETE', url: `/api/columns/${columnId}`, failOnStatusCode: false }));
    cy.then(() => as({ method: 'DELETE', url: `/api/rows/${rowId}`, failOnStatusCode: false }));
  });

  it('is uploaded from the task panel, reads back byte for byte, and is deleted from it', () => {
    cy.loginAsTestUser();
    cy.contains('.task', TASK_TITLE).click();

    cy.get('[data-testid="attachment-input"]').selectFile(
      { contents: Cypress.Buffer.from(FILE_BODY), fileName: FILE_NAME, mimeType: 'text/plain' },
      { force: true }
    );
    cy.contains('.attachment-item', FILE_NAME).should('be.visible');

    cy.then(() => as({ url: `/api/tasks/${taskId}/attachments` }))
      .then((response) => {
        expect(response.body).to.have.length(1);
        return as({ url: `/api/tasks/${taskId}/attachments/${response.body[0].id}/content` });
      })
      .then((response) => {
        expect(response.status).to.eq(200);
        expect(response.body).to.eq(FILE_BODY);
      });

    cy.contains('.attachment-item', FILE_NAME).find('.delete-attachment-btn').click();
    cy.get('.confirmation-actions .confirm-btn').click();
    cy.contains('.attachment-item', FILE_NAME).should('not.exist');
    cy.get('.no-attachments').should('be.visible');
    cy.then(() => as({ url: `/api/tasks/${taskId}/attachments` })).its('body').should('have.length', 0);
  });
});
