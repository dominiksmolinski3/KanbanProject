import fs from 'fs';
import path from 'path';

const STYLES = path.resolve(__dirname, '..', 'styles');

function stylesheets(dir = STYLES) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return stylesheets(full);
    return entry.name.endsWith('.css') ? [full] : [];
  });
}

describe('one dark-mode convention', () => {
  const sheets = stylesheets().map((file) => ({
    name: path.relative(STYLES, file),
    css: fs.readFileSync(file, 'utf8'),
  }));

  test('the scan finds the stylesheets', () => {
    expect(sheets.length).toBeGreaterThan(10);
  });

  test('no stylesheet keys dark mode on a class nothing sets', () => {
    const offenders = sheets
      .filter(({ css }) => /html\.(dark|light)\b|:not\(\.(light|dark)\)/.test(css))
      .map(({ name }) => name);

    expect(offenders).toEqual([]);
  });
});
