export const SECONDS = {
  'cypress/e2e/accessibility/locales.cy.js': 20,
  'cypress/e2e/accessibility/pages.cy.js': 19,
  'cypress/e2e/accessibility/states.cy.js': 68,
  'cypress/e2e/auth/login.cy.js': 4,
  'cypress/e2e/auth/logout.cy.js': 4,
  'cypress/e2e/auth/messaging-keys.cy.js': 10,
  'cypress/e2e/board/columns.cy.js': 36,
  'cypress/e2e/board/live-sync.cy.js': 8,
  'cypress/e2e/board/rows.cy.js': 26,
  'cypress/e2e/boards/sharing.cy.js': 7,
  'cypress/e2e/journeys/complete-workflow.cy.js': 27,
  'cypress/e2e/messages/board-channel.cy.js': 12,
  'cypress/e2e/messages/direct-messages.cy.js': 11,
  'cypress/e2e/security/csp.cy.js': 4,
  'cypress/e2e/tasks/attachments.cy.js': 4,
  'cypress/e2e/tasks/keyboard-movement.cy.js': 25,
  'cypress/e2e/tasks/stale-edit.cy.js': 7,
  'cypress/e2e/tasks/subtasks.cy.js': 22,
  'cypress/e2e/tasks/task-creation.cy.js': 19,
  'cypress/e2e/tasks/task-movement.cy.js': 23,
  'cypress/e2e/users/user-assignment.cy.js': 11,
};

export const UNMEASURED_SPEC_SECONDS = 20;

export const CYPRESS_LAUNCH_SECONDS = 25;

export const EXTRA_JOBS = {
  replicas: 20,
  load: 66,
};

const cost = (shard, item) =>
  item.seconds + (item.spec && shard.specs.length === 0 ? CYPRESS_LAUNCH_SECONDS : 0);

export function planShards(specs, total) {
  const items = specs
    .map(spec => ({ spec, seconds: SECONDS[spec] ?? UNMEASURED_SPEC_SECONDS }))
    .sort((a, b) => b.seconds - a.seconds || a.spec.localeCompare(b.spec));

  const shards = Array.from({ length: total }, (_, index) => ({
    full: index === 0,
    seconds: index === 0 ? Object.values(EXTRA_JOBS).reduce((sum, seconds) => sum + seconds, 0) : 0,
    specs: [],
    jobs: index === 0 ? Object.keys(EXTRA_JOBS) : [],
  }));
  for (const item of items) {
    const lightest = shards.reduce((min, shard) =>
      (shard.seconds + cost(shard, item) < min.seconds + cost(min, item) ? shard : min));
    lightest.seconds += cost(lightest, item);
    lightest.specs.push(item.spec);
  }
  for (const shard of shards) shard.specs.sort();
  return shards;
}
