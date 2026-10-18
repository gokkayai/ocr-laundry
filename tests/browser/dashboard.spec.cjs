const { test, expect } = require('@playwright/test');

for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
  test(`dashboard and benchmark at ${viewport.width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto('/');
    await expect(page.locator('#health-text')).toHaveText('OCR ready');
    await page.locator('#document-select').selectOption('small');
    const submittedResponse = page.waitForResponse(response => response.url().endsWith('/api/jobs')
      && response.request().method() === 'POST');
    await page.locator('#submit-button').click();
    const submitted = await (await submittedResponse).json();
    const card = page.locator('.job-card').filter({ hasText: submitted.id.slice(0, 8) });
    await expect(card).toHaveClass(/completed/, { timeout: 30000 });
    await expect(card.locator('.job-preview pre')).toContainText('OCR LAUNDRY');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath('dashboard.png'), fullPage: true, animations: 'disabled' });
    await page.goto('/benchmark');
    await expect(page.locator('#benchmark-finding')).toContainText('Estimated SJF mean latency was');
    await expect(page.locator('#observations')).toHaveText('36');
    await expect(page.locator('.benchmark-plot')).toBeVisible();
    expect(await page.locator('.benchmark-plot').evaluate(img => img.complete && img.naturalWidth > 0)).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath('benchmark.png'), fullPage: true });
    expect(errors).toEqual([]);
  });
}

test('headlines follow changed data, regressions, failures and unavailable data', async ({ page }) => {
  const header = 'policy,workers,successful_jobs,failed_jobs,mean_completion_ms\n';
  let csv = header + 'FIFO,1,10,0,100\nESTIMATED_SJF,1,10,0,150\n';
  await page.route('**/benchmark/summary.csv', route => route.fulfill({ body: csv, contentType: 'text/csv' }));
  await page.goto('/benchmark');
  await expect(page.locator('#benchmark-finding')).toContainText('50.0% higher');
  await expect(page.locator('#observations')).toHaveText('20');
  csv = header + 'FIFO,1,10,0,100\nESTIMATED_SJF,1,9,1,50\n';
  await page.reload();
  await expect(page.locator('#benchmark-finding')).toContainText('no headline comparison');
  await expect(page.locator('#failures')).toHaveText('1');
  csv = 'invalid';
  await page.reload();
  await expect(page.locator('#benchmark-finding')).toContainText('Results unavailable');
  await expect(page.locator('#observations')).toHaveText('—');
});
