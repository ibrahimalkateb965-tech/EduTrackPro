import { test } from '@e2e-dev/web';
import { expect } from 'e2e';

test('manager has full access to all sidebar navigation items', { session: 'admin', tags: ['rbac', 'security'] }, async ({ app, screen, browser }) => {
  await app.open('#/home');
  await expect(browser).toHaveURL(/#\/home$/);

  // Manager must see all primary navigation links in sidebar
  const sidebar = browser.locator('#sidebar');
  await expect(sidebar).toBeVisible();

  const expectedRoutes = [
    'الرئيسية',
    'الطلاب',
    'الحضور',
    'مركز التواصل',
    'الإدارة المالية',
    'الموظفون',
    'القاعات',
    'التقارير',
    'الإعدادات',
  ];

  for (const label of expectedRoutes) {
    const link = sidebar.getByRole('link', { name: label });
    await expect(link).toBeVisible();
  }

  // Logout button must be present in user menu
  const logoutBtn = browser.locator('#logout-button');
  await expect(logoutBtn).toBeVisible();
});

// Frontend gating only: /me is stubbed to the default supervisor profile the staff form grants,
// while every other call still runs with the manager token. Server-side 403s need a real supervisor login.
test('supervisor sidebar hides manager-only sections and the router bounces direct links', { session: 'admin', tags: ['rbac', 'security'] }, async ({ app, browser }) => {
  await browser.route(/\/api\/v1\/me$/, route => route.fulfill({
    status: 200,
    json: {
      id: '00000000-0000-0000-0000-00000000e2e5', username: 'e2e_supervisor', name: 'مشرف اختبار', role: 'supervisor',
      permissions: { students: true, attendance: true, daily_evaluation: true, monthly_evaluation: true, finance: false },
    },
  }));
  await app.open('#/home');
  // Full reload: the dashboard caches /me per page load.
  await browser.reload();

  const sidebar = browser.locator('#sidebar');
  await expect(browser.locator('#user-name')).toHaveText('مشرف اختبار');
  for (const label of ['الرئيسية', 'الطلاب', 'الحضور', 'مركز التواصل', 'القاعات', 'الإعدادات']) {
    await expect(sidebar.getByRole('link', { name: label })).toBeVisible();
  }
  for (const route of ['finance', 'staff', 'reports']) {
    await expect(browser.locator(`#sidebar a[data-route="${route}"]`)).toBeHidden();
  }

  for (const route of ['staff', 'reports', 'finance']) {
    await app.open(`#/${route}`);
    await expect(browser).toHaveURL(/#\/home$/);
  }
});
