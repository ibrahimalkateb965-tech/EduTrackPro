import { test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { apiCall } from './support/fixture-student.ts';

// M2: read-only. The rooms directory and the schedule room picker must mirror /api/v1/rooms.
test('manager inspects rooms directory and schedules view', { session: 'admin', tags: ['rooms'] }, async ({ app, screen, browser }) => {
  await app.open('#/rooms');

  await expect(screen.getByRole('heading', 'القاعات')).toBeVisible();
  await expect(screen.getByRole('heading', 'الجدول الدراسي')).toBeVisible();
  await expect(screen.getByRole('button', 'إضافة قاعة')).toBeVisible();
  await expect(screen.getByRole('button', 'إضافة حصة')).toBeVisible();

  const listed = await apiCall(browser, 'GET', 'rooms?limit=500');
  expect(listed.status).toBe(200);
  const rooms: any[] = listed.data?.items ?? listed.data ?? [];

  // First panel = rooms directory, second = schedule of the selected room.
  const roomsRows = browser.locator('#view > section:nth-of-type(1) table tbody tr');
  const roomSelect = screen.getByLabel('القاعة');
  await expect(roomSelect).toBeVisible();
  if (rooms.length) {
    await expect(roomsRows).toHaveCount(rooms.length);
    await expect(roomsRows.first()).toContainText(rooms[0].name);
    await expect(browser.locator('select[aria-label="القاعة"] option')).toHaveCount(rooms.length);
    await expect(roomSelect).toHaveValue(String(rooms[0].id));
  } else {
    await expect(roomsRows).toHaveText(['لا توجد قاعات بعد']);
  }
  await expect(browser.locator('#view > section:nth-of-type(2) table')).toBeVisible();
});
