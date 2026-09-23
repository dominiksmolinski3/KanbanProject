import { readFileSync } from 'fs';
import { join } from 'path';

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
    expect(declared('vitest')).toBeUndefined();
  });
});
