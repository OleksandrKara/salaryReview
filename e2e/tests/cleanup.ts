/**
 * Sweep helpers. Used by globalSetup (suite-level safety net) and exposed
 * for any test that wants to scrub a known prefix on teardown.
 *
 * Convention: anything created by the test suite uses a recognizable prefix
 * on `displayName` — UI_* (via UI tests) or E2E_* (via fixtures). Anything
 * matching is fair game to delete.
 */

const API_BASE = process.env.PW_API_BASE ?? 'http://localhost:8080';

interface Provider {
  id: number;
  displayName: string;
}

const TEST_PROVIDER_PREFIXES = ['UI_', 'E2E_'];

function isTestProvider(p: Provider): boolean {
  return TEST_PROVIDER_PREFIXES.some((pfx) => p.displayName.startsWith(pfx));
}

export async function sweepTestProviders(): Promise<number> {
  const res = await fetch(`${API_BASE}/api/providers?all=true`);
  if (!res.ok) return 0;
  const all = (await res.json()) as Provider[];
  const stale = all.filter(isTestProvider);
  await Promise.all(
    stale.map((p) =>
      fetch(`${API_BASE}/api/providers/${p.id}`, { method: 'DELETE' }).catch(() => undefined),
    ),
  );
  return stale.length;
}
