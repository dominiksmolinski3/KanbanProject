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

describe('nothing on screen bypasses t()', () => {
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
    expect(sourceFiles(SOURCE_DIR).filter((file) => file.endsWith('.jsx')).length)
      .toBeGreaterThan(20);
  });
});
