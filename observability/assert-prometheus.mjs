const [prometheus, replicas = '1', grafana] = process.argv.slice(2);
const expectedReplicas = Number(replicas);
const failures = [];

async function get(origin, path) {
  const response = await fetch(`${origin}${path}`);
  if (!response.ok) {
    throw new Error(`${origin}${path} answered ${response.status}`);
  }
  return response.json();
}

async function query(expr) {
  const body = await get(prometheus, `/api/v1/query?query=${encodeURIComponent(expr)}`);
  return body.data.result;
}

const instances = await query('count by (instance) ({__name__=~"kanban_.+", job="kanban-api"})');
if (instances.length < expectedReplicas) {
  failures.push(`kanban_* series arrived from ${instances.length} instance(s), expected ${expectedReplicas}: `
    + 'export is off, the push is failing, or both replicas report the same service.instance.id');
}

const requests = await query('sum(http_server_requests_seconds_count{job="kanban-api"})');
if (!requests.length || Number(requests[0].value[1]) === 0) {
  failures.push('http_server_requests_seconds_count is missing or zero after a whole Cypress run');
}

const buckets = await query('count(http_server_requests_seconds_bucket{job="kanban-api"})');
if (!buckets.length) {
  failures.push('no http_server_requests_seconds_bucket series, so every p95 panel and the latency rule read nothing');
}

const targets = (await get(prometheus, '/api/v1/targets')).data.activeTargets;
if (targets.length === 0) {
  failures.push('Prometheus has no scrape targets at all');
}
for (const target of targets.filter(t => t.health !== 'up')) {
  failures.push(`scrape target ${target.labels.job}/${target.labels.instance} is ${target.health}: ${target.lastError}`);
}

const probes = await query('probe_success == 0');
for (const probe of probes) {
  failures.push(`the blackbox probe of ${probe.metric.instance} fails`);
}

const groups = (await get(prometheus, '/api/v1/rules')).data.groups;
if (groups.length === 0) {
  failures.push('no rule groups loaded, so the rules mount or the rule_files glob is wrong');
}
for (const rule of groups.flatMap(group => group.rules)) {
  if (rule.health === 'err') {
    failures.push(`rule ${rule.name} fails to evaluate: ${rule.lastError}`);
  }
}

if (grafana) {
  const dashboards = await get(grafana, '/api/search?type=dash-db');
  if (dashboards.length === 0) {
    failures.push('Grafana provisioned no dashboards');
  }
  const datasource = await get(grafana, '/api/datasources/proxy/uid/prometheus/api/v1/query?query=up');
  if (datasource.status !== 'success') {
    failures.push('Grafana cannot query Prometheus through its provisioned data source');
  }
  console.log(`Grafana: ${dashboards.map(d => d.title).join(', ')}`);
}

console.log(`instances: ${instances.map(i => i.metric.instance).join(', ')}`);
console.log(`targets: ${targets.map(t => `${t.labels.job}=${t.health}`).join(', ')}`);
console.log(`rule groups: ${groups.map(g => `${g.name}(${g.rules.length})`).join(', ')}`);
if (failures.length > 0) {
  console.error(`\n${failures.length} problem(s) with what Prometheus holds:\n- ${failures.join('\n- ')}`);
  process.exitCode = 1;
} else {
  console.log('\nPrometheus received metrics from every replica, scraped every target and loaded every rule.');
}
