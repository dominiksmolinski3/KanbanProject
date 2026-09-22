#!/usr/bin/env node
import { readFileSync, existsSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import path from 'node:path';

// The budget the sign-in screen's own download graph must stay under, gzipped. Measured right
// after FE-01 split the four protected routes and vendor-react out of the entry chunk: ~116 KB.
// This is a ceiling rather than a floor, so the margin goes the other way from the JaCoCo floors:
// the old, unsplit bundle shipped 224 KB gzipped (203 KB JS + 21 KB CSS) before anyone painted a
// sign-in form, and this budget stays well under that so a regression back toward one big chunk
// fails the build instead of quietly creeping back up to the old number.
const BUDGET_BYTES = 150 * 1024;

const distDir = path.resolve(import.meta.dirname, '..', 'dist');
const manifestPath = path.join(distDir, '.vite', 'manifest.json');

if (!existsSync(manifestPath)) {
  console.error(`No manifest at ${manifestPath} - run "npm run build" first.`);
  process.exit(1);
}

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
const entryKey = Object.keys(manifest).find((key) => manifest[key].isEntry);
if (!entryKey) {
  console.error('No entry chunk in the manifest.');
  process.exit(1);
}

// Walk only the *static* import graph. A `dynamicImports` edge is a lazy route (React.lazy in
// App.jsx) - following it would defeat the point of the budget, which exists to catch code that
// reaches the sign-in screen before it should, not the code the split deliberately defers.
const files = new Set();
const visited = new Set();

function visit(key) {
  if (visited.has(key)) return;
  visited.add(key);
  const entry = manifest[key];
  if (!entry) return;
  files.add(entry.file);
  (entry.css || []).forEach((cssFile) => files.add(cssFile));
  (entry.imports || []).forEach(visit);
}

visit(entryKey);

let totalGzip = 0;
const breakdown = [];
for (const file of files) {
  const raw = readFileSync(path.join(distDir, file));
  const gzip = gzipSync(raw).length;
  totalGzip += gzip;
  breakdown.push({ file, gzip });
}

breakdown.sort((a, b) => b.gzip - a.gzip);
console.log("Files on the sign-in screen's own download graph (gzipped):");
for (const { file, gzip } of breakdown) {
  console.log(`  ${(gzip / 1024).toFixed(1).padStart(7)} kB  ${file}`);
}
console.log(`  ${(totalGzip / 1024).toFixed(1).padStart(7)} kB  total`);

if (totalGzip > BUDGET_BYTES) {
  console.error(
    `\nInitial bundle is ${(totalGzip / 1024).toFixed(1)} kB gzipped, over the ${(BUDGET_BYTES / 1024).toFixed(0)} kB budget.\n` +
    'Something that should be behind React.lazy() is reaching the entry chunk again - see FE-01 in the audit artifact.'
  );
  process.exit(1);
}

console.log(`\nWithin budget (${(totalGzip / 1024).toFixed(1)} kB / ${(BUDGET_BYTES / 1024).toFixed(0)} kB).`);
