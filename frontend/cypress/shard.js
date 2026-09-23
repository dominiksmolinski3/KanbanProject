// Prints the specs one CI shard runs, comma-separated, for `cypress run --spec`.
// usage: node cypress/shard.js <shard, 1-based> <total shards>
//
// The list is derived, never written down. It is the default specPattern from cypress.config.js,
// globbed, sorted and dealt round-robin - so every spec lands in exactly one shard by
// construction, and a spec added tomorrow is run by somebody without a second file to remember.
// A hand-kept list per shard would be the drift every guard in this repository exists to catch,
// and its failure mode is a spec that quietly never runs, which reads as a green suite.
//
// Round-robin over sorted paths balances well enough while the specs are of similar length; it is
// not a scheduler, and a spec that grows to dominate its shard is what a future rebalance would
// look at.
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

// An empty --spec is not "no specs" to Cypress, it is no filter at all - the shard would run the
// whole suite and report it as its own share. Fail instead; more shards than specs is a matrix
// that needs shrinking.
if (mine.length === 0) {
  console.error(`shard ${shard} of ${total} has no specs (${specs.length} in total)`);
  process.exit(1);
}

console.log(mine.join(','));
