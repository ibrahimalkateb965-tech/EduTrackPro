import { test } from '@e2e-dev/web';
import { expect } from 'e2e';

// Fleet order 15 (fleet_orders/15_PLAN.md, hook contract in §3.2). Hermetic: the live ledger is real and
// every test payment leaves a permanent receipt_no gap, so the student POST and the receipt API are stubbed
// with browser.route. Nothing in this file writes to the deployment.

const STUDENT_POST = /\/api\/v1\/students$/; // dashboard GETs always carry ?limit=, so this is the POST only
const RECEIPT_API = /\/api\/v1\/print\/receipt/;

async function openAddStudentForm(app: any, screen: any, browser: any) {
  await app.open('#/students');
  await expect(screen.getByRole('searchbox', 'بحث في الطلاب')).toBeVisible();
  await screen.getByRole('button', '➕ إضافة طالب').tap();
  await expect(browser.locator('.modal-panel input[name="name"]')).toBeVisible();
}

test('registration form exposes Tabby/Tamara, allergy and bus controls', { session: 'admin', tags: ['students', 'order15'] }, async ({ app, screen, browser }) => {
  await openAddStudentForm(app, screen, browser);

  // D1: canonical stored values, bilingual labels
  const methods = await browser.evaluate(() =>
    [...document.querySelectorAll('.modal-panel select[name="payment_method"] option')].map(o => [(o as HTMLOptionElement).value, o.textContent?.trim() ?? '']));
  const hasOption = (value: string, label: string) => methods.some(([v, l]) => v === value && l === label);
  expect(hasOption('تابي', 'تابي (Tabby)')).toBe(true);
  expect(hasOption('تمارا', 'تمارا (Tamara)')).toBe(true);

  // D2: notes appear only when the allergy flag is on
  const notes = browser.locator('.modal-panel textarea[name="allergy_notes"]');
  await expect(browser.locator('.modal-panel select[name="has_allergies"]')).toHaveValue('false');
  await expect(notes).not.toBeVisible();
  await browser.locator('.modal-panel select[name="has_allergies"]').selectOption({ value: 'true' });
  await expect(notes).toBeVisible();

  // D3: bus inputs appear with the flag and the remaining balance updates live
  const busFee = browser.locator('.modal-panel input[name="bus_fee"]');
  await expect(busFee).not.toBeVisible();
  await browser.locator('.modal-panel select[name="has_bus_subscription"]').selectOption({ value: 'true' });
  await expect(busFee).toBeVisible();
  await busFee.fill('500');
  await browser.locator('.modal-panel input[name="bus_paid"]').fill('200');
  await expect(browser.locator('#bus-remaining-preview')).toContainText('300.00');
});

test('next due date uses the RTL-safe picker and blocks past days', { session: 'admin', tags: ['students', 'order15'] }, async ({ app, screen, browser }) => {
  await openAddStudentForm(app, screen, browser);
  await browser.locator('.modal-panel input[name="registration_fee"]').fill('600');
  await browser.locator('.modal-panel input[name="initial_payment_amount"]').fill('200');

  // D4 (Solution B): no native date input left in the registration modal
  expect(await browser.evaluate(() => document.querySelectorAll('.modal-panel input[type="date"]').length)).toBe(0);
  await browser.locator('.g-datepicker-wrap[data-name="next_due_date"] .g-datepicker-input').tap();

  // Every day up to and including today in the opening month view is disabled (minDate = tomorrow)
  const enabledPastDays = await browser.evaluate(() => {
    const today = new Date().getDate();
    return [...document.querySelectorAll('.g-datepicker-wrap[data-name="next_due_date"] .g-cal-grid button.g-cal-day')]
      .filter(b => Number(b.textContent) <= today && !(b as HTMLButtonElement).disabled).length;
  });
  expect(enabledPastDays).toBe(0);

  // Pick the first selectable day; the hidden field must hold an ISO date after today
  const picked = await browser.evaluate(() => {
    const day = [...document.querySelectorAll('.g-datepicker-wrap[data-name="next_due_date"] .g-cal-grid button.g-cal-day')]
      .find(b => !(b as HTMLButtonElement).disabled) as HTMLButtonElement | undefined;
    day?.click();
    return (document.querySelector('.modal-panel input[name="next_due_date"]') as HTMLInputElement | null)?.value ?? '';
  });
  expect(picked).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  const todayIso = await browser.evaluate(() => new Date().toLocaleDateString('en-CA'));
  expect(picked > todayIso).toBe(true);
});

test('submission sends the new fields (stubbed POST, no live write)', { session: 'admin', tags: ['students', 'order15'] }, async ({ app, screen, browser }) => {
  let sent: any = null;
  await browser.route(STUDENT_POST, async route => {
    if (route.request.method !== 'POST') return route.fallback();
    sent = JSON.parse(route.request.postData ?? '{}');
    // No initial_payment in the stub, so the form does not redirect to a receipt
    await route.fulfill({ status: 200, json: { id: '00000000-0000-0000-0000-0000000000e2', name: sent.name } });
  });

  await openAddStudentForm(app, screen, browser);
  await browser.locator('.modal-panel input[name="name"]').fill(`طالب اختبار 15 ${Date.now()}`);
  await browser.locator('.modal-panel input[name="guardian_phone"]').fill('0001112223');
  await browser.locator('.modal-panel select[name="has_allergies"]').selectOption({ value: 'true' });
  await browser.locator('.modal-panel textarea[name="allergy_notes"]').fill('حساسية لاكتوز');
  await browser.locator('.modal-panel select[name="has_bus_subscription"]').selectOption({ value: 'true' });
  await browser.locator('.modal-panel input[name="bus_fee"]').fill('500');
  await browser.locator('.modal-panel input[name="bus_paid"]').fill('200');
  await browser.locator('.modal-panel input[name="registration_fee"]').fill('600');
  await browser.locator('.modal-panel input[name="initial_payment_amount"]').fill('600');
  await browser.locator('.modal-panel select[name="payment_method"]').selectOption({ value: 'تمارا' });
  await screen.getByRole('button', 'إضافة طالب').tap();

  for (let i = 0; i < 50 && sent === null; i++) await new Promise(r => setTimeout(r, 200));
  expect(sent).not.toBeNull();
  expect(sent.payment_method).toBe('تمارا');
  expect(sent.has_allergies).toBe(true);
  expect(sent.allergy_notes).toBe('حساسية لاكتوز');
  expect(sent.has_bus_subscription).toBe(true);
  expect(Number(sent.bus_fee)).toBe(500);
  expect(Number(sent.bus_paid)).toBe(200);
  // Fully paid registration: no plan terms are sent
  expect(sent.next_due_date).toBeUndefined();
});

const RECEIPT_FIXTURE = {
  template: 'receipt', center_name: 'مركز غراس', center_address: 'الرياض', center_phone: '0110000000',
  receipt_no: 4321, date: '2026-10-16', student_name: 'عبدالرحمن فهد', method: 'تابي',
  amount: 500, amount_words: 'خمسمائة ريال فقط', installment_ref: '—', registration_paid_amount: 350,
  total_fee: 600, remaining_balance: 250, has_installments: true, is_fully_paid: false,
  remaining_installments_count: 1, next_due_date: '2026-11-15', next_installment_amount: 250,
  interval_label: 'شهري (كل 30 يوم)', has_bus: true, bus_fee: 400, bus_paid_amount: 150, bus_remaining: 250,
  receiver_name: 'أمل المحاسبة', guardian_whatsapp: '966551234567',
};

test('receipt toolbar shares via WhatsApp Web and shows the receiver', { session: 'admin', tags: ['print', 'order15'] }, async ({ app, screen, browser }) => {
  await browser.route(RECEIPT_API, route => route.fulfill({ status: 200, json: RECEIPT_FIXTURE }));
  await app.open('#/students');
  await expect(screen.getByRole('searchbox', 'بحث في الطلاب')).toBeVisible();
  await browser.goto(new URL('../print/templates/receipt.html?payment=e2e-stub', await browser.url()).href);

  // D6: receiver name under المستلم; D3: bus rows rendered
  await expect(browser.locator('.sig-receiver')).toHaveText('أمل المحاسبة');
  expect(await browser.evaluate(() => document.querySelectorAll('.receipt-bus-row').length)).toBeGreaterThan(0);

  // D5: Universal WhatsApp wa.me link matching communication.js
  const share = browser.locator('#gheras-whatsapp-action');
  await expect(share).toBeVisible();
  await expect(share).toHaveAttribute('target', '_blank');
  const href = await browser.evaluate(() => document.querySelector('#gheras-whatsapp-action')?.getAttribute('href') ?? '');
  expect(href.startsWith('https://wa.me/966551234567?text=')).toBe(true);
  expect(href.includes('whatsapp://')).toBe(false);
  const text = new URL(href).searchParams.get('text') ?? '';
  for (const part of ['مركز غراس', '4321', 'عبدالرحمن فهد', '500.00', '2026-10-16']) {
    expect(text).toContain(part);
  }
  expect(/[٠-٩]/.test(text)).toBe(false); // Rule 50
});

test('receipt without a valid guardian mobile has no share button', { session: 'admin', tags: ['print', 'order15'] }, async ({ app, screen, browser }) => {
  await browser.route(RECEIPT_API, route => route.fulfill({ status: 200, json: { ...RECEIPT_FIXTURE, guardian_whatsapp: '' } }));
  await app.open('#/students');
  await expect(screen.getByRole('searchbox', 'بحث في الطلاب')).toBeVisible();
  await browser.goto(new URL('../print/templates/receipt.html?payment=e2e-stub', await browser.url()).href);
  await expect(browser.locator('.sig-receiver')).toBeVisible();
  expect(await browser.evaluate(() => document.querySelectorAll('#gheras-whatsapp-action').length)).toBe(0);
});

// ---- Granular per-control coverage (one control or interaction per test) ----

const modal = (css: string) => `.modal-panel ${css}`;
const DUE_PICKER = '.g-datepicker-wrap[data-name="next_due_date"]';

test('A1 registration payment_method lists and selects Tabby, then Tamara', { session: 'admin', tags: ['students', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  await openAddStudentForm(app, screen, browser);
  const method = browser.locator(modal('select[name="payment_method"]'));
  await expect(method).toBeVisible();
  await expect(browser.locator(modal('select[name="payment_method"] option[value="تابي"]'))).toHaveText('تابي (Tabby)');
  await expect(browser.locator(modal('select[name="payment_method"] option[value="تمارا"]'))).toHaveText('تمارا (Tamara)');

  await method.selectOption({ value: 'تابي' });
  await expect(method).toHaveValue('تابي');
  await method.selectOption({ value: 'تمارا' });
  await expect(method).toHaveValue('تمارا');
});

test('A2 finance payment dialog method lists and selects Tabby and Tamara (read-only)', { session: 'admin', tags: ['finance', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  // #/payments redirects here; views/payments.js is not routed, so this is the live payments form
  await app.open('#/finance?tab=payments');
  await screen.getByRole('button', '＋ تسجيل دفعة جديدة').tap();
  await expect(screen.getByRole('heading', 'تسجيل دفعة جديدة')).toBeVisible();
  const method = browser.locator(modal('select[name="method"]'));
  await expect(method).toHaveValue('كاش');
  await expect(browser.locator(modal('select[name="method"] option[value="تابي"]'))).toHaveCount(1);
  await expect(browser.locator(modal('select[name="method"] option[value="تمارا"]'))).toHaveCount(1);

  await method.selectOption({ value: 'تابي' });
  await expect(method).toHaveValue('تابي');
  await method.selectOption({ value: 'تمارا' });
  await expect(method).toHaveValue('تمارا');

  // Never submit: cancel leaves the ledger untouched
  await browser.locator(modal('.form-actions button.button-outline')).tap();
  await expect(screen.getByRole('dialog')).not.toBeVisible();
});

test('B1 has_allergies on shows allergy_notes, notes keep typed text, off hides them', { session: 'admin', tags: ['students', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  await openAddStudentForm(app, screen, browser);
  const flag = browser.locator(modal('select[name="has_allergies"]'));
  const notes = browser.locator(modal('textarea[name="allergy_notes"]'));
  await expect(flag).toHaveValue('false');
  await expect(notes).toBeHidden();

  await flag.selectOption({ value: 'true' });
  await expect(flag).toHaveValue('true');
  await expect(notes).toBeVisible();

  await notes.fill('حساسية فول سوداني وبنسلين');
  await expect(notes).toHaveValue('حساسية فول سوداني وبنسلين');

  await flag.selectOption({ value: 'false' });
  await expect(notes).toBeHidden();
});

test('B2 has_allergies on with blank notes is rejected and the form stays open', { session: 'admin', tags: ['students', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  // The stub mirrors the server contract (crud.py create: 400 validation_error, covered server-side by
  // test_allergy_flag_without_notes_rejected), so a regression can never create a student on a live target.
  let sent: any = null;
  await browser.route(STUDENT_POST, async route => {
    if (route.request.method !== 'POST') return route.fallback();
    sent = JSON.parse(route.request.postData ?? '{}');
    await route.fulfill({ status: 400, json: { error: { code: 'validation_error', message: 'يرجى توضيح نوع الحساسية (غذائية أو دوائية)' } } });
  });

  await openAddStudentForm(app, screen, browser);
  await browser.locator(modal('input[name="name"]')).fill(`طالب اختبار 15 ${Date.now()}`);
  await browser.locator(modal('input[name="guardian_phone"]')).fill('0001112223');
  await browser.locator(modal('select[name="has_allergies"]')).selectOption({ value: 'true' });
  await browser.locator(modal('textarea[name="allergy_notes"]')).fill('   ');
  await screen.getByRole('button', 'إضافة طالب').tap();

  await expect(browser.locator('.toast.error')).toHaveText('يرجى توضيح نوع الحساسية (غذائية أو دوائية)');
  expect(sent).not.toBeNull();
  expect(sent.has_allergies).toBe(true);
  expect(sent.allergy_notes).toBe(''); // whitespace is trimmed before sending
  await expect(browser.locator(modal('input[name="name"]'))).toBeVisible();
  await expect(screen.getByRole('button', 'إضافة طالب')).toBeEnabled(); // re-enabled, label restored
});

test('C1 has_bus_subscription on shows bus_fee, bus_paid and the remaining preview', { session: 'admin', tags: ['students', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  await openAddStudentForm(app, screen, browser);
  const flag = browser.locator(modal('select[name="has_bus_subscription"]'));
  const fee = browser.locator(modal('input[name="bus_fee"]'));
  const paid = browser.locator(modal('input[name="bus_paid"]'));
  const preview = browser.locator('#bus-remaining-preview');
  await expect(flag).toHaveValue('false');
  for (const node of [fee, paid, preview]) await expect(node).toBeHidden();

  await flag.selectOption({ value: 'true' });
  for (const node of [fee, paid, preview]) await expect(node).toBeVisible();
  await expect(preview).toHaveText('0.00 ر.س');
});

test('C2 bus_fee and bus_paid inputs drive the live remaining preview', { session: 'admin', tags: ['students', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  await openAddStudentForm(app, screen, browser);
  await browser.locator(modal('select[name="has_bus_subscription"]')).selectOption({ value: 'true' });
  const fee = browser.locator(modal('input[name="bus_fee"]'));
  const paid = browser.locator(modal('input[name="bus_paid"]'));
  const preview = browser.locator('#bus-remaining-preview');

  await fee.fill('800'); // fill dispatches the input event the preview listens to
  await expect(fee).toHaveValue('800');
  await expect(preview).toHaveText('800.00 ر.س');

  await paid.fill('350');
  await expect(paid).toHaveValue('350');
  await expect(preview).toHaveText('450.00 ر.س');

  await paid.fill('200');
  await expect(preview).toHaveText('600.00 ر.س');

  // Overpayment never shows a negative balance
  await paid.fill('900');
  await expect(preview).toHaveText('0.00 ر.س');

  // Turning the subscription off clears both inputs and resets the preview
  await browser.locator(modal('select[name="has_bus_subscription"]')).selectOption({ value: 'false' });
  await expect(fee).toHaveValue('');
  await expect(paid).toHaveValue('');
  await expect(preview).toHaveText('0.00 ر.س');
});

test('D1 next_due_date picker opens a calendar grid of day buttons with past days disabled', { session: 'admin', tags: ['students', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  await openAddStudentForm(app, screen, browser);
  await browser.locator(modal('input[name="registration_fee"]')).fill('600');
  await browser.locator(modal('input[name="initial_payment_amount"]')).fill('200');

  const grid = browser.locator(`${DUE_PICKER} .g-cal-grid`);
  await expect(grid).toBeHidden();
  await browser.locator(`${DUE_PICKER} .g-datepicker-input`).tap();
  await expect(grid).toBeVisible();

  const days = browser.locator(`${DUE_PICKER} .g-cal-grid button.g-cal-day`);
  const daysInMonth = await browser.evaluate(() => { const n = new Date(); return new Date(n.getFullYear(), n.getMonth() + 1, 0).getDate(); });
  await expect(days).toHaveCount(daysInMonth);

  // minDate = tomorrow: day 1..today are disabled, tomorrow (if in this month) is enabled
  const today = await browser.evaluate(() => new Date().getDate());
  await expect(days.nth(0)).toBeDisabled();
  await expect(days.nth(today - 1)).toBeDisabled();
  if (today < daysInMonth) await expect(days.nth(today)).toBeEnabled();
  await expect(browser.locator(`${DUE_PICKER} .g-cal-grid button.g-cal-day:not([disabled])`)).toHaveCount(daysInMonth - today);
});

test('D2 tapping an enabled day writes an ISO date to the hidden next_due_date and closes the popup', { session: 'admin', tags: ['students', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  await openAddStudentForm(app, screen, browser);
  await browser.locator(modal('input[name="registration_fee"]')).fill('600');
  await browser.locator(modal('input[name="initial_payment_amount"]')).fill('200');
  const display = browser.locator(`${DUE_PICKER} .g-datepicker-input`);
  await display.tap();

  const enabled = browser.locator(`${DUE_PICKER} .g-cal-grid button.g-cal-day:not([disabled])`);
  if (await enabled.count() === 0) {
    // Last day of the month: tomorrow lives in the next month view
    await browser.locator(`${DUE_PICKER} .g-cal-nav-btn[title="الشهر التالي"]`).tap();
  }
  const day = enabled.first();
  const dayNum = Number(await day.textContent());
  await day.tap();

  const hidden = await browser.evaluate(() => (document.querySelector('.modal-panel input[type="hidden"][name="next_due_date"]') as HTMLInputElement | null)?.value ?? '');
  expect(hidden).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  expect(Number(hidden.slice(8))).toBe(dayNum);
  const todayIso = await browser.evaluate(() => new Date().toLocaleDateString('en-CA'));
  expect(hidden > todayIso).toBe(true);
  await expect(display).toHaveValue(new RegExp(`^${dayNum} \\S+ ${hidden.slice(0, 4)}$`)); // e.g. "17 أكتوبر 2026"
  await expect(browser.locator(`${DUE_PICKER} .g-cal-grid`)).toBeHidden();
});

test('D3 native date inputs render LTR; the registration modal has none left', { session: 'admin', tags: ['finance', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  await openAddStudentForm(app, screen, browser);
  await expect(browser.locator(modal('input[type="date"]'))).toHaveCount(0);
  // The custom picker shows Arabic month names, so it stays RTL; the stored value is ISO in a hidden field
  await expect(browser.locator(modal('input[type="hidden"][name="next_due_date"]'))).toHaveCount(1);
  await browser.locator(modal('.modal-close')).tap();

  await app.open('#/finance?tab=payments');
  await screen.getByRole('button', '＋ تسجيل دفعة جديدة').tap();
  await expect(browser.locator(modal('input[name="paid_on"]'))).toBeVisible();
  const style = await browser.evaluate(() => {
    const cs = getComputedStyle(document.querySelector('.modal-panel input[name="paid_on"]') as HTMLInputElement);
    return { direction: cs.direction, textAlign: cs.textAlign };
  });
  expect(style.direction).toBe('ltr');
  expect(style.textAlign).toBe('right');
  await browser.locator(modal('.form-actions button.button-outline')).tap();
});

async function openStubbedReceipt(app: any, screen: any, browser: any, fixture: object = RECEIPT_FIXTURE) {
  await browser.route(RECEIPT_API, route => route.fulfill({ status: 200, json: fixture }));
  await app.open('#/students');
  await expect(screen.getByRole('searchbox', 'بحث في الطلاب')).toBeVisible();
  await browser.goto(new URL('../print/templates/receipt.html?payment=e2e-stub', await browser.url()).href);
  await expect(browser.locator('.sig-receiver')).toBeVisible();
}

test('E1 receipt WhatsApp button opens WhatsApp Web in a new, isolated tab', { session: 'admin', tags: ['print', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  await openStubbedReceipt(app, screen, browser);
  const share = browser.locator('#gheras-whatsapp-action');
  await expect(share).toBeVisible();
  await expect(share).toHaveAttribute('target', '_blank');
  await expect(share).toHaveAttribute('rel', 'noopener noreferrer');
  await expect(share).toHaveAttribute('href', /^https:\/\/wa\.me\/966551234567\?text=[^&]+$/);
  await expect(share).toContainText('مشاركة عبر واتساب');
});

test('E2 receipt print button is visible and calls window.print', { session: 'admin', tags: ['print', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  await openStubbedReceipt(app, screen, browser);
  const print = browser.locator('.print-toolbar-btn.print-btn');
  await expect(print).toBeVisible();
  await expect(print).toContainText('طباعة المستند');
  await browser.evaluate(() => { (window as any).__printCalls = 0; window.print = () => { (window as any).__printCalls += 1; }; });
  await print.tap();
  expect(await browser.evaluate(() => (window as any).__printCalls)).toBe(1);
});

test('E3 receipt close button is visible and returns to the dashboard', { session: 'admin', tags: ['print', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  await openStubbedReceipt(app, screen, browser);
  const close = browser.locator('.print-toolbar-btn.close-btn');
  await expect(close).toBeVisible();
  await expect(close).toContainText('إغلاق');
  await close.tap();
  // Opened in the same tab from the dashboard: history.back() returns there
  await expect(screen.getByRole('searchbox', 'بحث في الطلاب')).toBeVisible();
});

test('E4 receipt bus rows show fee, paid and remaining figures', { session: 'admin', tags: ['print', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  await openStubbedReceipt(app, screen, browser);
  const rows = browser.locator('.receipt-bus-row');
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(0)).toContainText('رسوم اشتراك الباص');
  await expect(rows.nth(0)).toContainText('400 ريال');
  await expect(rows.nth(0)).toContainText('المسدد من الباص');
  await expect(rows.nth(0)).toContainText('150 ريال');
  await expect(rows.nth(1)).toContainText('المتبقي من الباص');
  await expect(rows.nth(1)).toContainText('250 ريال');
});

test('E6 receipt receiver signature shows the issuing staff member', { session: 'admin', tags: ['print', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  await openStubbedReceipt(app, screen, browser);
  await expect(browser.locator('.sig-receiver')).toHaveText('أمل المحاسبة');
});

test('E7 student edit form allows typing in bus paid input and is not readonly', { session: 'admin', tags: ['students', 'order15', 'granular'] }, async ({ app, screen, browser }) => {
  await app.open('#/students');
  const editBtn = browser.locator('table tbody tr:first-child button:has-text("تعديل")');
  if (await editBtn.count() > 0) {
    await editBtn.tap();
    await expect(browser.locator('.modal-panel select[name="has_bus_subscription"]')).toBeVisible();
    await browser.locator('.modal-panel select[name="has_bus_subscription"]').selectOption({ value: 'true' });
    const busPaidInput = browser.locator('.modal-panel input[name="bus_paid"]');
    await expect(busPaidInput).toBeVisible();
    const isReadonly = await busPaidInput.evaluate((el: HTMLInputElement) => el.readOnly);
    expect(isReadonly).toBe(false);
    await busPaidInput.fill('450');
    expect(await busPaidInput.inputValue()).toBe('450');
  }
});

