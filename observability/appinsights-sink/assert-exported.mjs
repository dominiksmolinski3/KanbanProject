const [origin, replicas = '1'] = process.argv.slice(2);
const expectedReplicas = Number(replicas);

const response = await fetch(`${origin}/summary`);
if (!response.ok) {
  console.error(`the sink at ${origin} answered ${response.status}`);
  process.exit(1);
}
const summary = await response.json();
const failures = [];

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
