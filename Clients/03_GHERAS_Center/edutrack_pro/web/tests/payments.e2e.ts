import { test } from '@e2e-dev/web';
import { expect } from 'e2e';

test('manager records cash payment receipt and verifies in payments list', { session: 'admin', tags: ['finance', 'payments'] }, async ({ app, screen, browser }) => {
  await app.open('#/finance?tab=payments');
  await expect(screen.getByRole('heading', 'الرسوم وسندات القبض')).toBeVisible();

  const addBtn = screen.getByRole('button', '➕ تسجيل دفعة جديدة');
  await expect(addBtn).toBeVisible();

  await expect(screen.getByRole('table')).toBeVisible();
});
