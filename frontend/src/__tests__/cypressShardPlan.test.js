/**
 * @jest-environment node
 */
import { globSync } from 'node:fs';
import { EXTRA_JOBS, SECONDS, planShards } from '../../cypress/shardPlan.js';

const specs = globSync('cypress/e2e/**/*.cy.{js,jsx,ts,tsx}')
  .map(path => path.replaceAll('\\', '/'))
  .sort();

describe('dealing the e2e suite out to the CI shards', () => {
  test('every timed spec still exists, so the table cannot drift from the suite', () => {
    expect(specs.length).toBeGreaterThan(0);
    expect(Object.keys(SECONDS).filter(spec => !specs.includes(spec))).toEqual([]);
  });

  test.each([1, 2, 3, 4])('with %i shards every spec and job lands in exactly one shard', total => {
    const shards = planShards(specs, total);

    expect(shards.flatMap(shard => shard.specs).sort()).toEqual(specs);
    expect(shards.flatMap(shard => shard.jobs).sort()).toEqual(Object.keys(EXTRA_JOBS).sort());
  });

  test.each([1, 2, 3, 4, 5])('with %i shards only the first runs the full stack and its extra jobs', total => {
    const shards = planShards(specs, total);

    expect(shards.map(shard => shard.full)).toEqual(shards.map((_, index) => index === 0));
    expect(shards[0].jobs.sort()).toEqual(Object.keys(EXTRA_JOBS).sort());
    expect(shards.slice(1).flatMap(shard => shard.jobs)).toEqual([]);
  });

  test('a spec with no timing is still dealt out', () => {
    const shards = planShards(['cypress/e2e/new/brand-new.cy.js'], 2);

    expect(shards.flatMap(shard => shard.specs)).toEqual(['cypress/e2e/new/brand-new.cy.js']);
  });

  test('the three shards CI runs finish within half a minute of each other', () => {
    const seconds = planShards(specs, 3).map(shard => shard.seconds);

    expect(Math.max(...seconds) - Math.min(...seconds)).toBeLessThanOrEqual(30);
  });

  test('the plan is the same on every run', () => {
    expect(planShards(specs, 3)).toEqual(planShards([...specs].reverse(), 3));
  });
});
