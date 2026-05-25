import { expect, Locator, Page } from '@playwright/test';

export interface NewProviderInput {
  name: string;
  displayName: string;
  ratePct: number | string;
  feePct: number | string;
}

/**
 * Page Object for `/providers` — the provider admin page.
 *
 * The existing rows render display name + full name inside `<input>` elements
 * (so they're editable in place). To assert "row for X exists", query the
 * input by its `value` attribute via `displayNameInput(displayName)`.
 */
export class ProvidersPage {
  readonly page: Page;
  readonly backLink: Locator;
  readonly heading: Locator;

  readonly addProviderForm: {
    name: Locator;
    displayName: Locator;
    rate: Locator;
    fee: Locator;
    submit: Locator;
  };

  constructor(page: Page) {
    this.page = page;
    this.heading = page.getByRole('heading', { name: 'Providers', exact: true });
    this.backLink = page.getByRole('link', { name: '← Home' });
    this.addProviderForm = {
      name: page.getByTestId('add-provider-name'),
      displayName: page.getByTestId('add-provider-display'),
      rate: page.getByTestId('add-provider-rate'),
      fee: page.getByTestId('add-provider-fee'),
      submit: page.getByTestId('add-provider-submit'),
    };
  }

  async goto(): Promise<void> {
    await this.page.goto('/providers');
    await expect(this.heading).toBeVisible();
  }

  /**
   * The row's display-name input. Use to assert a freshly-added provider
   * is visible: `await expect(providersPage.displayNameInput('UI_xyz')).toBeVisible();`
   */
  displayNameInput(displayName: string): Locator {
    return this.page.locator(`input[value="${displayName}"]`);
  }

  async addProvider(input: NewProviderInput): Promise<void> {
    await this.addProviderForm.name.fill(input.name);
    await this.addProviderForm.displayName.fill(input.displayName);
    await this.addProviderForm.rate.fill(String(input.ratePct));
    await this.addProviderForm.fee.fill(String(input.feePct));
    await this.addProviderForm.submit.click();
  }
}
