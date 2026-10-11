import { test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { apiCall, createFixtureStudent, markRowsStale, SENTINEL_DATE, ATTENDANCE_BUCKET } from './support/fixture-student.ts';

// H1: attendance round trip on a throwaway student, isolated by filter, on a sentinel past date.
// No endpoint deletes attendance, so one row for the (soft-deleted) fixture student stays on SENTINEL_DATE.
// Uses متأخر rather than غائب: an absence notifies the guardian.
test('manager records student attendance and verifies persistence on reload', { session: 'admin', tags: ['attendance'] }, async ({ app, screen, browser }) => {
  await app.open('#/home');
  await expect(screen.getByRole('button', 'تسجيل الخروج')).toBeVisible();
  const student = await createFixtureStudent(browser, ATTENDANCE_BUCKET, 'طالب اختبار الحضور');

  try {
    const showFixture = async () => {
      await expect(screen.getByRole('heading', 'الحضور والغياب')).toBeVisible();
      await expect(screen.getByLabel(`حالة ${student.name}`)).toBeVisible();
      await screen.getByLabel('تصفية بالمجموعة').selectOption({ value: ATTENDANCE_BUCKET.group });
      await screen.getByLabel('تصفية بالقسم').selectOption({ value: ATTENDANCE_BUCKET.gender });
      await markRowsStale(browser);
      const loaded = browser.waitForResponse(new RegExp(`/api/v1/reports/attendance\\?from=${SENTINEL_DATE}`));
      await screen.getByLabel('التاريخ').fill(SENTINEL_DATE);
      expect((await loaded).status).toBe(200);
      await expect(browser.locator('tbody tr[data-stale]')).toHaveCount(0);
      // Isolation: the save posts exactly the rows shown.
      await expect(browser.locator('tbody tr')).toHaveCount(1);
      return screen.getByLabel(`حالة ${student.name}`);
    };

    await app.open('#/attendance');
    const status = await showFixture();
    await expect(status).toHaveValue('حاضر');
    await status.selectOption('متأخر');

    const saved = browser.waitForResponse('**/api/v1/attendance/students');
    await screen.getByRole('button', '💾 حفظ الحضور').tap();
    const response = await saved;
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.saved).toBe(1);
    expect(body.items[0]).toMatchObject({ student_id: student.id, status: 'متأخر' });

    // A full reload: hash-only hops race the router and can leave the old view on screen.
    await browser.reload();
    const reloaded = await showFixture();
    await expect(reloaded).toHaveValue('متأخر');
  } finally {
    await apiCall(browser, 'DELETE', `students/${student.id}`);
  }
});
