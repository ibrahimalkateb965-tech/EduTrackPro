import { test } from '@e2e-dev/web';
import { expect } from 'e2e';

// Read-only: the live target's ledger is real. Never tap "تسجيل الدفعة" — a payment soft-delete
// does not reverse the ledger entry, and receipt_no is a global sequence (every test payment leaves a gap).
test('manager inspects payments ledger and payment dialog without writing', { session: 'admin', tags: ['finance', 'payments'] }, async ({ app, screen, browser }) => {
  await app.open('#/finance?tab=payments');
  await expect(screen.getByRole('heading', '💰 الإدارة المالية الشاملة')).toBeVisible();

  // Deep link selects the payments tab
  await expect(browser.locator('.tab-btn.active')).toHaveAttribute('data-tab', 'payments');

  // Ledger sections render (after reloadAll replaces the loading placeholder)
  await expect(screen.getByRole('heading', 'سجل الدفعات والمتحصلات')).toBeVisible({ timeout: 10_000 });
  await expect(screen.getByRole('heading', 'خطط الرسوم والأقساط للطلاب')).toBeVisible();
  const ledger = browser.locator('#finance-subview > .card:nth-child(2) table');
  await expect(ledger).toBeVisible();
  const ledgerBefore = await ledger.textContent();

  // Open the payment dialog
  const addPaymentBtn = screen.getByRole('button', '＋ تسجيل دفعة جديدة');
  await expect(addPaymentBtn).toBeVisible();
  await addPaymentBtn.tap();
  await expect(screen.getByRole('dialog')).toBeVisible();
  await expect(screen.getByRole('heading', 'تسجيل دفعة جديدة')).toBeVisible();

  // Form controls and their defaults
  await expect(browser.locator('.modal-panel select[name="student_id"]')).toHaveValue('');
  await expect(browser.locator('.modal-panel select[name="installment_id"]')).toBeVisible();
  await expect(browser.locator('.modal-panel input[name="amount"]')).toHaveValue('');
  await expect(browser.locator('.modal-panel select[name="method"]')).toHaveValue('كاش');
  await expect(browser.locator('.modal-panel input[name="paid_on"]')).toHaveValue(/^\d{4}-\d{2}-\d{2}$/);
  await expect(browser.locator('.modal-panel button[type="submit"]')).toBeVisible();

  // Close via cancel — no submit
  await browser.locator('.modal-panel .form-actions button.button-outline').tap();
  await expect(screen.getByRole('dialog')).not.toBeVisible();

  // Ledger untouched
  expect(await ledger.textContent()).toBe(ledgerBefore);
});
