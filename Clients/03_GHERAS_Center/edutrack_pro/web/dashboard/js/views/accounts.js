import { el, toast, modal, fmtSAR, fmtDate } from '../ui.js';

const KINDS = { cash: 'كاش', bank: 'بنك' };
const ENTRY_TYPES = { in: 'قبض', out: 'صرف', transfer_in: 'تحويل وارد', transfer_out: 'تحويل صادر' };

let accounts = [];
let entries = [];
let accountsBody = null;
let entriesBody = null;

function toList(data) {
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.items)) return data.items;
  return [];
}

function select(name, options, selected, required) {
  const attributes = { name };
  if (required) attributes.required = 'required';
  const node = el('select', attributes);
  options.forEach(([optionValue, optionLabel]) => node.append(el('option', { value: optionValue }, optionLabel)));
  node.value = selected === undefined || selected === null ? '' : String(selected);
  return node;
}

function currentMonth() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}

function accountLabel(entry) {
  if (entry.account_name) return entry.account_name;
  const account = accounts.find(item => item.id === entry.account_id);
  return account ? account.name : '—';
}

function accountRow(account) {
  return el('tr', {},
    el('td', {}, account.name || '—'),
    el('td', {}, KINDS[account.kind] || account.kind || '—'),
    el('td', {}, fmtSAR(account.opening_balance)),
    el('td', {}, fmtSAR(account.balance))
  );
}

function entryRow(entry) {
  return el('tr', {},
    el('td', {}, fmtDate(entry.occurred_on)),
    el('td', {}, accountLabel(entry)),
    el('td', {}, ENTRY_TYPES[entry.entry_type] || entry.entry_type || '—'),
    el('td', {}, fmtSAR(entry.amount))
  );
}

function paint() {
  const accountRows = accounts.map(accountRow);
  accountsBody.replaceChildren(...(accountRows.length ? accountRows : [el('tr', {}, el('td', { colspan: '4', class: 'muted' }, 'لا توجد حسابات بعد'))]));
  const entryRows = entries.map(entryRow);
  entriesBody.replaceChildren(...(entryRows.length ? entryRows : [el('tr', {}, el('td', { colspan: '4', class: 'muted' }, 'لا توجد حركات مالية بعد'))]));
}

async function reload(api) {
  try { accounts = toList(await api.get('ledger-accounts')); } catch (error) { toast(error.message, true); }
  try { entries = toList(await api.get('ledger-entries')); } catch (error) { toast(error.message, true); }
  paint();
}

function openAccountForm(api) {
  const fields = [
    el('label', {}, el('span', {}, 'اسم الحساب'), el('input', { name: 'name', type: 'text', required: 'required' })),
    el('label', {}, el('span', {}, 'النوع'), select('kind', [['cash', 'كاش'], ['bank', 'بنك']], 'cash', true)),
    el('label', {}, el('span', {}, 'الرصيد الافتتاحي'), el('input', { name: 'opening_balance', type: 'number', step: '0.01', value: '0' }))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, 'إضافة');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions' }, submit, cancel));
  const dialog = modal('إضافة حساب', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.name = String(payload.name).trim();
    payload.opening_balance = Number(payload.opening_balance || 0);
    try {
      await api.post('ledger-accounts', payload);
      dialog.close();
      toast('تمت إضافة الحساب');
      await reload(api);
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

function openTransferForm(api) {
  const accountOptions = [['', 'اختر الحساب']].concat(accounts.map(account => [account.id, account.name]));
  const fields = [
    el('label', {}, el('span', {}, 'من حساب'), select('from_account_id', accountOptions, '', true)),
    el('label', {}, el('span', {}, 'إلى حساب'), select('to_account_id', accountOptions, '', true)),
    el('label', {}, el('span', {}, 'المبلغ'), el('input', { name: 'amount', type: 'number', min: '0', step: '0.01', required: 'required' })),
    el('label', {}, el('span', {}, 'التاريخ'), el('input', { name: 'occurred_on', type: 'date', required: 'required', value: fmtDate(new Date()) })),
    el('label', {}, el('span', {}, 'ملاحظة'), el('textarea', { name: 'note' }))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, 'تحويل');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions' }, submit, cancel));
  const dialog = modal('تحويل بين الحسابات', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const payload = Object.fromEntries(new FormData(form).entries());
    if (payload.from_account_id === payload.to_account_id) {
      toast('اختر حسابين مختلفين', true);
      return;
    }
    submit.disabled = true;
    payload.amount = Number(payload.amount);
    payload.note = String(payload.note || '').trim();
    try {
      await api.post('ledger-entries', payload);
      dialog.close();
      toast('تم التحويل بين الحسابات');
      await reload(api);
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

function openMonthClosure(api) {
  const fields = [
    el('label', {}, el('span', {}, 'الشهر'), el('input', { name: 'month', type: 'month', required: 'required', value: currentMonth() }))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, 'إقفال الشهر');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions' }, submit, cancel));
  const dialog = modal('إقفال الشهر', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    try {
      await api.post('month-closures', { month: String(payload.month).trim() });
      dialog.close();
      toast('تم إقفال الشهر');
      await reload(api);
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

export async function render(container, api) {
  accounts = [];
  entries = [];
  container.replaceChildren();
  const addButton = el('button', { class: 'button', type: 'button' }, 'إضافة حساب');
  addButton.addEventListener('click', () => openAccountForm(api));
  const transferButton = el('button', { class: 'button button-outline', type: 'button' }, 'تحويل بين الحسابات');
  transferButton.addEventListener('click', () => openTransferForm(api));
  const closeButton = el('button', { class: 'button button-outline', type: 'button' }, 'إقفال الشهر');
  closeButton.addEventListener('click', () => openMonthClosure(api));
  accountsBody = el('tbody', {}, el('tr', {}, el('td', { colspan: '4', class: 'muted' }, 'جارٍ التحميل...')));
  entriesBody = el('tbody', {}, el('tr', {}, el('td', { colspan: '4', class: 'muted' }, 'جارٍ التحميل...')));
  container.append(
    el('div', { class: 'view-header' }, el('h1', {}, 'الحسابات')),
    el('div', { class: 'toolbar' }, addButton, transferButton, closeButton),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {},
        el('th', {}, 'اسم الحساب'),
        el('th', {}, 'النوع'),
        el('th', {}, 'الرصيد الافتتاحي'),
        el('th', {}, 'الرصيد الحالي')
      )),
      accountsBody
    )),
    el('div', { class: 'view-header' }, el('h2', {}, 'آخر الحركات')),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {},
        el('th', {}, 'التاريخ'),
        el('th', {}, 'الحساب'),
        el('th', {}, 'النوع'),
        el('th', {}, 'المبلغ')
      )),
      entriesBody
    ))
  );
  await reload(api);
}
