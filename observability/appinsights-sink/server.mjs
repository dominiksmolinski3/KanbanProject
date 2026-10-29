import http from 'node:http';
import zlib from 'node:zlib';

const PORT = Number(process.env.PORT || 8080);

const summary = {
  items: new Map(),
  metrics: new Map(),
  requests: new Map(),
  roles: new Set(),
};

const bump = (counts, key) => counts.set(key, (counts.get(key) ?? 0) + 1);

function record(item) {
  const type = item?.data?.baseType ?? 'unknown';
  bump(summary.items, type);
  const tags = item.tags ?? {};
  if (tags['ai.cloud.role']) summary.roles.add(tags['ai.cloud.role']);
  if (type === 'MetricData') {
    for (const metric of item.data.baseData.metrics ?? []) {
      if (!summary.metrics.has(metric.name)) summary.metrics.set(metric.name, { count: 0, instances: new Set() });
      const entry = summary.metrics.get(metric.name);
      entry.count += 1;
      if (tags['ai.cloud.roleInstance']) entry.instances.add(tags['ai.cloud.roleInstance']);
    }
  } else if (type === 'RequestData') {
    const name = item.data.baseData.name ?? 'unnamed';
    bump(summary.requests, name);
  }
}

function snapshot() {
  return {
    items: Object.fromEntries(summary.items),
    roles: [...summary.roles],
    requests: Object.fromEntries(summary.requests),
    metrics: Object.fromEntries([...summary.metrics]
      .map(([name, entry]) => [name, { count: entry.count, instances: entry.instances.size }])),
  };
}

http.createServer((req, res) => {
  const chunks = [];
  req.on('data', chunk => chunks.push(chunk));
  req.on('end', () => {
    const json = (status, body) => {
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(body));
    };
    if (req.method === 'GET' && req.url === '/summary') return json(200, snapshot());
    if (req.method === 'GET' && req.url === '/health') return json(200, { status: 'UP' });
    if (req.method === 'POST' && req.url.includes('/track')) {
      let body = Buffer.concat(chunks);
      if (req.headers['content-encoding'] === 'gzip') {
        try { body = zlib.gunzipSync(body); } catch { /* recorded as unparseable below */ }
      }
      const lines = body.toString('utf8').split('\n').filter(Boolean);
      for (const line of lines) {
        try { record(JSON.parse(line)); } catch { bump(summary.items, 'unparseable'); }
      }
      return json(200, { itemsReceived: lines.length, itemsAccepted: lines.length, errors: [] });
    }
    return json(200, {});
  });
}).listen(PORT, '0.0.0.0', () => console.log(`appinsights-sink listening on ${PORT}`));
