import { test } from '@e2e-dev/web';
import { expect } from 'e2e';

test('manager records center expense and confirms ledger registration', { session: 'admin', tags: ['finance', 'expenses'] }, async ({ app, screen }) => {
  const expenseDesc = `مصروف اختبار ${Date.now()}`;
  await app.open('#/finance?tab=expenses');
  await expect(screen.getByRole('heading', 'المصروفات')).toBeVisible();

  const addBtn = screen.getByRole('button', '➕ تسجيل مصروف');
  await expect(addBtn).toBeVisible();
  await addBtn.tap();

  const descInput = screen.getByLabel('الوصف / البيان');
  await descInput.fill(expenseDesc);

  const amountInput = screen.getByLabel('المبلغ (ر.س)');
  await amountInput.fill('1.00');

  const submitBtn = screen.getByRole('button', 'حفظ المصروف');
  await submitBtn.tap();

  await expect(screen.getByText(expenseDesc)).toBeVisible({ timeout: 10_000 });
});
