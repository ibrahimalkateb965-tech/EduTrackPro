import { test } from '@e2e-dev/web';
import { expect } from 'e2e';

// Smoke test: no model calls, no backend needed. A fresh browser has no token,
// so the dashboard shows its sign-in screen.
test('dashboard opens on the sign-in screen', async ({ app, screen }) => {
  await app.open();
  await expect(screen.getByRole('heading', 'مركز غراس')).toBeVisible();
  await expect(screen.getByLabel('اسم المستخدم')).toBeVisible();
  await expect(screen.getByRole('button', 'تسجيل الدخول')).toBeVisible();
});
