import fs from 'fs';
import path from 'path';

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
