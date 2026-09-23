// A stand-in for Application Insights' ingestion endpoint, for docker-compose and CI - what azurite
// is to Blob Storage. There is no official emulator, but the agent only POSTs gzipped JSON lines to
// whatever IngestionEndpoint its connection string names, so recording them is enough to run the
// real agent locally and to assert on what it would send to Azure.
//
// It keeps a running summary rather than the raw items, so a long session cannot grow it without
// bound: GET /summary answers which metric names arrived (and from how many replicas), which
// requests were recorded, and how many items of each type. It never refuses an item - a sink that
// answered 400 would make the agent retry and buffer, which is not what this is here to test.
//
// No dependencies, deliberately: it runs from a stock node image with this directory mounted.
import http from 'node:http';
import zlib from 'node:zlib';

const PORT = Number(process.env.PORT || 8080);

const summary = {
  items: {},          // baseType -> count
  metrics: {},        // metric name -> { count, instances: Set }
  requests: {},       // request name -> count
  roles: new Set(),
};

function record(item) {
  const type = item?.data?.baseType ?? 'unknown';
  summary.items[type] = (summary.items[type] ?? 0) + 1;
  const tags = item.tags ?? {};
  if (tags['ai.cloud.role']) summary.roles.add(tags['ai.cloud.role']);
  if (type === 'MetricData') {
    for (const metric of item.data.baseData.metrics ?? []) {
      const entry = (summary.metrics[metric.name] ??= { count: 0, instances: new Set() });
      entry.count += 1;
      if (tags['ai.cloud.roleInstance']) entry.instances.add(tags['ai.cloud.roleInstance']);
    }
  } else if (type === 'RequestData') {
    const name = item.data.baseData.name ?? 'unnamed';
    summary.requests[name] = (summary.requests[name] ?? 0) + 1;
  }
}

function snapshot() {
  return {
    items: summary.items,
    roles: [...summary.roles],
    requests: summary.requests,
    metrics: Object.fromEntries(Object.entries(summary.metrics)
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
        try { record(JSON.parse(line)); } catch { summary.items.unparseable = (summary.items.unparseable ?? 0) + 1; }
      }
      return json(200, { itemsReceived: lines.length, itemsAccepted: lines.length, errors: [] });
    }
    // Live Metrics pings and the agent's profile lookups: answered so it does not retry them.
    return json(200, {});
  });
}).listen(PORT, '0.0.0.0', () => console.log(`appinsights-sink listening on ${PORT}`));
