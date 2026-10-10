import { globSync } from 'node:fs';
import config from '../cypress.config.js';
import { EXTRA_JOBS, planShards } from './shardPlan.js';

const [shard, total] = process.argv.slice(2).map(Number);
if (!Number.isInteger(shard) || !Number.isInteger(total) || shard < 1 || shard > total) {
  console.error(`usage: node cypress/shard.js <shard 1..total> <total>, got "${process.argv.slice(2).join(' ')}"`);
  process.exit(1);
}

const specs = globSync(config.e2e.specPattern)
  .map(path => path.replaceAll('\\', '/'))
  .sort();

const mine = planShards(specs, total)[shard - 1];

console.error(`shard ${shard} of ${total} (~${mine.seconds}s): ${[...mine.specs, ...mine.jobs].join(', ')}`);
// An empty --spec means every spec to Cypress, so the workflow skips the run when this is empty.
console.log(`specs=${mine.specs.join(',')}`);
for (const job of Object.keys(EXTRA_JOBS)) console.log(`${job}=${mine.jobs.includes(job)}`);
