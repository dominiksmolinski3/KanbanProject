import { readFileSync } from 'fs';
import { join } from 'path';

/**
 * A guard over the one dependency rule this project has already been broken by: `react-dom`
 * checks its version against `react`'s at import and refuses to load when they differ, which
 * failed every rendering suite at once when an auto-merged Dependabot PR bumped one and not the
 * other. It reads `package.json` rather than the installed tree, since the declared range is what
 * Dependabot edits and is wrong on the branch that proposes it. Only this pair - `@types/react`
 * and `@types/react-dom` are on separate release trains and are not required to match.
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
    // vitest sat in devDependencies unused - no config, no script, nothing importing it - while
    // Jest ran every test, so CI proved nothing about the major-version bumps Dependabot carried
    // it through. A dead dependency can also be bundled into a PR alongside a live one, which is
    // exactly the shape of the bump that broke the trunk.
    expect(declared('vitest')).toBeUndefined();
  });
});
