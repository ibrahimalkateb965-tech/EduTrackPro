import { test } from '@e2e-dev/web';
import { expect } from 'e2e';

// M3: read-only. Never press the send / save buttons: they POST /messages and open WhatsApp to a real guardian.
test('manager inspects communication center tabs and composer preview', { session: 'admin', tags: ['communication'] }, async ({ app, screen, browser }) => {
  await app.open('#/communication');
  await expect(screen.getByRole('heading', '💬 مركز التواصل مع أولياء الأمور')).toBeVisible();

  // KPI cards
  await expect(screen.getByText('إجمالي الطلاب')).toBeVisible();
  await expect(screen.getByText('أرقام هواتف مسجلة')).toBeVisible();
  await expect(screen.getByText('إجمالي الرسائل بالسجل')).toBeVisible();
  const studentCount = Number(await browser.locator('.kpis .kpi:nth-child(1) strong').textContent());
  const messageCount = Number(await browser.locator('#comm-stat-sent').textContent());

  // Tabs carry the same counts as the KPIs
  const composerTab = browser.locator('.tabs-bar .tab-btn[data-tab="composer"]');
  const historyTab = browser.locator('.tabs-bar .tab-btn[data-tab="history"]');
  const directoryTab = browser.locator('.tabs-bar .tab-btn[data-tab="directory"]');
  const activeTab = browser.locator('.tabs-bar .tab-btn.active');
  await expect(composerTab).toContainText('إرسال رسالة جديدة');
  await expect(historyTab).toContainText(`سجل الرسائل (${messageCount})`);
  await expect(directoryTab).toContainText(`دليل هواتف أولياء الأمور (${studentCount})`);
  await expect(activeTab).toHaveAttribute('data-tab', 'composer');

  const rows = browser.locator('.tab-content table tbody tr');

  // Directory: one row per student
  await directoryTab.tap();
  await expect(activeTab).toHaveAttribute('data-tab', 'directory');
  await expect(screen.getByRole('columnheader', 'رقم الجوال')).toBeVisible();
  await expect(screen.getByRole('columnheader', 'حالة الرقم')).toBeVisible();
  if (studentCount) await expect(rows).toHaveCount(studentCount);
  else await expect(rows).toHaveText(['لا توجد بيانات مطابقة للبحث']);

  // History: one row per logged message
  await historyTab.tap();
  await expect(activeTab).toHaveAttribute('data-tab', 'history');
  await expect(screen.getByRole('columnheader', 'القناة')).toBeVisible();
  await expect(screen.getByRole('columnheader', 'نص الرسالة')).toBeVisible();
  if (messageCount) await expect(rows).toHaveCount(messageCount);
  else await expect(rows).toHaveText(['لا توجد رسائل مسجلة مطابقة للبحث']);

  // Composer: empty state until a student is chosen
  await composerTab.tap();
  await expect(activeTab).toHaveAttribute('data-tab', 'composer');
  await expect(screen.getByRole('heading', '1. تحديد الطالب ونوع الرسالة')).toBeVisible();
  await expect(screen.getByRole('heading', '2. صياغة الرسالة والمعاينة')).toBeVisible();
  await expect(screen.getByText('اختر طالباً لعرض بيانات ولي الأمر والملخص المالي والتربوي')).toBeVisible();
  await expect(screen.getByRole('button', '🟢 إرسال وتوثيق عبر واتساب')).toBeVisible();
  await expect(screen.getByRole('button', '💾 حفظ في السجل فقط')).toBeVisible();

  // Preview roundtrip (client-side only): directory "مراسلة" preselects the student and builds the template text
  if (!studentCount) return;
  await directoryTab.tap();
  const firstName = (await browser.locator('.tab-content table tbody tr:nth-child(1) td:nth-child(1)').textContent())!.trim();
  await browser.locator('.tab-content table tbody tr:nth-child(1) button').tap();
  await expect(activeTab).toHaveAttribute('data-tab', 'composer');
  await expect(screen.getByText('اختر طالباً لعرض بيانات ولي الأمر والملخص المالي والتربوي')).toBeHidden();
  await expect(screen.getByText('الجوال:')).toBeVisible();
  const preview = browser.locator('.tab-content textarea');
  await expect(preview).toHaveValue(new RegExp(`ولي أمر الطالب/ة ${firstName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} المحترم`));
});
