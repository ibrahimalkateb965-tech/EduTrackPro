import { test } from '@e2e-dev/web';
import { expect } from 'e2e';

// Read-only: the live target's ledger is real. Never tap "تسجيل المصروف" — an expense soft-delete
// does not reverse its ledger entry.
test('manager inspects expenses ledger and expense dialog without writing', { session: 'admin', tags: ['finance', 'expenses'] }, async ({ app, screen, browser }) => {
  await app.open('#/finance?tab=expenses');
  await expect(screen.getByRole('heading', '💰 الإدارة المالية الشاملة')).toBeVisible();

  // Deep link selects the expenses tab
  await expect(browser.locator('.tab-btn.active')).toHaveAttribute('data-tab', 'expenses');

  // Ledger section and category filter render
  await expect(screen.getByRole('heading', 'سجل المصروفات التشغيلية')).toBeVisible({ timeout: 10_000 });
  const filterSelect = browser.locator('#finance-subview .toolbar select');
  await expect(filterSelect).toHaveValue('');
  const ledger = browser.locator('#finance-subview table');
  await expect(ledger).toBeVisible();
  const ledgerBefore = await ledger.textContent();

  // Open the expense dialog
  const addExpenseBtn = screen.getByRole('button', '＋ تسجيل مصروف جديد');
  await expect(addExpenseBtn).toBeVisible();
  await addExpenseBtn.tap();
  await expect(screen.getByRole('dialog')).toBeVisible();
  await expect(screen.getByRole('heading', 'تسجيل مصروف جديد')).toBeVisible();

  // Form controls and their defaults (new expense: empty description/amount, today's date, cash)
  await expect(browser.locator('.modal-panel input[name="description"]')).toHaveValue('');
  await expect(browser.locator('.modal-panel select[name="category_id"]')).toHaveValue('');
  await expect(browser.locator('.modal-panel input[name="amount"]')).toHaveValue('');
  await expect(browser.locator('.modal-panel input[name="paid_on"]')).toHaveValue(/^\d{4}-\d{2}-\d{2}$/);
  await expect(browser.locator('.modal-panel select[name="method"]')).toHaveValue('كاش');
  await expect(browser.locator('.modal-panel select[name="account_id"]')).toHaveValue('');
  await expect(browser.locator('.modal-panel button[type="submit"]')).toBeVisible();

  // Close via cancel — no submit
  await browser.locator('.modal-panel .form-actions button.button-outline').tap();
  await expect(screen.getByRole('dialog')).not.toBeVisible();

  // Ledger untouched
  expect(await ledger.textContent()).toBe(ledgerBefore);
});
