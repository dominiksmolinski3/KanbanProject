import { readFileSync } from 'fs';
import { join } from 'path';

/**
 * A guard over the one dependency rule this project has already been broken by.
 *
 * `react-dom` compares its own version against `react`'s at import time and refuses to load when
 * they differ. That is not a warning and not a failing assertion - the module throws before any
 * test body runs, so every suite that renders anything fails to start at once. It happened: an
 * auto-merged Dependabot PR took `react` to 19.2.8 and left `react-dom` at 19.0.0, fifteen suites
 * stopped running on `main`, and it was found an hour later as a red check on somebody else's pull
 * request rather than by anything watching.
 *
 * Pinning the two to the same version was the fix at the time and is a fact about one moment. This
 * is the rule: whatever they are, they are the same. It reads `package.json` rather than the
 * installed tree deliberately - the declared range is what Dependabot edits and what a reviewer
 * looks at, and it is wrong on the branch that proposes it, which is where this is meant to fail.
 *
 * Only this pair, and only because `react-dom` genuinely enforces it. `@types/react` and
 * `@types/react-dom` are published on separate trains and are not required to match; asserting
 * that they do would be a rule nobody agreed to, failing on a bump that is fine.
 */
describe('dependencies that have to move together', () => {
  const manifest = JSON.parse(
    readFileSync(join(process.cwd(), 'package.json'), 'utf8'),
  );

  const declared = (name) =>
    manifest.dependencies?.[name] ?? manifest.devDependencies?.[name];

  it('declares react and react-dom at the same version', () => {
    expect(declared('react')).toBeDefined();
    expect(declared('react-dom')).toBeDefined();
    expect(declared('react-dom')).toBe(declared('react'));
  });

  it('does not carry a second test runner', () => {
    // vitest sat in devDependencies with no config, no npm script and nothing importing it, while
    // Jest ran every test. Dependabot carried it through a major version and CI proved nothing
    // about it, because nothing ran it. A dead dependency is not only noise: it is one more
    // package that can be bundled into a pull request with a live one, which is exactly the shape
    // of the bump that broke the trunk.
    expect(declared('vitest')).toBeUndefined();
  });
});
