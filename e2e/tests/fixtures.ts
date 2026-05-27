import { APIRequestContext, request, test as base } from '@playwright/test';
import { HomePage } from '../pages/HomePage';
import { PeriodEditorPage } from '../pages/PeriodEditorPage';
import { ProvidersPage } from '../pages/ProvidersPage';

const API_BASE = process.env.PW_API_BASE ?? 'http://localhost:8080';

type Half = 'FIRST' | 'SECOND';

export interface PayPeriod {
  id: number;
  year: number;
  month: number;
  half: Half;
  label: string;
}

export interface Provider {
  id: number;
  name: string;
  displayName: string;
  commissionRate: number;
  cardTipFeeRate: number;
  active: boolean;
}

/**
 * Thin typed wrapper around the backend REST API — used by fixtures for
 * deterministic setup and teardown that doesn't depend on UI selectors.
 */
export class SalonApi {
  constructor(private readonly api: APIRequestContext) {}

  async createPeriod(input: { year: number; month: number; half: Half }): Promise<PayPeriod> {
    const res = await this.api.post(`${API_BASE}/api/pay-periods`, { data: input });
    if (!res.ok()) throw new Error(`createPeriod failed: ${res.status()} ${await res.text()}`);
    return res.json();
  }

  async deletePeriod(id: number): Promise<void> {
    // Idempotent: 404 is fine for cleanup (test may have already deleted it).
    const res = await this.api.delete(`${API_BASE}/api/pay-periods/${id}`);
    if (!res.ok() && res.status() !== 404) {
      throw new Error(`deletePeriod failed: ${res.status()}`);
    }
  }

  async listProviders(opts?: { includeInactive?: boolean }): Promise<Provider[]> {
    const url = `${API_BASE}/api/providers${opts?.includeInactive ? '?all=true' : ''}`;
    const res = await this.api.get(url);
    if (!res.ok()) throw new Error(`listProviders failed: ${res.status()}`);
    return res.json();
  }

  async createProvider(input: {
    name: string;
    displayName: string;
    commissionRate?: number;
    cardTipFeeRate?: number;
  }): Promise<Provider> {
    const res = await this.api.post(`${API_BASE}/api/providers`, { data: input });
    if (!res.ok()) throw new Error(`createProvider failed: ${res.status()} ${await res.text()}`);
    return res.json();
  }

  async deleteProvider(id: number): Promise<void> {
    const res = await this.api.delete(`${API_BASE}/api/providers/${id}`);
    if (!res.ok() && res.status() !== 404) {
      throw new Error(`deleteProvider failed: ${res.status()}`);
    }
  }
}

/**
 * Try a few random (year, month, half) combinations within the backend's
 * allowed range (year ∈ [2000, 2100]) until one creates cleanly. Backend
 * enforces UNIQUE(year, month, half), so the only failure mode is collision
 * with seeded or previously-created data — retrying handles it.
 */
async function createUniquePeriod(api: SalonApi): Promise<PayPeriod> {
  for (let i = 0; i < 20; i++) {
    const year = 2050 + Math.floor(Math.random() * 50);      // 2050..2099
    const month = 1 + Math.floor(Math.random() * 12);
    const half: Half = Math.random() < 0.5 ? 'FIRST' : 'SECOND';
    try {
      return await api.createPeriod({ year, month, half });
    } catch (err) {
      if (i === 19) throw err;
    }
  }
  throw new Error('unreachable');
}

/**
 * Custom test fixtures. Each one auto-cleans on teardown, *regardless of
 * whether the test passes or fails*. Playwright runs fixture teardown in
 * an `afterEach`-style hook bound to the lifetime of the fixture scope.
 */
export const test = base.extend<{
  api: SalonApi;
  freshPeriod: PayPeriod;
  freshProvider: Provider;
  homePage: HomePage;
  periodEditor: PeriodEditorPage;
  providersPage: ProvidersPage;
}>({
  // Per-test API context so requests are isolated and fixture teardown
  // can still call the backend even if the page has navigated away.
  api: async ({}, use) => {
    const ctx = await request.newContext();
    await use(new SalonApi(ctx));
    await ctx.dispose();
  },

  // Creates a unique pay period; deletes it after the test (idempotent).
  freshPeriod: async ({ api }, use) => {
    const period = await createUniquePeriod(api);
    try {
      await use(period);
    } finally {
      await api.deletePeriod(period.id);
    }
  },

  // Creates a unique provider; deletes it after the test (cascades to entries).
  freshProvider: async ({ api }, use) => {
    const suffix = Math.random().toString(36).slice(2, 8);
    const provider = await api.createProvider({
      name: `E2E Test ${suffix}`,
      displayName: `E2E_${suffix}`,
      commissionRate: 0.5,
      cardTipFeeRate: 0.035,
    });
    try {
      await use(provider);
    } finally {
      await api.deleteProvider(provider.id);
    }
  },

  /* --- Page Object fixtures --- */

  homePage: async ({ page }, use) => {
    await use(new HomePage(page));
  },

  periodEditor: async ({ page }, use) => {
    await use(new PeriodEditorPage(page));
  },

  providersPage: async ({ page }, use) => {
    await use(new ProvidersPage(page));
  },
});

export { expect } from '@playwright/test';
