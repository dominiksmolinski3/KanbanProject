#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const WINDOWS = process.platform === 'win32';

function run(command, args, options = {}) {
  return execFileSync(command, args, { encoding: 'utf8', shell: WINDOWS, maxBuffer: 64 * 1024 * 1024, ...options });
}

function readText(path) {
  const text = readFileSync(path, 'utf8');
  return { text: text.replace(/\r\n/g, '\n'), eol: text.includes('\r\n') ? '\r\n' : '\n' };
}

function writeText(path, text, eol) {
  writeFileSync(path, eol === '\n' ? text : text.replace(/\n/g, eol));
}

function parseArgs(argv) {
  const args = { trivy: new Map(), apply: false, summary: null };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--apply') args.apply = true;
    else if (argv[i] === '--summary') args.summary = argv[++i];
    else if (argv[i] === '--trivy') {
      const [ignoreFile, result] = argv[++i].split('=');
      args.trivy.set(ignoreFile, result);
    } else throw new Error(`unknown argument: ${argv[i]}`);
  }
  return args;
}


function parseIgnoreFile(text) {
  const lines = text.split('\n');
  const entries = [];
  let entry = null;
  let inPaths = false;
  lines.forEach((line, index) => {
    const item = line.match(/^(\s*)- id:\s*(\S+)\s*$/);
    if (item) {
      entry = { id: item[2], start: index, end: index + 1, indent: item[1].length, paths: [] };
      entries.push(entry);
      inPaths = false;
      return;
    }
    if (!entry) return;
    const indent = line.match(/^\s*/)[0].length;
    if (line.trim() !== '' && indent <= entry.indent) {
      entry = null;
      return;
    }
    if (/^\s+paths:\s*$/.test(line)) inPaths = true;
    else if (inPaths && /^\s+- /.test(line)) entry.paths.push({ value: line.replace(/^\s+- /, '').trim(), line: index });
    else if (/^\s+\w[\w-]*:/.test(line)) inPaths = false;
    if (line.trim() !== '') entry.end = index + 1;
  });
  return { lines, entries };
}

function globToRegExp(glob) {
  const escaped = glob.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*\*/g, '\u0000').replace(/\*/g, '[^/]*');
  return new RegExp(`^${escaped.replace(/\u0000/g, '.*')}$`);
}

function trivyFindings(resultFile) {
  const report = JSON.parse(readFileSync(resultFile, 'utf8'));
  if (!Array.isArray(report.Results)) {
    throw new Error(`${resultFile} holds no Results, so the image was not scanned`);
  }
  return report.Results.flatMap(result => (result.Vulnerabilities ?? []).map(vulnerability => ({
    id: vulnerability.VulnerabilityID,
    paths: [result.Target, vulnerability.PkgPath].filter(Boolean),
  })));
}

function staleIgnores(trivy) {
  const tracked = run('git', ['ls-files', '*.trivyignore.yaml', '.trivyignore.yaml'], { cwd: REPO }).split('\n').filter(Boolean);
  const unscanned = tracked.filter(file => !trivy.has(file));
  if (unscanned.length > 0) {
    throw new Error(`no scan was given for ${unscanned.join(', ')}; add its image to the sweep`);
  }
  const changes = [];
  for (const [file, resultFile] of trivy) {
    const findings = trivyFindings(resultFile);
    const path = join(REPO, file);
    const { text, eol } = readText(path);
    const { lines, entries } = parseIgnoreFile(text);
    const drop = new Set();
    const notes = [];
    for (const entry of entries) {
      const matches = findings.filter(finding => finding.id === entry.id);
      if (entry.paths.length === 0) {
        if (matches.length === 0) {
          for (let i = entry.start; i < entry.end; i++) drop.add(i);
          notes.push(`\`${entry.id}\` no longer matches any finding`);
        }
        continue;
      }
      const stale = entry.paths.filter(p => !matches.some(m => m.paths.some(found => globToRegExp(p.value).test(found))));
      if (stale.length === entry.paths.length) {
        for (let i = entry.start; i < entry.end; i++) drop.add(i);
        notes.push(`\`${entry.id}\` no longer matches any finding`);
      } else {
        for (const p of stale) {
          drop.add(p.line);
          notes.push(`\`${entry.id}\` no longer matches \`${p.value}\``);
        }
      }
    }
    if (notes.length > 0) {
      changes.push({
        file,
        notes,
        apply: () => {
          let kept = lines.filter((_, i) => !drop.has(i)).join('\n');
          if (!/^\s*- id:/m.test(kept)) kept = kept.replace(/^vulnerabilities:\s*$/m, 'vulnerabilities: []');
          writeText(path, kept, eol);
        },
      });
    }
  }
  return changes;
}


function loadSemver() {
  const node = dirname(process.execPath);
  const candidates = [join(node, '..', 'lib', 'node_modules', 'npm'), join(node, 'node_modules', 'npm'), join(run('npm', ['root', '-g']).trim(), 'npm')];
  const npm = candidates.find(dir => existsSync(join(dir, 'node_modules', 'semver')));
  if (!npm) throw new Error(`npm's semver is in none of ${candidates.join(', ')}`);
  return createRequire(join(npm, 'package.json'))('semver');
}

function splitSelector(key) {
  const at = key.indexOf('@', 1);
  return at > 0 ? { name: key.slice(0, at), range: key.slice(at + 1) } : { name: key, range: null };
}

function instances(lock, name) {
  return Object.entries(lock.packages)
    .filter(([path]) => path === `node_modules/${name}` || path.endsWith(`/node_modules/${name}`))
    .map(([path, meta]) => ({ path, version: meta.version }));
}

function resolveChild(lock, from, name) {
  let base = from;
  for (;;) {
    const candidate = `${base ? `${base}/` : ''}node_modules/${name}`;
    if (lock.packages[candidate]) return lock.packages[candidate].version;
    if (!base) return null;
    const cut = base.lastIndexOf('/node_modules/');
    base = cut >= 0 ? base.slice(0, cut) : '';
  }
}

// The committed lock has a peer conflict a fresh resolve refuses; --force accepts it and moves nothing else.
const NPM_LOCK_ONLY = ['install', '--package-lock-only', '--ignore-scripts', '--no-audit', '--no-fund', '--force', '--loglevel=error'];

function resolveWithoutOverrides(frontend, names) {
  const work = mkdtempSync(join(tmpdir(), 'stale-overrides-'));
  try {
    const manifest = JSON.parse(readFileSync(join(frontend, 'package.json'), 'utf8'));
    delete manifest.overrides;
    const lock = JSON.parse(readFileSync(join(frontend, 'package-lock.json'), 'utf8'));
    const cut = Object.keys(lock.packages).filter(path => names.some(name => path === `node_modules/${name}` || path.endsWith(`/node_modules/${name}`)));
    for (const path of Object.keys(lock.packages)) {
      if (cut.some(root => path === root || path.startsWith(`${root}/`))) delete lock.packages[path];
    }
    writeFileSync(join(work, 'package.json'), JSON.stringify(manifest, null, 2));
    writeFileSync(join(work, 'package-lock.json'), JSON.stringify(lock, null, 2));
    if (existsSync(join(frontend, '.npmrc'))) copyFileSync(join(frontend, '.npmrc'), join(work, '.npmrc'));
    run('npm', NPM_LOCK_ONLY, { cwd: work, stdio: ['ignore', 'pipe', 'pipe'] });
    return JSON.parse(readFileSync(join(work, 'package-lock.json'), 'utf8'));
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
}

function staleOverrides() {
  const frontend = join(REPO, 'frontend');
  const manifestPath = join(frontend, 'package.json');
  const { text, eol } = readText(manifestPath);
  const manifest = JSON.parse(text);
  const overrides = manifest.overrides ?? {};
  if (Object.keys(overrides).length === 0) return [];

  const semver = loadSemver();
  const names = Object.entries(overrides).flatMap(([key, value]) =>
    typeof value === 'string' ? [splitSelector(key).name] : Object.keys(value).filter(child => child !== '.'));
  const lock = resolveWithoutOverrides(frontend, names);
  const satisfies = (version, range) => semver.satisfies(version, range, { includePrerelease: true });
  const notes = [];
  const remaining = structuredClone(overrides);

  for (const [key, value] of Object.entries(overrides)) {
    const { name, range } = splitSelector(key);
    if (typeof value === 'string') {
      const found = instances(lock, name);
      const needed = range
        ? found.some(i => satisfies(i.version, range))
        : found.some(i => !satisfies(i.version, value));
      if (!needed) {
        delete remaining[key];
        notes.push(`\`${key}\`: without it, nothing resolves ${range ? `\`${name}\` in \`${range}\`` : `\`${name}\` outside \`${value}\``}`);
      }
      continue;
    }
    const parents = instances(lock, name).filter(i => !range || satisfies(i.version, range));
    for (const [child, target] of Object.entries(value)) {
      if (typeof target !== 'string' || child === '.') {
        throw new Error(`the override ${key} > ${child} has a shape this sweep cannot check`);
      }
      const resolved = parents.map(p => resolveChild(lock, p.path, child)).filter(Boolean);
      if (resolved.every(version => satisfies(version, target))) {
        delete remaining[key][child];
        notes.push(parents.length === 0
          ? `\`${key} > ${child}\`: nothing depends on \`${name}\` any more`
          : resolved.length === 0
            ? `\`${key} > ${child}\`: \`${name}\` no longer depends on \`${child}\``
            : `\`${key} > ${child}\`: without it, \`${name}\` already resolves \`${child}\` within \`${target}\``);
      }
    }
    if (Object.keys(remaining[key]).length === 0) delete remaining[key];
  }
  if (notes.length === 0) return [];
  return [{
    file: 'frontend/package.json',
    notes,
    apply: () => {
      if (Object.keys(remaining).length > 0) manifest.overrides = remaining;
      else delete manifest.overrides;
      // Never regenerate the lock here: npm then drops what the remaining overrides hold in place.
      writeText(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, eol);
    },
  }];
}


function compareVersions(a, b) {
  const parts = v => v.split(/[^0-9]+/).filter(Boolean).map(Number);
  const [x, y] = [parts(a), parts(b)];
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] ?? 0) - (y[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

function managedPins(pom) {
  const section = pom.match(/<dependencyManagement>[\s\S]*?<\/dependencyManagement>/);
  if (!section) return [];
  const properties = Object.fromEntries([...pom.matchAll(/<([\w.-]+)>([^<]*)<\/\1>/g)].map(m => [m[1], m[2]]));
  return [...section[0].matchAll(/[ \t]*<dependency>[\s\S]*?<\/dependency>\n?/g)]
    .map(m => m[0])
    .filter(block => !/<scope>\s*import\s*<\/scope>/.test(block))
    .map(block => {
      const tag = name => block.match(new RegExp(`<${name}>\\s*([^<]+?)\\s*</${name}>`))?.[1];
      const version = tag('version');
      const property = version?.match(/^\$\{(.+)\}$/)?.[1] ?? null;
      return { block, group: tag('groupId'), artifact: tag('artifactId'), property, version: property ? properties[property] : version };
    });
}

function staleMavenPins() {
  const backend = join(REPO, 'backend');
  const pomPath = join(backend, 'pom.xml');
  const { text: pom, eol } = readText(pomPath);
  const pins = managedPins(pom);
  if (pins.length === 0) return [];

  const probe = join(backend, 'pom.stale-check.xml');
  const listing = join(backend, 'target', 'stale-check-dependencies.txt');
  writeFileSync(probe, pins.reduce((text, pin) => text.replace(pin.block, ''), pom));
  try {
    run(join(backend, WINDOWS ? 'mvnw.cmd' : 'mvnw'), ['-q', '-B', '-f', 'pom.stale-check.xml', 'dependency:list',
      `-DoutputFile=${listing}`, '-DoutputAbsoluteArtifactFilename=false'], { cwd: backend, stdio: ['ignore', 'pipe', 'inherit'] });
  } finally {
    rmSync(probe, { force: true });
  }
  const resolved = new Map([...readFileSync(listing, 'utf8').matchAll(/^\s*([^\s:]+):([^\s:]+):[^\s:]+:(?:[^\s:]+:)?([^\s:]+):[a-z]+/gm)]
    .map(m => [`${m[1]}:${m[2]}`, m[3]]));

  const stale = pins.filter(pin => {
    const without = resolved.get(`${pin.group}:${pin.artifact}`);
    return without === undefined || compareVersions(without, pin.version) >= 0;
  });
  if (stale.length === 0) return [];
  return [{
    file: 'backend/pom.xml',
    notes: stale.map(pin => {
      const without = resolved.get(`${pin.group}:${pin.artifact}`);
      return `\`${pin.group}:${pin.artifact}\` ${pin.version}: ${without === undefined ? 'nothing depends on it any more' : `resolves ${without} without the pin`}`;
    }),
    apply: () => {
      let text = stale.reduce((current, pin) => current.replace(pin.block, ''), pom);
      for (const pin of stale) {
        if (pin.property && !text.includes(`\${${pin.property}}`)) {
          text = text.replace(new RegExp(`[ \\t]*<${pin.property.replace(/\./g, '\\.')}>[^<]*</${pin.property.replace(/\./g, '\\.')}>\\n`), '');
        }
      }
      writeText(pomPath, text, eol);
    },
  }];
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const changes = [...staleIgnores(args.trivy), ...staleOverrides(), ...staleMavenPins()];
  const summary = changes.flatMap(change => [`- \`${change.file}\``, ...change.notes.map(note => `  - ${note}`)]).join('\n');
  console.log(changes.length === 0 ? 'Every exception still catches something.' : summary);
  if (args.summary) writeFileSync(args.summary, summary ? `${summary}\n` : '');
  if (args.apply) changes.forEach(change => change.apply());
}

main();
