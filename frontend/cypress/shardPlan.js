export const SECONDS = {
  'cypress/e2e/auth/login.cy.js': 4,
  'cypress/e2e/auth/logout.cy.js': 4,
  'cypress/e2e/board/columns.cy.js': 38,
  'cypress/e2e/board/live-sync.cy.js': 14,
  'cypress/e2e/board/rows.cy.js': 35,
  'cypress/e2e/journeys/complete-workflow.cy.js': 29,
  'cypress/e2e/security/csp.cy.js': 6,
  'cypress/e2e/tasks/keyboard-movement.cy.js': 16,
  'cypress/e2e/tasks/stale-edit.cy.js': 7,
  'cypress/e2e/tasks/subtasks.cy.js': 28,
  'cypress/e2e/tasks/task-creation.cy.js': 20,
  'cypress/e2e/tasks/task-movement.cy.js': 24,
  'cypress/e2e/users/user-assignment.cy.js': 20,
};

export const UNMEASURED_SPEC_SECONDS = 20;

export const CYPRESS_LAUNCH_SECONDS = 25;

export const EXTRA_JOBS = {
  replicas: 28,
  load: 67,
};

const cost = (shard, item) =>
  item.seconds + (item.spec && shard.specs.length === 0 ? CYPRESS_LAUNCH_SECONDS : 0);

export function planShards(specs, total) {
  const items = [
    ...specs.map(spec => ({ spec, seconds: SECONDS[spec] ?? UNMEASURED_SPEC_SECONDS })),
    ...Object.entries(EXTRA_JOBS).map(([job, seconds]) => ({ job, seconds })),
  ].sort((a, b) => b.seconds - a.seconds || (a.spec ?? a.job).localeCompare(b.spec ?? b.job));

  const shards = Array.from({ length: total }, () => ({ seconds: 0, specs: [], jobs: [] }));
  for (const item of items) {
    const lightest = shards.reduce((min, shard) =>
      (shard.seconds + cost(shard, item) < min.seconds + cost(min, item) ? shard : min));
    lightest.seconds += cost(lightest, item);
    if (item.spec) lightest.specs.push(item.spec);
    else lightest.jobs.push(item.job);
  }
  for (const shard of shards) shard.specs.sort();
  return shards;
}
