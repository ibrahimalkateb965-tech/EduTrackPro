import { test } from '@e2e-dev/web';
import { expect, credentials } from 'e2e';

// Signs the manager in once and saves the session for the flow tests.
// Needs APP_URL (a deployment with /api/v1) and E2E_USER_ADMIN_USERNAME / E2E_USER_ADMIN_PASSWORD.
test.setup('sign in as the center manager', { sessions: ['admin'] }, async ({ app, screen, session, browser }) => {
  const admin = credentials.user('admin');
  await app.open();
  await screen.getByLabel('اسم المستخدم').fill(admin.username);
  await screen.getByLabel('كلمة المرور').fill(admin.password);
  await screen.getByRole('button', 'تسجيل الدخول').tap();
  await expect(browser).toHaveURL(/#\/home$/);
  await expect(screen.getByRole('button', 'تسجيل الخروج')).toBeVisible();
  await session.save('admin');
});
