import fs from 'fs';
import path from 'path';
import * as parser from '@babel/parser';

const LOCALES_DIR = path.join(process.cwd(), 'public', 'locales');
const SOURCE_DIR = path.join(process.cwd(), 'src');

const languages = fs.readdirSync(LOCALES_DIR).filter((name) =>
  fs.statSync(path.join(LOCALES_DIR, name)).isDirectory()
);

function readLocale(language) {
  return JSON.parse(
    fs.readFileSync(path.join(LOCALES_DIR, language, 'translation.json'), 'utf8')
  );
}

function flatten(value, prefix = '') {
  return Object.entries(value).flatMap(([key, entry]) => {
    const name = prefix ? `${prefix}.${key}` : key;
    return entry !== null && typeof entry === 'object' ? flatten(entry, name) : [name];
  });
}

function sourceFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === '__tests__' ? [] : sourceFiles(full);
    return /\.jsx?$/.test(entry.name) ? [full] : [];
  });
}

describe('locale files', () => {
  const english = flatten(readLocale('en')).sort();

  test('ships more than one language', () => {
    expect(languages.length).toBeGreaterThan(1);
    expect(languages).toContain('en');
  });

  test.each(languages.filter((language) => language !== 'en'))(
    '%s carries exactly the keys en does',
    (language) => {
      expect(flatten(readLocale(language)).sort()).toEqual(english);
    }
  );
});

describe('user-facing strings', () => {
  /**
   * A toast argument is fine when every word in it comes from an interpolation — `${t('key')}`
   * or `${error.message}`. What is not fine is prose sitting in the source, which no locale file
   * can translate. Strip the interpolations and fail on whatever letters are left.
   */
  const TOAST_LITERAL = /toast\.\w+\(\s*(['"`])((?:\\.|(?!\1)[\s\S])*)\1/g;

  test('no toast is raised with a hardcoded string', () => {
    const offenders = [];

    for (const file of sourceFiles(SOURCE_DIR)) {
      const source = fs.readFileSync(file, 'utf8');
      for (const match of source.matchAll(TOAST_LITERAL)) {
        const literal = match[2].replace(/\$\{[^}]*\}/g, '');
        if (/\p{L}/u.test(literal)) {
          offenders.push(`${path.relative(process.cwd(), file)}: ${match[0]}`);
        }
      }
    }

    expect(offenders).toEqual([]);
  });
});

/**
 * The half the toast check could not see.
 *
 * Nine locales with 391 identical keys, parity asserted and holding — and three Polish strings in
 * a card popover that every non-Polish reader saw, an English literal on ten screens in the task
 * panel, and a `title="row.delete"` rendering the translation key itself. All of them lived where
 * the assertion above never looked: in JSX text nodes and in `title` / `aria-label` / `placeholder`
 * / `alt` attributes.
 *
 * <p>This reads the JSX with Babel's own parser rather than with a regex, because the shapes that
 * matter cannot be told apart by one: `{isOpen ? 'Hide' : 'Show'}` is prose and
 * `className={isOpen ? 'open' : ''}` is not, and both are a string literal in a conditional. The
 * parser makes the distinction the obvious one — an expression that is a child of an element is on
 * screen, and an expression that is an attribute value mostly is not.
 *
 * A repository that catches a reworded log line with `DeadLetterAlertTest` should not be blind to
 * Polish prose in a default-English screen.
 */
describe('nothing on screen bypasses t()', () => {
  /** Attributes a person reads or hears. `className`, `type` and the rest are not prose. */
  const SPOKEN_ATTRIBUTES = new Set([
    'title', 'aria-label', 'aria-description', 'placeholder', 'alt',
  ]);

  const hasLetters = (value) => /\p{L}/u.test(value);

  function walk(node, visit, parent) {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) {
      node.forEach((child) => walk(child, visit, parent));
      return;
    }
    const isNode = typeof node.type === 'string';
    if (isNode) visit(node, parent);
    for (const key of Object.keys(node)) {
      if (key === 'loc' || key === 'leadingComments' || key === 'trailingComments') continue;
      walk(node[key], visit, isNode ? node : parent);
    }
  }

  /**
   * The strings an expression can put on screen. A conditional shows either branch; `a || b` shows
   * `b` when `a` is falsy, which is how a dead `t('key') || 'fallback'` hides a missing key. A
   * template literal is prose only in the parts outside its interpolations, so `${a} - ${b}` is
   * not one and `Page ${n}` is.
   */
  function displayed(expression, found) {
    if (!expression) return;
    if (expression.type === 'StringLiteral') {
      found.push({ value: expression.value, line: expression.loc.start.line });
      return;
    }
    if (expression.type === 'ConditionalExpression') {
      displayed(expression.consequent, found);
      displayed(expression.alternate, found);
      return;
    }
    if (expression.type === 'LogicalExpression') {
      displayed(expression.right, found);
      return;
    }
    if (expression.type === 'TemplateLiteral') {
      const prose = expression.quasis.map((quasi) => quasi.value.cooked).join('');
      if (prose.trim()) found.push({ value: prose, line: expression.loc.start.line });
    }
  }

  const scan = () => {
    const offenders = [];

    for (const file of sourceFiles(SOURCE_DIR)) {
      const relative = path.relative(process.cwd(), file).replace(/\\/g, '/');
      const ast = parser.parse(fs.readFileSync(file, 'utf8'), {
        sourceType: 'module',
        plugins: ['jsx'],
      });

      walk(ast.program, (node, parent) => {
        if (node.type === 'JSXText' && hasLetters(node.value.trim())) {
          offenders.push(`${relative}:${node.loc.start.line}  ${JSON.stringify(node.value.trim())}`);
        }

        if (node.type === 'JSXAttribute' && node.value && node.value.type === 'StringLiteral') {
          const name = node.name.type === 'JSXNamespacedName'
            ? `${node.name.namespace.name}:${node.name.name.name}`
            : node.name.name;
          if (SPOKEN_ATTRIBUTES.has(name) && hasLetters(node.value.value)) {
            offenders.push(`${relative}:${node.loc.start.line}  ${name}=${JSON.stringify(node.value.value)}`);
          }
        }

        // A child expression is on screen; an attribute value is the parser's own distinction.
        if (node.type === 'JSXExpressionContainer' && parent && parent.type !== 'JSXAttribute') {
          const found = [];
          displayed(node.expression, found);
          for (const literal of found) {
            if (hasLetters(literal.value)) {
              offenders.push(`${relative}:${literal.line}  ${JSON.stringify(literal.value)}`);
            }
          }
        }
      });
    }

    return offenders.sort();
  };

  test('no JSX text node or spoken attribute carries prose', () => {
    expect(scan()).toEqual([]);
  });

  test('the scan reads the components - a silent zero would pass the assertion above', () => {
    // If the parse ever stops finding JSX, the check above passes by seeing nothing at all.
    expect(sourceFiles(SOURCE_DIR).filter((file) => file.endsWith('.jsx')).length)
      .toBeGreaterThan(20);
  });
});
