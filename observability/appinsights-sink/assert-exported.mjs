// Asks the sink what the agent sent during a run and fails on what would be wrong in Azure.
// usage: node assert-exported.mjs <sink origin> [expected replica count]
//
// Each check is a claim terraform/modules/diagnostics and backend/applicationinsights.json make
// about the export, which until now only a hand-run capture had ever tested:
//   - the meters the alerts read arrive, under the agent's underscore spelling;
//   - nothing but kanban_* arrives, since the filter is what keeps the bill to a handful of series;
//   - no /actuator request is recorded, since the probes would otherwise be most of the traffic;
//   - every replica exports, which is what makes a fleet-wide sum() in an alert mean what it says.
const [origin, replicas = '1'] = process.argv.slice(2);
const expectedReplicas = Number(replicas);

const response = await fetch(`${origin}/summary`);
if (!response.ok) {
  console.error(`the sink at ${origin} answered ${response.status}`);
  process.exit(1);
}
const summary = await response.json();
const failures = [];

// Registered at startup and exported every interval whether or not anything happened, so a run
// long enough to reach one export interval always has them.
const alwaysExported = ['kanban_mail_outbox_pending', 'kanban_mail_outbox_dead_letters'];
for (const name of alwaysExported) {
  if (!summary.metrics[name]) {
    failures.push(`${name} never arrived - the agent is not attached, the filter drops it, or the name changed`);
  } else if (summary.metrics[name].instances < expectedReplicas) {
    failures.push(`${name} arrived from ${summary.metrics[name].instances} replica(s), expected ${expectedReplicas}`);
  }
}

const unfiltered = Object.keys(summary.metrics).filter(name => !name.startsWith('kanban_'));
if (unfiltered.length > 0) {
  failures.push(`metrics outside kanban_* were exported, so the filter is not applied: ${unfiltered.slice(0, 10).join(', ')}`);
}

const probes = Object.keys(summary.requests).filter(name => name.includes('/actuator'));
if (probes.length > 0) {
  failures.push(`probe requests were recorded, so they are not sampled out: ${probes.join(', ')}`);
}

if (!summary.roles.includes('kanban-api')) {
  failures.push(`no telemetry carried the role kanban-api (roles seen: ${summary.roles.join(', ') || 'none'})`);
}

console.log(JSON.stringify(summary, null, 2));
if (failures.length > 0) {
  console.error(`\n${failures.length} problem(s) with what the agent exported:\n- ${failures.join('\n- ')}`);
  process.exit(1);
}
console.log('\nThe agent exported what the alerts read, and nothing it should not.');
