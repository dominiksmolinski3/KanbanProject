import { runRetryingConnect, BROWSER_NEVER_CONNECTED } from '../../cypress/browserConnect.js';

const scripted = (...results) => {
  const runner = jest.fn();
  results.forEach((result) => runner.mockResolvedValueOnce(result));
  return runner;
};

describe('retrying a Cypress run whose browser never connected', () => {
  test('a run that passes is not repeated', async () => {
    const runner = scripted({ code: 0, output: '' });

    await expect(runRetryingConnect('npm', ['run', 'cypress:run'], runner, jest.fn())).resolves.toBe(0);
    expect(runner).toHaveBeenCalledTimes(1);
  });

  test('a failing test is not retried, so a real red stays red', async () => {
    const runner = scripted({ code: 1, output: '1 failing\nAssertionError: expected true' });

    await expect(runRetryingConnect('npm', ['run', 'cypress:run'], runner, jest.fn())).resolves.toBe(1);
    expect(runner).toHaveBeenCalledTimes(1);
  });

  test('a browser that never connected is retried once, with a warning in the job summary', async () => {
    const runner = scripted(
      { code: 1, output: `${BROWSER_NEVER_CONNECTED}. Retrying...\nTypeError: Cannot read properties of undefined (reading 'getWebSocketDebuggerUrl')` },
      { code: 0, output: 'All specs passed!' },
    );
    const log = jest.fn();

    await expect(runRetryingConnect('npm', ['run', 'cypress:run'], runner, log)).resolves.toBe(0);
    expect(runner).toHaveBeenCalledTimes(2);
    expect(log).toHaveBeenCalledWith(expect.stringMatching(/^::warning /));
  });

  test('only once: a second crash fails the step', async () => {
    const crash = { code: 1, output: BROWSER_NEVER_CONNECTED };
    const runner = scripted(crash, crash);

    await expect(runRetryingConnect('npm', ['run', 'cypress:run'], runner, jest.fn())).resolves.toBe(1);
    expect(runner).toHaveBeenCalledTimes(2);
  });
});
