#!/usr/bin/env node

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import pg from 'pg';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const fixture = (name) =>
  JSON.parse(readFileSync(path.join(__dirname, 'fixtures', `${name}.json`), 'utf8'));

const primaryAccount = fixture('test-account');

const memberAccount = fixture('member-account');

const API_BASE_URL = process.env.SEED_API_BASE_URL || 'http://localhost:8080/api';

const pgConfig = {
  host: process.env.SEED_PGHOST || 'localhost',
  port: Number(process.env.SEED_PGPORT || 5432),
  database:
    process.env.SEED_PGDATABASE || process.env.SPRING_DATASOURCE_DB || 'kanban',
  user: process.env.SEED_PGUSER || process.env.SPRING_DATASOURCE_USERNAME || 'kanban',
  password:
    process.env.SEED_PGPASSWORD || process.env.SPRING_DATASOURCE_PASSWORD || '',
};

const MAX_WAIT_MS = Number(process.env.SEED_TIMEOUT_MS || 30000);
const POLL_INTERVAL_MS = 500;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForVerificationCode(client, email) {
  const deadline = Date.now() + MAX_WAIT_MS;
  while (Date.now() < deadline) {
    const { rows } = await client.query(
      'select verification_code from users where email = $1',
      [email]
    );
    if (rows.length > 0 && rows[0].verification_code) {
      return rows[0].verification_code;
    }
    await sleep(POLL_INTERVAL_MS);
  }
  throw new Error(
    `Timed out waiting for a verification_code for ${email}. ` +
      'Is the backend actually reachable and pointed at this Postgres?'
  );
}

async function fetchJson(pathname, options = {}) {
  const res = await fetch(`${API_BASE_URL}${pathname}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`${options.method || 'GET'} ${pathname} -> ${res.status} ${res.statusText}: ${text}`);
  }
  const contentType = res.headers.get('content-type') || '';
  return contentType.includes('application/json') ? res.json() : undefined;
}

async function ensureVerified(client, account) {
  const existing = await client.query(
    'select enabled from users where email = $1',
    [account.email]
  );

  if (existing.rows.length > 0 && existing.rows[0].enabled) {
    console.log(`[seed] ${account.email} already exists and is verified - nothing to do`);
    return;
  }

  if (existing.rows.length > 0) {
    console.log(`[seed] ${account.email} exists but is unverified - requesting a fresh code`);
    await fetchJson(`/auth/resend?email=${encodeURIComponent(account.email)}`, { method: 'POST' });
  } else {
    console.log(`[seed] signing up ${account.email} via POST /api/auth/signup`);
    await fetchJson('/auth/signup', {
      method: 'POST',
      body: { email: account.email, password: account.password, username: account.username, locale: 'en' },
    });
  }

  console.log(`[seed] waiting for ${account.email}'s verification code to land in Postgres...`);
  const verificationCode = await waitForVerificationCode(client, account.email);

  console.log(`[seed] verifying ${account.email} via POST /api/auth/verify`);
  await fetchJson('/auth/verify', {
    method: 'POST',
    body: { email: account.email, verificationCode },
  });

  console.log(`[seed] ${account.email} is verified`);
}

async function ensureBoardMembership() {
  const ownerLogin = await fetchJson('/auth/login', {
    method: 'POST',
    body: { email: primaryAccount.email, password: primaryAccount.password },
  });
  const asOwner = { Authorization: `Bearer ${ownerLogin.token}` };

  const board = await fetchJson('/boards/current', { headers: asOwner });

  if (board.members.some((m) => m.email === memberAccount.email)) {
    console.log(`[seed] ${memberAccount.email} is already a member of board ${board.id} - nothing to do`);
    return;
  }

  console.log(`[seed] inviting ${memberAccount.email} to board ${board.id}`);
  await fetchJson(`/boards/${board.id}/invitations`, {
    method: 'POST',
    headers: asOwner,
    body: { email: memberAccount.email },
  });

  const memberLogin = await fetchJson('/auth/login', {
    method: 'POST',
    body: { email: memberAccount.email, password: memberAccount.password },
  });
  const asMember = { Authorization: `Bearer ${memberLogin.token}` };

  const invitations = await fetchJson('/invitations', { headers: asMember });
  const mine = invitations.find((invitation) => invitation.boardId === board.id);
  if (!mine) {
    throw new Error(`[seed] no pending invitation to board ${board.id} for ${memberAccount.email}`);
  }

  console.log(`[seed] accepting invitation ${mine.id} as ${memberAccount.email}`);
  await fetchJson(`/invitations/${mine.id}/accept`, { method: 'POST', headers: asMember });

  console.log(`[seed] ${memberAccount.email} is now a member of board ${board.id}`);
}

async function main() {
  const client = new pg.Client(pgConfig);
  await client.connect();

  try {
    await ensureVerified(client, primaryAccount);
    await ensureVerified(client, memberAccount);
    await ensureBoardMembership();
  } finally {
    await client.end();
  }
}

main().catch((err) => {
  console.error('[seed] failed:', err.message);
  process.exit(1);
});
