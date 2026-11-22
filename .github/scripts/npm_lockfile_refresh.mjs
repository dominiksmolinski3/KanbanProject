#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const FRONTEND = resolve(dirname(fileURLToPath(import.meta.url)), '../../frontend');

function loadSemver() {
  const node = dirname(process.execPath);
  const global = execFileSync('npm', ['root', '-g'], { encoding: 'utf8', shell: process.platform === 'win32' }).trim();
  const candidates = [join(node, '..', 'lib', 'node_modules', 'npm'), join(node, 'node_modules', 'npm'), join(global, 'npm')];
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
    if (lock.packages[candidate]) return { path: candidate, version: lock.packages[candidate].version };
    if (!base) return null;
    const cut = base.lastIndexOf('/node_modules/');
    base = cut >= 0 ? base.slice(0, cut) : '';
  }
}

function brokenOverrides(manifest, lock, semver) {
  const satisfies = (version, range) => semver.satisfies(version, range, { includePrerelease: true });
  const broken = [];
  for (const [key, value] of Object.entries(manifest.overrides ?? {})) {
    const { name, range } = splitSelector(key);
    if (typeof value === 'string') {
      const escaped = instances(lock, name).filter(i => (!range || satisfies(i.version, range)) && !satisfies(i.version, value));
      escaped.forEach(i => broken.push(`\`${key}\`: \`${i.path}\` is ${i.version}`));
      continue;
    }
    for (const parent of instances(lock, name).filter(i => !range || satisfies(i.version, range))) {
      for (const [child, target] of Object.entries(value)) {
        const resolved = resolveChild(lock, parent.path, child);
        if (resolved && !satisfies(resolved.version, target)) broken.push(`\`${key} > ${child}\`: \`${resolved.path}\` is ${resolved.version}`);
      }
    }
  }
  return broken;
}

function summary(manifest, before, after, semver) {
  const changed = Object.keys({ ...before.packages, ...after.packages })
    .filter(path => path !== '' && before.packages[path]?.version !== after.packages[path]?.version);
  const older = changed.filter(path => {
    const [from, to] = [before.packages[path]?.version, after.packages[path]?.version];
    return from && to && semver.valid(from) && semver.valid(to) && semver.lt(to, from);
  });
  const direct = Object.keys({ ...manifest.dependencies, ...manifest.devDependencies }).sort()
    .map(name => [name, before.packages[`node_modules/${name}`]?.version, after.packages[`node_modules/${name}`]?.version])
    .filter(([, from, to]) => from !== to);
  const lines = [`${changed.length} entries in \`frontend/package-lock.json\` move within the ranges \`package.json\` declares.`];
  if (older.length > 0) {
    lines.push('', `${older.length} paths now hold an older release, either hoisted differently or held back by the cooldown:`, '',
      ...older.map(path => `- \`${path.replace(/^node_modules\//, '')}\` ${before.packages[path].version} → ${after.packages[path].version}`));
  }
  if (direct.length > 0) {
    lines.push('', '| Direct dependency | From | To |', '| --- | --- | --- |', ...direct.map(([name, from, to]) => `| \`${name}\` | ${from ?? '-'} | ${to ?? '-'} |`));
  }
  return { changed: changed.length, text: `${lines.join('\n')}\n` };
}

const [baseLock, summaryFile] = process.argv.slice(2);
if (!baseLock || !summaryFile) throw new Error('usage: npm_lockfile_refresh.mjs <lock before the refresh> <summary file>');
const manifest = JSON.parse(readFileSync(join(FRONTEND, 'package.json'), 'utf8'));
const after = JSON.parse(readFileSync(join(FRONTEND, 'package-lock.json'), 'utf8'));
const semver = loadSemver();
const broken = brokenOverrides(manifest, after, semver);
if (broken.length > 0) {
  console.error(`::error::the refreshed lock escapes ${broken.length} override(s):\n${broken.join('\n')}`);
  process.exit(1);
}
const { changed, text } = summary(manifest, JSON.parse(readFileSync(baseLock, 'utf8')), after, semver);
writeFileSync(summaryFile, changed > 0 ? text : '');
console.log(changed > 0 ? text : 'The lock is already current.');
