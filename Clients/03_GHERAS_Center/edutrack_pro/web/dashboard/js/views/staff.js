import { el, toast, modal, fmtSAR } from '../ui.js';

const STATUSES = { active: 'نشط', on_leave: 'إجازة', terminated: 'منتهي' };
const ROLES = [['manager', 'مدير'], ['supervisor', 'مشرف'], ['teacher', 'معلم']];
const PERMISSIONS = [
  ['attendance', 'الحضور'],
  ['daily_evaluation', 'التقييم اليومي'],
  ['monthly_evaluation', 'التقييم الشهري'],
  ['students', 'الطلاب'],
  ['finance', 'المالية']
];

let staff = [];
let rooms = [];
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

function currentMonth() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}

function statusBadge(member) {
  return el('span', { class: `badge badge-${member.status || 'unknown'}` }, STATUSES[member.status] || member.status || '—');
}

function staffRow(member, api) {
  const edit = el('button', { class: 'button button-outline', type: 'button' }, 'تعديل');
  edit.addEventListener('click', () => openStaffForm(api, member));
  const account = el('button', { class: 'button button-outline', type: 'button' }, 'حساب دخول');
  account.addEventListener('click', () => openUserForm(api, member));
  const payroll = el('button', { class: 'button button-outline', type: 'button' }, 'راتب');
  payroll.addEventListener('click', () => openPayrollForm(api, member));
  return el('tr', {},
    el('td', {}, member.name || '—'),
    el('td', {}, member.role_title || '—'),
    el('td', {}, member.phone || '—'),
    el('td', {}, fmtSAR(member.base_salary)),
    el('td', {}, statusBadge(member)),
    el('td', {}, edit, account, payroll)
  );
}

function paint(api) {
  const rows = staff.map(member => staffRow(member, api));
  tbody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '6', class: 'muted' }, 'لا يوجد موظفون بعد'))]));
}

async function reload(api) {
  try {
    staff = toList(await api.get('staff'));
  } catch (error) {
    toast(error.message, true);
  }
  paint(api);
}

function openStaffForm(api, member) {
  const editing = Boolean(member && member.id);
  const value = key => (member && member[key] !== undefined && member[key] !== null) ? String(member[key]) : '';
  const statusOptions = [['active', 'نشط'], ['on_leave', 'إجازة'], ['terminated', 'منتهي']];
  const fields = [
    el('label', {}, el('span', {}, 'الاسم'), el('input', { name: 'name', type: 'text', required: 'required', value: value('name') })),
    el('label', {}, el('span', {}, 'المسمى الوظيفي'), el('input', { name: 'role_title', type: 'text', required: 'required', value: value('role_title') })),
    el('label', {}, el('span', {}, 'الجوال'), el('input', { name: 'phone', type: 'tel', value: value('phone') })),
    el('label', {}, el('span', {}, 'تاريخ التعيين'), el('input', { name: 'hire_date', type: 'date', value: value('hire_date') })),
    el('label', {}, el('span', {}, 'الراتب الأساسي'), el('input', { name: 'base_salary', type: 'number', min: '0', step: '0.01', required: 'required', value: value('base_salary') })),
    el('label', {}, el('span', {}, 'الحالة'), select('status', statusOptions, value('status') || 'active', true))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, editing ? 'حفظ التعديلات' : 'إضافة');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions' }, submit, cancel));
  const dialog = modal(editing ? 'تعديل موظف' : 'إضافة موظف', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    Object.keys(payload).forEach(key => { payload[key] = String(payload[key]).trim(); });
    payload.base_salary = Number(payload.base_salary);
    payload.phone = payload.phone || null;
    payload.hire_date = payload.hire_date || null;
    try {
      if (editing) await api.patch(`staff/${member.id}`, payload);
      else await api.post('staff', payload);
      dialog.close();
      toast(editing ? 'تم تحديث بيانات الموظف' : 'تمت إضافة الموظف');
      await reload(api);
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

function openUserForm(api, member) {
  const roomOptions = [['', 'بدون حلقة']].concat(rooms.map(room => [room.id, room.name]));
  const fields = [
    el('label', {}, el('span', {}, 'اسم المستخدم'), el('input', { name: 'username', type: 'text', required: 'required', autocomplete: 'off' })),
    el('label', {}, el('span', {}, 'كلمة المرور'), el('input', { name: 'password', type: 'password', required: 'required', autocomplete: 'new-password' })),
    el('label', {}, el('span', {}, 'الدور'), select('role', ROLES, 'teacher', true)),
    el('label', {}, el('span', {}, 'الحلقة'), select('room_id', roomOptions, '')),
    el('strong', {}, 'الصلاحيات'),
    ...PERMISSIONS.map(([key, label]) => el('label', {}, el('input', { type: 'checkbox', name: key }), el('span', {}, label)))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, 'إنشاء الحساب');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions' }, submit, cancel));
  const dialog = modal(`حساب دخول: ${member.name || ''}`, form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const data = Object.fromEntries(new FormData(form).entries());
    const permissions = {};
    PERMISSIONS.forEach(([key]) => { permissions[key] = Boolean(form.querySelector(`[name="${key}"]`)?.checked); });
    const payload = {
      username: String(data.username).trim(),
      password: String(data.password),
      role: data.role,
      staff_id: member.id,
      room_id: data.room_id || null,
      permissions
    };
    try {
      await api.post('users', payload);
      dialog.close();
      toast('تم إنشاء حساب الدخول');
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

function openPayrollForm(api, member) {
  const fields = [
    el('label', {}, el('span', {}, 'الشهر'), el('input', { name: 'month', type: 'month', required: 'required', value: currentMonth() })),
    el('label', {}, el('span', {}, 'الراتب الأساسي'), el('input', { name: 'base', type: 'number', min: '0', step: '0.01', required: 'required', value: member.base_salary ?? '' })),
    el('label', {}, el('span', {}, 'البدلات'), el('input', { name: 'allowances', type: 'number', min: '0', step: '0.01', value: '0' })),
    el('label', {}, el('span', {}, 'المكافأة'), el('input', { name: 'bonus', type: 'number', min: '0', step: '0.01', value: '0' })),
    el('label', {}, el('span', {}, 'الخصومات'), el('input', { name: 'deductions', type: 'number', min: '0', step: '0.01', value: '0' })),
    el('label', {}, el('span', {}, 'سلفة مخصومة'), el('input', { name: 'advance_deducted', type: 'number', min: '0', step: '0.01', value: '0' })),
    el('label', {}, el('span', {}, 'ملاحظة'), el('textarea', { name: 'note' }))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, 'تسجيل الراتب');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions' }, submit, cancel));
  const dialog = modal(`راتب: ${member.name || ''}`, form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.staff_id = member.id;
    ['base', 'allowances', 'bonus', 'deductions', 'advance_deducted'].forEach(key => { payload[key] = Number(payload[key] || 0); });
    payload.note = String(payload.note || '').trim();
    try {
      await api.post('payroll-runs', payload);
      dialog.close();
      toast('تم تسجيل الراتب');
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

export async function render(container, api) {
  staff = [];
  rooms = [];
  container.replaceChildren();
  try { rooms = toList(await api.get('rooms')); } catch (error) { toast('تعذر تحميل الحلقات', true); }
  const addButton = el('button', { class: 'button', type: 'button' }, 'إضافة موظف');
  addButton.addEventListener('click', () => openStaffForm(api, null));
  tbody = el('tbody', {}, el('tr', {}, el('td', { colspan: '6', class: 'muted' }, 'جارٍ التحميل...')));
  container.append(
    el('div', { class: 'view-header' }, el('h1', {}, 'الموظفون')),
    el('div', { class: 'toolbar' }, addButton),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {},
        el('th', {}, 'الاسم'),
        el('th', {}, 'المسمى الوظيفي'),
        el('th', {}, 'الجوال'),
        el('th', {}, 'الراتب الأساسي'),
        el('th', {}, 'الحالة'),
        el('th', {}, 'إجراءات')
      )),
      tbody
    ))
  );
  await reload(api);
}
