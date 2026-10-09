import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const BASE = __ENV.LOAD_BASE_URL || 'http://web:8080/api';
const PROFILE = __ENV.LOAD_PROFILE || 'smoke';
const ACCOUNTS = __ENV.LOAD_ACCOUNTS || './.run/accounts.json';

const seeded = JSON.parse(open(ACCOUNTS));
const users = new SharedArray('users', () => seeded.users);
const board = `?boardId=${seeded.boardId}`;

const serverErrors = new Counter('server_errors');
const SCRATCH = 'Load scratch ';

// Rates are iterations per second per scenario; each iteration is one user action of one to three requests.
const MIX = { churn: 4, contention: 2, browse: 2, search: 1 };

const PROFILES = {
  smoke: { scale: 1, stages: [{ target: 1, duration: __ENV.LOAD_DURATION || '60s' }], p95: 5000 },
  load: { scale: 4, stages: [{ target: 1, duration: '1m' }, { target: 1, duration: __ENV.LOAD_DURATION || '8m' }], p95: 1500 },
  stress: { scale: 4, stages: [{ target: 1, duration: '2m' }, { target: 2, duration: '3m' }, { target: 3, duration: '3m' }, { target: 0, duration: '1m' }], p95: 5000 },
  spike: { scale: 4, stages: [{ target: 1, duration: '1m' }, { target: 5, duration: '20s' }, { target: 5, duration: '1m' }, { target: 1, duration: '20s' }, { target: 1, duration: '2m' }], p95: 5000 },
};

const profile = PROFILES[PROFILE];
if (!profile) throw new Error(`LOAD_PROFILE must be one of ${Object.keys(PROFILES).join(', ')}`);

function scenario(name, perSecond) {
  const rate = perSecond * Number(__ENV.LOAD_RATE_SCALE || profile.scale);
  return {
    executor: 'ramping-arrival-rate',
    exec: name,
    startRate: rate,
    timeUnit: '1s',
    preAllocatedVUs: rate * 2,
    // Past this an iteration is dropped and counted rather than given a new VU; more VUs outgrow k6's 512M limit.
    maxVUs: rate * 4,
    stages: profile.stages.map(({ target, duration }) => ({ target: Math.round(rate * target), duration })),
  };
}

export const options = {
  scenarios: Object.fromEntries(Object.entries(MIX).map(([name, perSecond]) => [name, scenario(name, perSecond)])),
  // url and the like would make one Prometheus series per task id.
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
  thresholds: {
    server_errors: ['count==0'],
    http_req_failed: ['rate<0.01'],
    http_req_duration: [`p(95)<${Number(__ENV.LOAD_P95_MS || profile.p95)}`],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

const RACES = http.expectedStatuses({ min: 200, max: 299 }, 404, 409);
const CELL_RACES = http.expectedStatuses({ min: 200, max: 299 }, 400, 404, 409);

function user() {
  return users[(exec.vu.idInTest - 1) % users.length];
}

// A body k6 keeps is memory per VU, and a board of 300 cards is megabytes; only keep the ones a scenario reads.
function request(method, path, name, body, expected = RACES, responseType = 'none') {
  const { token, address } = user();
  const res = http.request(method, BASE + path, body === undefined ? null : JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'X-Forwarded-For': address },
    tags: { name },
    responseCallback: expected,
    responseType,
  });
  if (res.status === 0 || res.status >= 500) {
    serverErrors.add(1, { name });
  }
  return res;
}

function list(path, name) {
  const res = request('GET', path, name, undefined, RACES, 'text');
  return res.status === 200 ? res.json() : [];
}

// Like the client, hold the layout in memory and re-read it now and then rather than before every move.
let layout = { columns: [], rows: [], age: Infinity };
function currentLayout() {
  if (layout.age++ >= 20) {
    layout = {
      columns: list(`/columns${board}`, 'GET /columns').filter((c) => !c.name.startsWith(SCRATCH)),
      rows: list(`/rows${board}`, 'GET /rows'),
      age: 0,
    };
  }
  return layout;
}

const pick = (items) => items[Math.floor(Math.random() * items.length)];
const shuffle = (items) => items.map((item) => [Math.random(), item]).sort((a, b) => a[0] - b[0]).map(([, item]) => item);

export function churn() {
  const tasks = list(`/tasks${board}`, 'GET /tasks');
  const task = pick(tasks);
  if (!task) return;
  const roll = Math.random();
  if (roll < 0.6) {
    const { columns, rows } = currentLayout();
    if (!columns.length || !rows.length) return;
    request('PATCH', `/tasks/${task.id}`, 'PATCH /tasks/{id}', {
      column: { id: pick(columns).id },
      row: { id: pick(rows).id },
      version: task.version,
    });
  } else if (roll < 0.85) {
    const cell = tasks.filter((t) => t.columnId === task.columnId && t.rowId === task.rowId).map((t) => t.id);
    request('PATCH', '/tasks/positions', 'PATCH /tasks/positions', { orderedIds: shuffle(cell) }, CELL_RACES);
  } else {
    request('PATCH', `/tasks/${task.id}/complete/${!task.completed}`, 'PATCH /tasks/{id}/complete');
  }
}

// Concurrent writes to the same rows are what this scenario is for; one request at a time rarely overlaps.
const RACERS = 3;

export function contention() {
  const roll = Math.random();
  if (roll < 0.4) {
    const ids = list(`/columns${board}`, 'GET /columns').map((c) => c.id);
    if (ids.length) race(Array.from({ length: RACERS }, () => ['PATCH', '/columns/positions', 'PATCH /columns/positions', { orderedIds: shuffle(ids) }]));
  } else if (roll < 0.8) {
    const ids = list(`/rows${board}`, 'GET /rows').map((r) => r.id);
    if (ids.length) race(Array.from({ length: RACERS }, () => ['PATCH', '/rows/positions', 'PATCH /rows/positions', { orderedIds: shuffle(ids) }]));
  } else {
    deleteColumnUnderAMove();
  }
}

function race(requests) {
  const { token, address } = user();
  const responses = http.batch(requests.map(([method, path, name, body]) => [
    method,
    BASE + path,
    body === undefined ? null : JSON.stringify(body),
    {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'X-Forwarded-For': address },
      tags: { name },
      responseCallback: RACES,
      responseType: 'none',
    },
  ]));
  responses.forEach((res, i) => {
    if (res.status === 0 || res.status >= 500) serverErrors.add(1, { name: requests[i][2] });
  });
  return responses;
}

function deleteColumnUnderAMove() {
  const tasks = list(`/tasks${board}`, 'GET /tasks');
  const [inside, mover] = [pick(tasks), pick(tasks)];
  const created = request('POST', `/columns${board}`, 'POST /columns', { name: `${SCRATCH}${exec.vu.idInTest}` }, RACES, 'text');
  if (!inside || !mover || inside.id === mover.id || created.status !== 201) return;
  const column = created.json().id;
  const placed = request('PATCH', `/tasks/${inside.id}`, 'PATCH /tasks/{id}', { column: { id: column }, version: inside.version });
  const [, moved] = race([
    ['DELETE', `/columns/${column}`, 'DELETE /columns/{id}'],
    ['PATCH', `/tasks/${mover.id}`, 'PATCH /tasks/{id}', { column: { id: column }, version: mover.version }],
  ]);
  // Deleting a column deletes its cards; put back what this took so long runs keep their board.
  const lost = [placed.status === 200 && inside, moved.status === 200 && mover].filter(Boolean);
  const { columns } = currentLayout();
  for (const task of lost) {
    if (!columns.length) break;
    request('POST', `/tasks${board}`, 'POST /tasks', {
      title: task.title,
      column: { id: pick(columns).id },
      row: task.rowId ? { id: task.rowId } : undefined,
    });
  }
}

export function browse() {
  request('GET', `/columns${board}`, 'GET /columns');
  request('GET', `/rows${board}`, 'GET /rows');
  request('GET', `/tasks${board}`, 'GET /tasks');
}

export function search() {
  const roll = Math.random();
  if (roll < 0.5) request('GET', `/tasks/search${board}&q=Load&label=bug&size=50`, 'GET /tasks/search');
  else if (roll < 0.8) request('GET', `/activity${board}&size=50`, 'GET /activity');
  else request('GET', `/flow${board}`, 'GET /flow');
}
