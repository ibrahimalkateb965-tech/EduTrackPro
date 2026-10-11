import { test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { apiCall } from './support/fixture-student.ts';

// M1: creates one throwaway staff row (no login account, salary 0) and soft-deletes it in finally.
// Staff creation has no ledger/payroll side effects; the audit-log rows stay by design.
test('manager adds staff member and verifies in staff directory', { session: 'admin', tags: ['staff'] }, async ({ app, screen, browser }) => {
  const staffName = `موظف اختبار ${Date.now()}`;
  // Non-routable test phone, never a real employee.
  const testPhone = '0002223334';

  await app.open('#/staff');
  const addButton = screen.getByRole('button', '➕ إضافة موظف');
  await expect(addButton).toBeVisible();
  await addButton.tap();

  const field = (selector: string) => browser.locator(`[role="dialog"] form ${selector}`);
  await expect(field('input[name="name"]')).toBeVisible();
  await field('input[name="name"]').fill(staffName);
  await field('input[name="role_title"]').fill('معلم تجريبي');
  await field('input[name="phone"]').fill(testPhone);
  // Required for managers (finance permission); 0 keeps payroll untouched.
  await field('input[name="base_salary"]').fill('0');

  // No login account: keeps the users table clean and avoids a phone-based username.
  const createAccount = field('#chk-create-account');
  await expect(createAccount).toBeChecked();
  await createAccount.uncheck();
  await expect(createAccount).not.toBeChecked();

  // Dashboard GETs always carry ?limit=, so the anchored pattern only matches the create POST.
  const created = browser.waitForResponse(/\/api\/v1\/staff$/);
  await field('button[type="submit"]').tap();
  const response = await created;
  expect(response.status).toBeLessThan(300);
  const staffId = String((await response.json()).id);
  expect(staffId).toMatch(/^[0-9a-f-]{36}$/);

  try {
    const row = browser.locator('tbody tr').filter({ hasText: staffName });
    await expect(row).toBeVisible({ timeout: 10_000 });
    await expect(row).toContainText('معلم تجريبي');
    await expect(row).toContainText(testPhone);

    const users = await apiCall(browser, 'GET', 'users?limit=500');
    expect((users.data?.items ?? []).filter((u: any) => String(u.staff_id) === staffId)).toEqual([]);
  } finally {
    const removed = await apiCall(browser, 'DELETE', `staff/${staffId}`);
    expect(removed.status).toBe(204);
  }

  // Teardown verified: soft-deleted row is gone from the API and the directory.
  expect((await apiCall(browser, 'GET', `staff/${staffId}`)).status).toBe(404);
  await browser.reload();
  await expect(screen.getByRole('button', '➕ إضافة موظف')).toBeVisible();
  await expect(browser.locator('tbody tr').first()).not.toContainText('جارٍ التحميل');
  await expect(browser.locator('tbody tr').filter({ hasText: staffName })).toHaveCount(0);
});
