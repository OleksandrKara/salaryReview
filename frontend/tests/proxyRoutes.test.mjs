import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'app');

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

const routes = walk(path.join(root, 'api')).filter((f) => f.endsWith('route.ts')).map((f) => {
  const rel = path.relative(path.join(root, 'api'), path.dirname(f)).split(path.sep);
  return { segs: rel, methods: [...fs.readFileSync(f, 'utf8').matchAll(/export async function (GET|POST|PUT|PATCH|DELETE)/g)].map((m) => m[1]) };
});

function matches(segs, urlSegs) {
  return segs.length === urlSegs.length && segs.every((s, i) => /^\[.+\]$/.test(s) || s === urlSegs[i]);
}

// Every browser call to /api/leads/... in the leads UI must have a Next.js proxy route, or it 404s in production.
test('every /api/leads call in the leads UI has a proxy route with that method', () => {
  const files = walk(path.join(root, 'admin', 'leads')).filter((f) => /\.tsx$/.test(f));
  const calls = [];
  for (const f of files) {
    const src = fs.readFileSync(f, 'utf8');
    for (const m of src.matchAll(/(?:fetch|send)\(\s*([`'])(\/api\/leads[^`']*)\1\s*(?:,\s*(?:'(GET|POST|PUT|PATCH|DELETE)'|\{[^}]*?method:\s*'(GET|POST|PUT|PATCH|DELETE)'))?/g)) {
      const url = m[2].split('?')[0].replace(/\$\{[^}]+\}/g, '[x]');
      calls.push({ f: path.basename(f), url, method: m[3] ?? m[4] ?? 'GET' });
    }
  }
  assert.ok(calls.length > 10, `found only ${calls.length} calls`);
  for (const c of calls) {
    const segs = c.url.split('/').slice(2);
    const route = routes.find((r) => matches(r.segs, segs.map((s) => (s === '[x]' ? '[x]' : s))));
    assert.ok(route, `${c.f}: no proxy route for ${c.method} ${c.url}`);
    assert.ok(route.methods.includes(c.method), `${c.f}: route for ${c.url} lacks ${c.method}`);
  }
});

test('the reached-out and attempts routes exist', () => {
  for (const n of ['attempts', 'reached-out']) {
    assert.ok(routes.some((r) => matches(r.segs, ['leads', '[id]', n]) && r.methods.includes('POST')), n);
  }
});
