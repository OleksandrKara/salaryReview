import { expect, Locator, Page } from '@playwright/test';

export interface EntryFields {
  procedures?: number | string;
  card?: number | string;
  cash?: number | string;
  tips?: number | string;
  adj?: number | string;
  note?: string;
  /** Empty string clears the per-period rate override (revert to provider default). */
  ratePct?: string;
}

/**
 * Page Object for `/periods/{id}` — the period editor.
 *
 * The page renders one editable row per active provider, plus a Calculate
 * button that surfaces a settlement card per provider on the right.
 */
export class PeriodEditorPage {
  readonly page: Page;
  readonly heading: Locator;
  readonly backLink: Locator;
  readonly deletePeriodButton: Locator;
  readonly calculateButton: Locator;
  readonly settlementList: Locator;

  constructor(page: Page) {
    this.page = page;
    this.heading = page.getByRole('heading', { level: 1 });
    this.backLink = page.getByRole('link', { name: '← All periods' });
    this.deletePeriodButton = page.getByTestId('delete-period-button');
    this.calculateButton = page.getByTestId('calculate-button');
    this.settlementList = page.getByTestId('settlement-list');
  }

  async goto(periodId: number): Promise<void> {
    await this.page.goto(`/periods/${periodId}`);
    await expect(this.heading).toBeVisible();
  }

  /* --- per-provider row locators --- */

  entryRow(providerId: number): Locator {
    return this.page.getByTestId(`entry-row-${providerId}`);
  }

  entryProvider(providerId: number): Locator {
    return this.page.getByTestId(`entry-provider-${providerId}`);
  }

  entryProcedures(providerId: number): Locator {
    return this.page.getByTestId(`entry-procedures-${providerId}`);
  }

  entryCard(providerId: number): Locator {
    return this.page.getByTestId(`entry-card-${providerId}`);
  }

  entryCash(providerId: number): Locator {
    return this.page.getByTestId(`entry-cash-${providerId}`);
  }

  entryTips(providerId: number): Locator {
    return this.page.getByTestId(`entry-tips-${providerId}`);
  }

  entryAdjustment(providerId: number): Locator {
    return this.page.getByTestId(`entry-adj-${providerId}`);
  }

  entryNote(providerId: number): Locator {
    return this.page.getByTestId(`entry-note-${providerId}`);
  }

  entryRate(providerId: number): Locator {
    return this.page.getByTestId(`entry-rate-${providerId}`);
  }

  /* --- per-provider settlement card locators --- */

  settlementCard(providerId: number): Locator {
    return this.page.getByTestId(`settlement-card-${providerId}`);
  }

  settlementMessage(providerId: number): Locator {
    return this.page.getByTestId(`settlement-message-${providerId}`);
  }

  settlementCopyButton(providerId: number): Locator {
    return this.page.getByTestId(`settlement-copy-${providerId}`);
  }

  /* --- high-level actions --- */

  /**
   * Fill any subset of the row's fields. Each blur triggers a PUT upsert
   * on the backend; this method awaits between fields so the order of
   * writes is deterministic (helpful when assertions inspect later state).
   */
  async fillEntry(providerId: number, fields: EntryFields): Promise<void> {
    if (fields.procedures !== undefined) await this.fillAndBlur(this.entryProcedures(providerId), String(fields.procedures));
    if (fields.card !== undefined)       await this.fillAndBlur(this.entryCard(providerId), String(fields.card));
    if (fields.cash !== undefined)       await this.fillAndBlur(this.entryCash(providerId), String(fields.cash));
    if (fields.tips !== undefined)       await this.fillAndBlur(this.entryTips(providerId), String(fields.tips));
    if (fields.adj !== undefined)        await this.fillAndBlur(this.entryAdjustment(providerId), String(fields.adj));
    if (fields.note !== undefined)       await this.fillAndBlur(this.entryNote(providerId), fields.note);
    if (fields.ratePct !== undefined)    await this.fillAndBlur(this.entryRate(providerId), fields.ratePct);
  }

  async clickCalculate(): Promise<void> {
    await this.calculateButton.click();
    await expect(this.settlementList).toBeVisible();
  }

  /** Delete the whole period via the header button. Auto-accepts the confirm. */
  async deletePeriod(): Promise<void> {
    this.page.once('dialog', (d) => d.accept());
    await this.deletePeriodButton.click();
  }

  private async fillAndBlur(locator: Locator, value: string): Promise<void> {
    await locator.fill(value);
    await locator.blur();
  }
}
