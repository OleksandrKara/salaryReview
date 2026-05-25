import { expect, Locator, Page } from '@playwright/test';

type Half = 'FIRST' | 'SECOND';

/**
 * Page Object for `/` — the home page.
 *
 * Convention:
 *  - Public readonly locators describe stable elements
 *  - Methods that take parameters (e.g. provider id) are functions returning Locators
 *  - High-level actions are async methods returning Promise<void> (or void)
 *  - Assertions live in tests, NOT in page objects (keeps the POM reusable)
 */
export class HomePage {
  readonly page: Page;
  readonly title: Locator;
  readonly periodList: Locator;
  readonly manageProvidersLink: Locator;

  readonly createPeriodForm: {
    year: Locator;
    month: Locator;
    half: Locator;
    submit: Locator;
  };

  constructor(page: Page) {
    this.page = page;
    this.title = page.getByTestId('home-title');
    this.periodList = page.getByTestId('period-list');
    this.manageProvidersLink = page.getByTestId('nav-manage-providers');
    this.createPeriodForm = {
      year: page.getByTestId('create-period-year'),
      month: page.getByTestId('create-period-month'),
      half: page.getByTestId('create-period-half'),
      submit: page.getByTestId('create-period-submit'),
    };
  }

  async goto(): Promise<void> {
    await this.page.goto('/');
    await expect(this.title).toBeVisible();
  }

  /** Locator for a given pay-period row in the list. */
  periodRow(id: number): Locator {
    return this.page.getByTestId(`period-row-${id}`);
  }

  periodLink(id: number): Locator {
    return this.page.getByTestId(`period-link-${id}`);
  }

  periodDeleteButton(id: number): Locator {
    return this.page.getByTestId(`period-delete-${id}`);
  }

  /** Fill the create-period form and submit. Does NOT assert navigation. */
  async createPeriod(year: number, month: number, half: Half): Promise<void> {
    await this.createPeriodForm.year.fill(String(year));
    await this.createPeriodForm.month.fill(String(month));
    await this.createPeriodForm.half.selectOption(half);
    await this.createPeriodForm.submit.click();
  }

  /**
   * Delete a period via its row's ✕ button. Auto-accepts the confirm dialog
   * that pops up. Caller asserts the row disappears.
   */
  async deletePeriod(id: number): Promise<void> {
    this.page.once('dialog', (d) => d.accept());
    await this.periodDeleteButton(id).click();
  }
}
