import { runRetryingConnect } from './browserConnect.js';

const [command, ...args] = process.argv.slice(2);
if (!command) {
  console.error('usage: node cypress/retry-browser-connect.js <command> [args...]');
  process.exit(2);
}
process.exit(await runRetryingConnect(command, args));
