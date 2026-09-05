#!/usr/bin/env node
/**
 * Seeds two verified accounts for the Cypress suite: the primary sign-in account, and a second
 * board member ("User One") that the assignment specs need to select.
 *
 * Cypress specs used to hardcode a personal developer's email/password (see
 * `frontend/cypress/fixtures/test-account.json` for the account this replaces it with). That
 * account only ever existed in one person's own Postgres, and signup normally requires a mailbox
 * to receive a verification code - which CI has no way to do, because mail is off by design
 * unless ACS is configured (see CLAUDE.md, "Mail is enqueued, not sent").
 *
 * Rather than hand-crafting a BCrypt hash and an already-verified row, this drives the real
 * signup/verify endpoints the same way a person would, for each account:
 *
 *   1. POST /api/auth/signup - the account is created disabled, with a verification code.
 *   2. Read `users.verification_code` straight out of Postgres (no mailbox needed - the code the
 *      app generated is already sitting in the row it wrote).
 *   3. POST /api/auth/verify with that code, which is what a real user clicking an emailed link
 *      would send.
 *
 * The second account is then added to the primary's board via POST /api/boards/{id}/members -
 * GET /api/users only lists accounts the caller shares a board with (see CLAUDE.md's tenancy
 * section), so without this step the assignment `<select>` in TaskDetails has nobody to offer.
 *
 * Idempotent throughout: an already-verified account is left alone, and adding a member who is
 * already on the board is treated as success rather than retried. Safe to run before every
 * Cypress invocation, locally or in CI.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import pg from 'pg';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const primaryAccount = JSON.parse(
  readFileSync(path.join(__dirname, 'fixtures', 'test-account.json'), 'utf8')
);

// Not read from a fixture of its own: nothing needs to log in as this account, only to select it
// by name, so its credentials never leave this script. The name is "User One" verbatim because
// that is the literal option text users/user-assignment.cy.js selects.
const memberAccount = {
  email: 'cypress.member@kanban.local',
  username: 'User One',
  password: 'Cypress-E2E-Member-1!',
};

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

/** Signs up and verifies one account, unless it is already enabled. Returns nothing - the
 * caller logs in separately, since a freshly-verified account already has a session from
 * /auth/verify's response that this script has no use for. */
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
    // AuthenticationService#signup is a no-op for an email that already has a row (it answers
    // 202 either way, without touching the code), so it would not refresh one left over - and
    // possibly expired, after 15 minutes - from an earlier run. /resend is what actually
    // rewrites verification_code and its expiry for an unverified account.
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

/** Adds memberAccount to primaryAccount's board, unless it is already a member. */
async function ensureBoardMembership() {
  const login = await fetchJson('/auth/login', {
    method: 'POST',
    body: { email: primaryAccount.email, password: primaryAccount.password },
  });

  const board = await fetchJson('/boards/current', {
    headers: { Authorization: `Bearer ${login.token}` },
  });

  if (board.members.some((m) => m.email === memberAccount.email)) {
    console.log(`[seed] ${memberAccount.email} is already a member of board ${board.id} - nothing to do`);
    return;
  }

  console.log(`[seed] adding ${memberAccount.email} to board ${board.id} via POST /api/boards/${board.id}/members`);
  await fetchJson(`/boards/${board.id}/members`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${login.token}` },
    body: { email: memberAccount.email },
  });

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
