export const SECONDS = {
  'cypress/e2e/auth/login.cy.js': 4,
  'cypress/e2e/auth/logout.cy.js': 4,
  'cypress/e2e/board/columns.cy.js': 40,
  'cypress/e2e/board/live-sync.cy.js': 16,
  'cypress/e2e/board/rows.cy.js': 35,
  'cypress/e2e/journeys/complete-workflow.cy.js': 30,
  'cypress/e2e/security/csp.cy.js': 7,
  'cypress/e2e/tasks/keyboard-movement.cy.js': 17,
  'cypress/e2e/tasks/stale-edit.cy.js': 8,
  'cypress/e2e/tasks/subtasks.cy.js': 33,
  'cypress/e2e/tasks/task-creation.cy.js': 21,
  'cypress/e2e/tasks/task-movement.cy.js': 24,
  'cypress/e2e/users/user-assignment.cy.js': 16,
};

export const UNMEASURED_SPEC_SECONDS = 20;

export const EXTRA_JOBS = {
  replicas: 30,
  load: 65,
};

export function planShards(specs, total) {
  const items = [
    ...specs.map(spec => ({ spec, seconds: SECONDS[spec] ?? UNMEASURED_SPEC_SECONDS })),
    ...Object.entries(EXTRA_JOBS).map(([job, seconds]) => ({ job, seconds })),
  ].sort((a, b) => b.seconds - a.seconds || (a.spec ?? a.job).localeCompare(b.spec ?? b.job));

  const shards = Array.from({ length: total }, () => ({ seconds: 0, specs: [], jobs: [] }));
  for (const item of items) {
    const lightest = shards.reduce((min, shard) => (shard.seconds < min.seconds ? shard : min));
    lightest.seconds += item.seconds;
    if (item.spec) lightest.specs.push(item.spec);
    else lightest.jobs.push(item.job);
  }
  for (const shard of shards) shard.specs.sort();
  return shards;
}
