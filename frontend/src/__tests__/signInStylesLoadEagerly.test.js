import fs from 'fs';
import path from 'path';

/*
 * The sign-in screen is the one screen that loads before any lazy route (FE-01), so a class it uses
 * that is styled only in a lazily-loaded stylesheet renders unstyled there - and only there, since
 * every other screen has pulled that stylesheet in by the time it draws. That is how the language
 * switcher fell out of its corner: `.language-switcher-container` sat in Board.css, which the
 * sign-in screen stopped loading when the routes were split. Jest maps every stylesheet to a proxy,
 * so no component test can see this; it is read out of the source instead.
 */

const SRC = path.resolve(__dirname, '..');
const SIGN_IN_COMPONENTS = ['components/HomePage.jsx', 'components/LanguageSwitcher.jsx', 'components/DemoBanner.jsx'];

/** Stylesheets reachable from main.jsx through static imports only; `import()` is a lazy chunk. */
function eagerStylesheets() {
  const seen = new Set();
  const css = new Set();
  const walk = (file) => {
    if (seen.has(file) || !fs.existsSync(file)) return;
    seen.add(file);
    const source = fs.readFileSync(file, 'utf8');
    for (const match of source.matchAll(/^import\s+(?:[^'"]*?from\s+)?['"]([^'"]+)['"]/gm)) {
      const spec = match[1];
      if (!spec.startsWith('.')) continue;
      const target = path.resolve(path.dirname(file), spec);
      if (spec.endsWith('.css')) {
        css.add(target);
        continue;
      }
      const resolved = ['', '.jsx', '.js'].map(ext => target + ext)
        .find(candidate => fs.existsSync(candidate) && fs.statSync(candidate).isFile());
      if (resolved) walk(resolved);
    }
  };
  walk(path.join(SRC, 'main.jsx'));
  return css;
}

function allStylesheets(dir = SRC) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === '__tests__' ? [] : allStylesheets(full);
    return entry.name.endsWith('.css') ? [full] : [];
  });
}

function classesUsedBy(file) {
  const source = fs.readFileSync(path.join(SRC, file), 'utf8');
  const classes = new Set();
  for (const match of source.matchAll(/className=\{?["'`]([^"'`]+)["'`]/g)) {
    match[1].split(/\s+/).filter(name => name && !name.includes('$')).forEach(name => classes.add(name));
  }
  return classes;
}

const definesClass = (stylesheet, name) =>
  new RegExp(`\\.${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![\\w-])`).test(fs.readFileSync(stylesheet, 'utf8'));

describe('the sign-in screen', () => {
  const eager = eagerStylesheets();
  const stylesheets = allStylesheets();

  test('styles every class it uses from a stylesheet it actually loads', () => {
    const stranded = [];
    for (const component of SIGN_IN_COMPONENTS) {
      for (const name of classesUsedBy(component)) {
        const defining = stylesheets.filter(sheet => definesClass(sheet, name));
        if (defining.length > 0 && !defining.some(sheet => eager.has(sheet))) {
          stranded.push(`${name} (${component}) is styled only in ${defining.map(s => path.basename(s)).join(', ')}`);
        }
      }
    }
    expect(stranded).toEqual([]);
  });

  test('the reader still sees the split it exists for', () => {
    // A walk that followed lazy imports too would find Board.css eager and pass everything above.
    const names = [...eager].map(sheet => path.basename(sheet));
    expect(names).toEqual(expect.arrayContaining(['HomePage.css', 'LanguageSwitcher.css']));
    expect(names).not.toContain('Board.css');
  });
});
