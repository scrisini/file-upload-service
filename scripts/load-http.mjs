// Keeps <clients> concurrent GET requests going against a running instance for <seconds>, then
// prints requests/s, client-side latency percentiles and bytes/s as one JSON line. Used to measure
// README "Capacity"; read the server side (CPU, heap, timers) from /actuator/metrics meanwhile.
//
//   node scripts/load-http.mjs <clients> <seconds> <url>
//   node scripts/load-http.mjs 50 60 http://localhost:8080/api/v1/things
//
// Needs Node 18 or later; no packages.
import http from 'node:http';

const [clients, seconds, target] = process.argv.slice(2);
if (!clients || !seconds || !target) {
  console.error('usage: node scripts/load-http.mjs <clients> <seconds> <url>');
  process.exit(2);
}
const url = new URL(target);
const agent = new http.Agent({ keepAlive: true, maxSockets: Number(clients) });
const latencies = [];
let ok = 0, errors = 0, bytes = 0, running = true;

function request() {
  return new Promise(resolve => {
    const start = process.hrtime.bigint();
    const req = http.get({ host: url.hostname, port: url.port, path: url.pathname + url.search, agent }, res => {
      res.on('data', chunk => { bytes += chunk.length; });
      res.on('end', () => {
        latencies.push(Number(process.hrtime.bigint() - start) / 1e6);
        if (res.statusCode < 400) ok++; else errors++;
        resolve();
      });
    });
    req.on('error', () => { errors++; resolve(); });
  });
}

async function client() {
  while (running) {
    await request();
  }
}

const percentile = (sorted, p) => sorted.length ? +sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))].toFixed(2) : 0;
setTimeout(() => { running = false; }, Number(seconds) * 1000);
await Promise.all(Array.from({ length: Number(clients) }, client));
latencies.sort((a, b) => a - b);
console.log(JSON.stringify({ clients: Number(clients), seconds: Number(seconds), requestsPerSec: +(ok / seconds).toFixed(1),
    errors, p50ms: percentile(latencies, 0.5), p95ms: percentile(latencies, 0.95), p99ms: percentile(latencies, 0.99),
    kbPerSec: +(bytes / 1024 / seconds).toFixed(1) }));
agent.destroy();
