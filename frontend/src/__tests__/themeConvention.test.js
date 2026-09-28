import fs from 'fs';
import path from 'path';
import { THEME_CHANGE_EVENT, THEME_STORAGE_KEY, writeThemePreference } from '../theme/themePreference';

const FRONTEND = path.resolve(__dirname, '..', '..');
const STYLES = path.resolve(__dirname, '..', 'styles');
const INIT_SCRIPT = path.join(FRONTEND, 'public', 'theme-init.js');

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
  const offenders = (pattern) => sheets.filter(({ css }) => pattern.test(css)).map(({ name }) => name);

  test('the scan finds the stylesheets', () => {
    expect(sheets.length).toBeGreaterThan(10);
  });

  test('no stylesheet keys dark mode on a class nothing sets', () => {
    expect(offenders(/html\.(dark|light)\b|:not\(\.(light|dark)\)/)).toEqual([]);
  });

  test('no stylesheet reads the system setting itself, so the switch reaches every screen', () => {
    expect(offenders(/prefers-color-scheme/)).toEqual([]);
    expect(offenders(/data-theme="light"/)).toEqual([]);
  });

  test('dark rules keep the specificity the media query gave them', () => {
    expect(offenders(/(?<!:where\()\[data-theme="dark"\]/)).toEqual([]);
  });
});

const COLOUR_LITERAL = /#[0-9a-f]{3,8}\b|\b(?:rgb|hsl)a?\((?![^)]*var\()[^)]*\)|(?<![\w-])(?:white|black)\b/i;

function declarations(css) {
  const uncommented = css.replace(/\/\*[\s\S]*?\*\//g, '');
  return [...uncommented.matchAll(/\{([^{}]*)\}/g)].flatMap(([, block]) =>
    block.split(';').map((line) => line.trim()).filter((line) => line.includes(':')).map((line) => {
      const colon = line.indexOf(':');
      return { property: line.slice(0, colon).trim(), value: line.slice(colon + 1).trim() };
    }));
}

describe('every stylesheet takes its colours from the --kb-* tokens', () => {
  const sheets = stylesheets()
    .map((file) => ({ name: path.relative(STYLES, file).replace(/\\/g, '/'), css: fs.readFileSync(file, 'utf8') }));
  const offending = (matches, within = sheets) => within.flatMap(({ name, css }) => declarations(css)
    .filter(({ property, value }) => matches(property, value))
    .map(({ property, value }) => `${name}: ${property}: ${value}`));

  test('the scan finds every stylesheet, the tokens and index.css among them', () => {
    expect(sheets.map(({ name }) => name)).toEqual(expect.arrayContaining(
      ['index.css', 'components/BoardTokens.css', 'components/Board.css', 'components/TaskDetails.css']));
  });

  test('none writes a colour of its own, which the theme switch could not reach', () => {
    expect(offending((property, value) => !property.startsWith('--') && COLOUR_LITERAL.test(value))).toEqual([]);
  });

  test('index.css defines no second palette beside the tokens', () => {
    const index = sheets.filter(({ name }) => name === 'index.css');
    expect(offending((property, value) => property.startsWith('--') && COLOUR_LITERAL.test(value), index))
      .toEqual([]);
  });
});

describe('the pre-paint theme script', () => {
  const source = fs.readFileSync(INIT_SCRIPT, 'utf8');
  let systemDark;
  let systemListeners;

  const run = () => {
    // eslint-disable-next-line no-new-func
    new Function(source)();
  };

  beforeEach(() => {
    systemDark = false;
    systemListeners = [];
    window.localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
    window.matchMedia = jest.fn(() => ({
      get matches() { return systemDark; },
      addEventListener: (_, listener) => systemListeners.push(listener),
    }));
  });

  test('is loaded as a classic script in the head, before the bundle', () => {
    const html = fs.readFileSync(path.join(FRONTEND, 'index.html'), 'utf8');
    const head = html.slice(0, html.indexOf('</head>'));
    expect(head).toMatch(/<script src="\/theme-init\.js"><\/script>/);
  });

  test('agrees with the switch on the storage key and the change event', () => {
    expect(source).toContain(`'${THEME_STORAGE_KEY}'`);
    expect(source).toContain(`'${THEME_CHANGE_EVENT}'`);
  });

  test('follows the system when nothing is chosen, including a later change', () => {
    systemDark = true;
    run();
    expect(document.documentElement.dataset.theme).toBe('dark');

    systemDark = false;
    systemListeners.forEach((listener) => listener());
    expect(document.documentElement.dataset.theme).toBe('light');
  });

  test('a stored choice wins over the system, and the switch applies a new one at once', () => {
    systemDark = true;
    window.localStorage.setItem(THEME_STORAGE_KEY, 'light');
    run();
    expect(document.documentElement.dataset.theme).toBe('light');

    writeThemePreference('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');

    writeThemePreference('system');
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
    expect(document.documentElement.dataset.theme).toBe('dark');
  });
});
