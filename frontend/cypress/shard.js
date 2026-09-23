import { globSync } from 'node:fs';
import config from '../cypress.config.js';

const [shard, total] = process.argv.slice(2).map(Number);
if (!Number.isInteger(shard) || !Number.isInteger(total) || shard < 1 || shard > total) {
  console.error(`usage: node cypress/shard.js <shard 1..total> <total>, got "${process.argv.slice(2).join(' ')}"`);
  process.exit(1);
}

const specs = globSync(config.e2e.specPattern)
  .map(path => path.replaceAll('\\', '/'))
  .sort();

const mine = specs.filter((_, i) => i % total === shard - 1);

if (mine.length === 0) {
  console.error(`shard ${shard} of ${total} has no specs (${specs.length} in total)`);
  process.exit(1);
}

console.log(mine.join(','));
