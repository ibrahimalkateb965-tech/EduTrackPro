import { expect } from 'e2e';

// The attendance and daily-evaluation pages save one row for EVERY student in the current
// filter (unmarked students are saved as حاضر / 10), and an absence pushes an urgent
// notification to the guardian. On the shared deployment the round-trip tests therefore
// run against a throwaway student in a filter bucket (group + gender) no real student occupies.
// Each test file gets its own bucket because the runner runs files in parallel.
export const SENTINEL_DATE = '2020-01-15';
export type Bucket = { group: string; gender: string };
export const ATTENDANCE_BUCKET: Bucket = { group: 'القدرات', gender: 'بنات' };
export const EVALUATION_BUCKET: Bucket = { group: 'القدرات', gender: 'بنين' };
// Non-routable test guardian (see students.e2e.ts): never attaches the student to a real parent.
const GUARDIAN_PHONE = '0001112223';

type Browser = { evaluate: (fn: any, arg?: any) => Promise<any> };

// Calls /api/v1 from the page with the dashboard's own session token.
export async function apiCall(browser: Browser, method: string, path: string, body: unknown = null) {
  return browser.evaluate(async ([method, path, body]: [string, string, unknown]) => {
    const token = sessionStorage.getItem('gheras_token') || localStorage.getItem('gheras_token');
    const res = await fetch(`/api/v1/${path}`, {
      method,
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: body === null ? undefined : JSON.stringify(body),
    });
    const data = res.status === 204 ? null : await res.json().catch(() => null);
    return { status: res.status, data };
  }, [method, path, body]);
}

// Fails fast if a real student has moved into the fixture bucket: saving would overwrite their record.
export async function createFixtureStudent(browser: Browser, bucket: Bucket, label: string) {
  const list = await apiCall(browser, 'GET', 'students?limit=500');
  const occupants = (list.data?.items ?? list.data ?? []).filter((s: any) =>
    (s.status || 'active') === 'active' && s.group_name === bucket.group && s.gender === bucket.gender);
  expect(occupants.map((s: any) => s.name)).toEqual([]);

  const name = `${label} ${Date.now()}`;
  const created = await apiCall(browser, 'POST', 'students', {
    name, gender: bucket.gender, group_name: bucket.group, guardian_phone: GUARDIAN_PHONE, guardian_relation: 'الأب',
  });
  expect(created.status).toBeLessThan(300);
  return { id: String(created.data.id), name };
}

// Re-renders the table, so callers wait until rows painted before the change are gone.
export async function markRowsStale(browser: Browser) {
  await browser.evaluate(() => document.querySelectorAll('tbody tr').forEach(tr => tr.setAttribute('data-stale', '')));
}
