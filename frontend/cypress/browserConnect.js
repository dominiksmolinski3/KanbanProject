import { spawn } from 'node:child_process';

export const BROWSER_NEVER_CONNECTED = 'Timed out waiting for the browser to connect';

export function run(command, args) {
  return new Promise((resolve) => {
    let output = '';
    const child = spawn(command, args, { shell: process.platform === 'win32' });
    child.stdout.on('data', (chunk) => {
      process.stdout.write(chunk);
      output += chunk;
    });
    child.stderr.on('data', (chunk) => {
      process.stderr.write(chunk);
      output += chunk;
    });
    child.on('close', (code) => resolve({ code: code ?? 1, output }));
  });
}

export async function runRetryingConnect(command, args, runner = run, log = console.log) {
  const first = await runner(command, args);
  if (first.code === 0 || !first.output.includes(BROWSER_NEVER_CONNECTED)) {
    return first.code;
  }
  log(`::warning title=Cypress browser never connected::retrying once - ${command} ${args.join(' ')}`);
  return (await runner(command, args)).code;
}
