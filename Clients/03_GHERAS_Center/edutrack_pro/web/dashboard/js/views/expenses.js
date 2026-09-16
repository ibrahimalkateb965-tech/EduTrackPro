import { el, toast, modal, fmtSAR, fmtDate } from '../ui.js';

const METHODS = ['كاش', 'تحويل بنكي', 'مدى', 'بطاقة', 'Apple Pay'];

let expenses = [];
let categories = [];
let accounts = [];
let categoryFilter = '';
let tbody = null;

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

function categoryLabel(expense) {
  if (expense.category_name) return expense.category_name;
  const category = categories.find(item => item.id === expense.category_id);
  return category ? category.name : '—';
}

function accountLabel(expense) {
  if (expense.account_name) return expense.account_name;
  const account = accounts.find(item => item.id === expense.account_id);
  return account ? account.name : '—';
}

function expenseRow(expense, api) {
  const edit = el('button', { class: 'button button-outline', type: 'button' }, 'تعديل');
  edit.addEventListener('click', () => openExpenseForm(api, expense));
  return el('tr', {},
    el('td', {}, fmtDate(expense.paid_on)),
    el('td', {}, expense.description || '—'),
    el('td', {}, categoryLabel(expense)),
    el('td', {}, fmtSAR(expense.amount)),
    el('td', {}, expense.method || '—'),
    el('td', {}, accountLabel(expense)),
    el('td', {}, edit)
  );
}

function paint(api) {
  const list = expenses.filter(expense => !categoryFilter || String(expense.category_id) === categoryFilter);
  const rows = list.map(expense => expenseRow(expense, api));
  const emptyText = expenses.length ? 'لا توجد مصروفات ضمن هذا التصنيف' : 'لا توجد مصروفات مسجلة بعد';
  tbody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '7', class: 'muted' }, emptyText))]));
}

async function reload(api) {
  try {
    expenses = toList(await api.get('expenses'));
  } catch (error) {
    toast(error.message, true);
  }
  paint(api);
}

function openExpenseForm(api, expense) {
  const editing = Boolean(expense && expense.id);
  const value = key => (expense && expense[key] !== undefined && expense[key] !== null) ? String(expense[key]) : '';
  const categoryOptions = [['', 'اختر التصنيف']].concat(categories.map(category => [category.id, category.name]));
  const accountOptions = [['', 'بدون حساب']].concat(accounts.map(account => [account.id, account.name]));
  const fields = [
    el('label', {}, el('span', {}, 'الوصف'), el('input', { name: 'description', type: 'text', required: 'required', value: value('description') })),
    el('label', {}, el('span', {}, 'التصنيف'), select('category_id', categoryOptions, value('category_id'), true)),
    el('label', {}, el('span', {}, 'المبلغ'), el('input', { name: 'amount', type: 'number', min: '0', step: '0.01', required: 'required', value: value('amount') })),
    el('label', {}, el('span', {}, 'تاريخ الدفع'), el('input', { name: 'paid_on', type: 'date', required: 'required', value: value('paid_on') || fmtDate(new Date()) })),
    el('label', {}, el('span', {}, 'طريقة الدفع'), select('method', METHODS.map(method => [method, method]), value('method') || 'كاش')),
    el('label', {}, el('span', {}, 'الحساب'), select('account_id', accountOptions, value('account_id'))),
    el('label', {}, el('span', {}, 'ملاحظة'), el('textarea', { name: 'note' }, value('note')))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, editing ? 'حفظ التعديلات' : 'إضافة');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions' }, submit, cancel));
  const dialog = modal(editing ? 'تعديل المصروف' : 'إضافة مصروف', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    Object.keys(payload).forEach(key => { payload[key] = String(payload[key]).trim(); });
    payload.amount = Number(payload.amount);
    payload.category_id = payload.category_id || null;
    payload.account_id = payload.account_id || null;
    try {
      if (editing) await api.patch(`expenses/${expense.id}`, payload);
      else await api.post('expenses', payload);
      dialog.close();
      toast(editing ? 'تم تحديث المصروف' : 'تمت إضافة المصروف');
      await reload(api);
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

export async function render(container, api) {
  expenses = [];
  categories = [];
  accounts = [];
  categoryFilter = '';
  container.replaceChildren();
  try { categories = toList(await api.get('expense-categories')); } catch (error) { toast('تعذر تحميل تصنيفات المصروفات', true); }
  try { accounts = toList(await api.get('ledger-accounts')); } catch (error) { toast('تعذر تحميل الحسابات', true); }
  const filter = el('select', { 'aria-label': 'تصفية بالتصنيف' });
  filter.append(el('option', { value: '' }, 'كل التصنيفات'));
  categories.forEach(category => filter.append(el('option', { value: category.id }, category.name)));
  filter.addEventListener('change', () => { categoryFilter = filter.value; paint(api); });
  const addButton = el('button', { class: 'button', type: 'button' }, 'إضافة مصروف');
  addButton.addEventListener('click', () => openExpenseForm(api, null));
  tbody = el('tbody', {}, el('tr', {}, el('td', { colspan: '7', class: 'muted' }, 'جارٍ التحميل...')));
  container.append(
    el('div', { class: 'view-header' }, el('h1', {}, 'المصروفات')),
    el('div', { class: 'toolbar' }, filter, addButton),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {},
        el('th', {}, 'التاريخ'),
        el('th', {}, 'الوصف'),
        el('th', {}, 'التصنيف'),
        el('th', {}, 'المبلغ'),
        el('th', {}, 'طريقة الدفع'),
        el('th', {}, 'الحساب'),
        el('th', {}, 'إجراءات')
      )),
      tbody
    ))
  );
  await reload(api);
}
