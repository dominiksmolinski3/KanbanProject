#!/usr/bin/env node

import { createRequire } from 'node:module';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
// pg is a frontend devDependency; this script has no package.json of its own.
const pg = createRequire(path.join(here, '..', 'frontend', 'package.json'))('pg');

const USERS = Number(process.env.LOAD_USERS || 20);
const TASKS = Number(process.env.LOAD_TASKS || 300);
const SIGNUP_URL = process.env.LOAD_SIGNUP_URL || 'http://127.0.0.1:8081/api';
const EDGE_URL = process.env.LOAD_EDGE_URL || 'http://localhost:8080/api';
const OUT = path.join(here, '.run', 'accounts.json');
const RUN = Date.now().toString(36);
const PASSWORD = 'Load-test-0nly!';

const pgConfig = {
  host: process.env.LOAD_PGHOST || 'localhost',
  port: Number(process.env.LOAD_PGPORT || 5432),
  database: process.env.LOAD_PGDATABASE || process.env.SPRING_DATASOURCE_DB || 'kanban',
  user: process.env.LOAD_PGUSER || process.env.SPRING_DATASOURCE_USERNAME || 'kanban',
  password: process.env.LOAD_PGPASSWORD || process.env.SPRING_DATASOURCE_PASSWORD || '',
};

// 198.18.0.0/15 is reserved for benchmarking; one address per account keeps the per-address limits per user.
const addressOf = (i) => `198.18.${(i >> 8) & 255}.${i & 255}`;

async function call(base, method, pathname, { token, body, address } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (address) headers['X-Forwarded-For'] = address;
  const res = await fetch(base + pathname, { method, headers, body: body && JSON.stringify(body) });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${pathname} answered ${res.status}: ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : undefined;
}

async function codeFor(db, email) {
  for (let attempt = 0; attempt < 60; attempt++) {
    const { rows } = await db.query('select verification_code from users where email = $1', [email]);
    if (rows[0]?.verification_code) return rows[0].verification_code;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`no verification code for ${email}; is the API pointed at this Postgres?`);
}

async function createUser(db, i) {
  const email = `load-${RUN}-${i}@example.test`;
  const address = addressOf(i);
  await call(SIGNUP_URL, 'POST', '/auth/signup', {
    address,
    body: { email, password: PASSWORD, username: `load${RUN}${i}`, locale: 'en' },
  });
  const session = await call(SIGNUP_URL, 'POST', '/auth/verify', {
    address,
    body: { email, verificationCode: await codeFor(db, email) },
  });
  return { email, address, token: session.token };
}

const db = new pg.Pool({ ...pgConfig, max: 4 });
try {
  const users = [];
  for (let i = 0; i < USERS; i += 10) {
    const batch = Array.from({ length: Math.min(10, USERS - i) }, (_, k) => createUser(db, i + k));
    users.push(...(await Promise.all(batch)));
  }
  const [owner, ...members] = users;

  const board = await call(EDGE_URL, 'POST', '/boards', { ...owner, body: { name: `Load ${RUN}` } });
  for (const member of members) {
    await call(EDGE_URL, 'POST', `/boards/${board.id}/invitations`, {
      ...owner,
      body: { email: member.email, role: 'MEMBER' },
    });
    for (const invitation of await call(EDGE_URL, 'GET', '/invitations', member)) {
      await call(EDGE_URL, 'POST', `/invitations/${invitation.id}/accept`, member);
    }
  }

  const query = `?boardId=${board.id}`;
  for (let r = 0; r < 4; r++) {
    await call(EDGE_URL, 'POST', `/rows${query}`, { ...owner, body: { name: `Lane ${r + 1}` } });
  }
  const columns = await call(EDGE_URL, 'GET', `/columns${query}`, owner);
  const rows = await call(EDGE_URL, 'GET', `/rows${query}`, owner);
  const labels = ['bug', 'ops', 'ui', 'api'];
  await Promise.all(users.map(async (user, u) => {
    for (let t = u; t < TASKS; t += users.length) {
      await call(EDGE_URL, 'POST', `/tasks${query}`, {
        ...user,
        body: {
          title: `Load task ${t}`,
          labels: [labels[t % labels.length]],
          column: { id: columns[t % columns.length].id },
          row: { id: rows[t % rows.length].id },
        },
      });
    }
  }));

  mkdirSync(path.dirname(OUT), { recursive: true });
  writeFileSync(OUT, JSON.stringify({ boardId: board.id, seededAt: new Date().toISOString(), users }, null, 2));
  console.log(`[load-seed] ${users.length} accounts on board ${board.id} with ${TASKS} tasks -> ${OUT}`);
} finally {
  await db.end();
}
