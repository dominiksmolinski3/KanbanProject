#!/usr/bin/env node
/**
 * Seeds one verified account for the Cypress suite to sign in with.
 *
 * Cypress specs used to hardcode a personal developer's email/password (see
 * `frontend/cypress/fixtures/test-account.json` for the account this replaces it with). That
 * account only ever existed in one person's own Postgres, and signup normally requires a mailbox
 * to receive a verification code - which CI has no way to do, because mail is off by design
 * unless ACS is configured (see CLAUDE.md, "Mail is enqueued, not sent").
 *
 * Rather than hand-crafting a BCrypt hash and an already-verified row, this drives the real
 * signup/verify endpoints the same way a person would:
 *
 *   1. POST /api/auth/signup - the account is created disabled, with a verification code.
 *   2. Read `users.verification_code` straight out of Postgres (no mailbox needed - the code the
 *      app generated is already sitting in the row it wrote).
 *   3. POST /api/auth/verify with that code, which is what a real user clicking an emailed link
 *      would send.
 *
 * Idempotent: if the account already exists and is enabled, this is a no-op. Safe to run before
 * every Cypress invocation, locally or in CI.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import pg from 'pg';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const account = JSON.parse(
  readFileSync(path.join(__dirname, 'fixtures', 'test-account.json'), 'utf8')
);

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

async function waitForVerificationCode(client) {
  const deadline = Date.now() + MAX_WAIT_MS;
  while (Date.now() < deadline) {
    const { rows } = await client.query(
      'select verification_code from users where email = $1',
      [account.email]
    );
    if (rows.length > 0 && rows[0].verification_code) {
      return rows[0].verification_code;
    }
    await sleep(POLL_INTERVAL_MS);
  }
  throw new Error(
    `Timed out waiting for a verification_code for ${account.email}. ` +
      'Is the backend actually reachable and pointed at this Postgres?'
  );
}

async function postJson(pathname, body) {
  const res = await fetch(`${API_BASE_URL}${pathname}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`POST ${pathname} -> ${res.status} ${res.statusText}: ${text}`);
  }
  return res;
}

async function main() {
  const client = new pg.Client(pgConfig);
  await client.connect();

  try {
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
      await postJson(`/auth/resend?email=${encodeURIComponent(account.email)}`);
    } else {
      console.log(`[seed] signing up ${account.email} via POST /api/auth/signup`);
      await postJson('/auth/signup', {
        email: account.email,
        password: account.password,
        username: account.username,
        locale: 'en',
      });
    }

    console.log('[seed] waiting for the verification code to land in Postgres...');
    const verificationCode = await waitForVerificationCode(client);

    console.log(`[seed] verifying ${account.email} via POST /api/auth/verify`);
    await postJson('/auth/verify', {
      email: account.email,
      verificationCode,
    });

    console.log(`[seed] ${account.email} is verified and ready for Cypress`);
  } finally {
    await client.end();
  }
}

main().catch((err) => {
  console.error('[seed] failed:', err.message);
  process.exit(1);
});
