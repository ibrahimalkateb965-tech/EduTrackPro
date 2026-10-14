import { test } from '@e2e-dev/web';
import { expect } from 'e2e';

// Core flow: the manager registers a new student and finds them in the list.
// Writes a real student record — point APP_URL at a staging deployment, not production.
test('manager registers a new student', { session: 'admin', tags: ['core'] }, async ({ app, agent, screen }) => {
  const studentName = `طالب اختبار ${Date.now()}`;
  // Fixed non-routable number: the API links students to an existing guardian by exact phone,
  // so a random 05xxxxxxxx could attach the test student to a real parent.
  const guardianPhone = '0001112223';

  await app.open('#/students');
  await expect(screen.getByRole('searchbox', 'بحث في الطلاب')).toBeVisible();

  await agent.act(
    'open the add-student form (➕ إضافة طالب), enter student name {name}, guardian phone {phone}, choose study group {group}, then submit with إضافة طالب and stop',
    { params: { name: studentName, phone: guardianPhone, group: 'الصباح' } },
  );
  // The success toast lives 3.5s (ui.js toast), shorter than the agent's post-submit model calls,
  // so assert the persisted outcome instead: the new row in the list.
  await screen.getByRole('searchbox', 'بحث في الطلاب').fill(studentName);
  const row = screen.getByRole('row').filter({ hasText: studentName });
  await expect(row).toContainText(guardianPhone);
  await expect(row).toContainText('الصباح');
});
