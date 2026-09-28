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

function unscopedSelectors(css) {
  const rules = css
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/@keyframes[^{]*\{(?:[^{}]*\{[^{}]*\})*[^{}]*\}/g, '');
  return [...rules.matchAll(/([^{}]+)\{([^{}]*)\}/g)].flatMap(([, selector, body]) => {
    const onlyCustomProperties = body.split(';').map((d) => d.trim()).filter(Boolean)
      .every((d) => d.startsWith('--') || d.startsWith('color-scheme'));
    return selector.split(',').map((part) => part.trim().replace(/^@media[^{]*$/, ''))
      .filter((part) => part && !part.startsWith('@') && !part.includes('.'))
      .filter((part) => !(part.startsWith(':root') && onlyCustomProperties));
  });
}

describe('a screen\'s stylesheet styles only its own classes', () => {
  const sheets = stylesheets().map((file) => ({
    name: path.relative(STYLES, file).replace(/\\/g, '/'),
    css: fs.readFileSync(file, 'utf8'),
  }));

  test('the check sees a bare element selector when there is one', () => {
    expect(unscopedSelectors('.card { color: red; }\nbutton[type="submit"] { width: 100%; }'))
      .toEqual(['button[type="submit"]']);
    expect(unscopedSelectors(':root { --kb-text: red; }\n.card label { color: red; }')).toEqual([]);
  });

  test('only index.css styles bare elements, and only App.css the root node', () => {
    const offenders = sheets
      .filter(({ name }) => name !== 'index.css')
      .flatMap(({ name, css }) => unscopedSelectors(css)
        .filter((selector) => !(name === 'App.css' && selector === '#root'))
        .map((selector) => `${name}: ${selector}`));
    expect(offenders).toEqual([]);
  });
});
