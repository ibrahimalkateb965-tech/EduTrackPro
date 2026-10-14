import { test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { apiCall, createFixtureStudent, markRowsStale, SENTINEL_DATE, EVALUATION_BUCKET } from './support/fixture-student.ts';

// H2: daily-evaluation round trip on a throwaway student, isolated by filter, on a sentinel past date.
// Cleanup soft-deletes the evaluation rows first: the list endpoint hides rows of deleted students.
test('manager records and verifies daily student evaluation round trip', { session: 'admin', tags: ['evaluation'] }, async ({ app, screen, browser }) => {
  await app.open('#/home');
  await expect(screen.getByRole('button', 'تسجيل الخروج')).toBeVisible();
  const student = await createFixtureStudent(browser, EVALUATION_BUCKET, 'طالب اختبار التقييم');

  try {
    const showFixture = async () => {
      await expect(screen.getByRole('heading', 'التقييم اليومي للطلاب')).toBeVisible();
      await expect(screen.getByLabel(`درجة ${student.name}`)).toBeVisible();
      await expect(screen.getByLabel('المادة')).toHaveValue('القرآن');
      await screen.getByLabel('تصفية بالمجموعة').selectOption({ value: EVALUATION_BUCKET.group });
      await screen.getByLabel('تصفية بالقسم').selectOption({ value: EVALUATION_BUCKET.gender });
      await markRowsStale(browser);
      const loaded = browser.waitForResponse(new RegExp(`/api/v1/evaluations/daily\\?date=${SENTINEL_DATE}`));
      await screen.getByLabel('التاريخ').fill(SENTINEL_DATE);
      expect((await loaded).status).toBe(200);
      await expect(browser.locator('tbody tr[data-stale]')).toHaveCount(0);
      // Isolation: the save posts exactly the rows shown.
      await expect(browser.locator('tbody tr')).toHaveCount(1);
      return screen.getByLabel(`درجة ${student.name}`);
    };

    await app.open('#/attendance?tab=evaluation');
    const score = await showFixture();
    await expect(score).toHaveValue('10');
    await score.fill('7.5');

    const saved = browser.waitForResponse('**/api/v1/evaluations/daily');
    await screen.getByRole('button', '💾 حفظ التقييم اليومي').tap();
    const response = await saved;
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.saved).toBe(1);
    expect(body.items[0]).toMatchObject({ student_id: student.id, subject: 'القرآن', eval_type: 'daily' });
    expect(Number(body.items[0].value)).toBe(7.5);

    // A full reload: hash-only hops race the router and can leave the old view on screen.
    await browser.reload();
    const reloaded = await showFixture();
    await expect(reloaded).toHaveValue('7.5');
  } finally {
    const list = await apiCall(browser, 'GET', `evaluations/daily?date=${SENTINEL_DATE}`);
    for (const row of list.data?.items ?? []) {
      if (String(row.student_id) === student.id) await apiCall(browser, 'DELETE', `evaluations/${row.id}`);
    }
    await apiCall(browser, 'DELETE', `students/${student.id}`);
  }
});
