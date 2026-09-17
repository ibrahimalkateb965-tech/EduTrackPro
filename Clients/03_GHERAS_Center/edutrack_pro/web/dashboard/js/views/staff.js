import { el, toast, modal, fmtSAR, fmtDate } from '../ui.js';

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
let absences = [];
let activeTab = 'all';
let tbody = null;
let tabsContainer = null;

function checkHasFinance(api) {
  return !api.currentUser || api.currentUser.role === 'manager' || Boolean(api.currentUser?.permissions?.finance);
}

function checkIsManager(api) {
  return !api.currentUser || api.currentUser.role === 'manager';
}

function getParams() {
  const hash = location.hash.replace(/^#\/?/, '');
  const qIdx = hash.indexOf('?');
  if (qIdx === -1) return new URLSearchParams();
  return new URLSearchParams(hash.slice(qIdx + 1));
}

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

function absenceStatusBadge(status) {
  if (status === 'غائب') return el('span', { class: 'badge red' }, 'غائب');
  if (status === 'متأخر') return el('span', { class: 'badge', style: 'background:#fef3c7; color:#92400e;' }, 'متأخر');
  if (status === 'مستأذن') return el('span', { class: 'badge', style: 'background:#e0f2fe; color:#0369a1;' }, 'مستأذن');
  return el('span', { class: 'badge' }, status || 'حاضر');
}

function staffRow(member, api) {
  const hasFinance = checkHasFinance(api);
  const isManager = checkIsManager(api);
  const edit = el('button', { class: 'button button-outline', type: 'button' }, 'تعديل');
  edit.addEventListener('click', () => openStaffForm(api, member));
  const actions = [edit];
  if (isManager) {
    const account = el('button', { class: 'button button-outline', type: 'button' }, 'حساب دخول');
    account.addEventListener('click', () => openUserForm(api, member));
    actions.push(' ', account);
  }
  if (hasFinance) {
    const payroll = el('button', { class: 'button button-outline', type: 'button' }, 'راتب');
    payroll.addEventListener('click', () => openPayrollForm(api, member));
    actions.push(' ', payroll);
  }
  const salaryText = hasFinance && member.base_salary !== null && member.base_salary !== undefined
    ? fmtSAR(member.base_salary)
    : '—';
  return el('tr', {},
    el('td', {}, member.name || '—'),
    el('td', {}, member.role_title || '—'),
    el('td', {}, member.phone || '—'),
    el('td', {}, salaryText),
    el('td', {}, statusBadge(member)),
    el('td', {}, ...actions)
  );
}

function absenceRow(rec) {
  const member = staff.find(s => s.id === rec.staff_id) || {};
  return el('tr', {},
    el('td', {}, member.name || rec.staff_name || '—'),
    el('td', {}, member.role_title || '—'),
    el('td', {}, rec.date || '—'),
    el('td', {}, absenceStatusBadge(rec.status)),
    el('td', {}, rec.note || '—')
  );
}

function paint(api) {
  if (activeTab === 'absences') {
    const rows = absences.map(absenceRow);
    tbody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '5', class: 'muted', style: 'text-align:center;' }, 'لا توجد سجلات غياب للموظفين بعد'))]));
    return;
  }

  const list = staff.filter(member => {
    const title = (member.role_title || '').toLowerCase();
    if (activeTab === 'teachers_supervisors') {
      return title.includes('معلم') || title.includes('مشرف') || title.includes('إشراف') || title.includes('تحفيظ') || title.includes('تعليم');
    }
    if (activeTab === 'teachers') {
      return title.includes('معلم') || title.includes('تحفيظ') || title.includes('تعليم');
    }
    if (activeTab === 'supervisors') {
      return title.includes('مشرف') || title.includes('إشراف');
    }
    return true;
  });

  const rows = list.map(member => staffRow(member, api));
  tbody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '6', class: 'muted', style: 'text-align:center;' }, 'لا يوجد موظفون في هذا القسم'))]));
}

async function reload(api) {
  try {
    staff = toList(await api.get('staff'));
  } catch (error) {
    toast(error.message, true);
  }
  if (activeTab === 'absences') {
    try {
      absences = toList(await api.get('staff-attendance'));
    } catch (error) {
      toast('تعذر تحميل سجلات غياب الموظفين', true);
    }
  }
  paint(api);
}

function openStaffForm(api, member) {
  const editing = Boolean(member && member.id);
  const hasFinance = checkHasFinance(api);
  const value = key => (member && member[key] !== undefined && member[key] !== null) ? String(member[key]) : '';
  const statusOptions = [['active', 'نشط'], ['on_leave', 'إجازة'], ['terminated', 'منتهي']];
  const fields = [
    el('label', {}, el('span', {}, 'الاسم'), el('input', { name: 'name', type: 'text', required: 'required', value: value('name') })),
    el('label', {}, el('span', {}, 'المسمى الوظيفي'), el('input', { name: 'role_title', type: 'text', required: 'required', value: value('role_title') })),
    el('label', {}, el('span', {}, 'الجوال'), el('input', { name: 'phone', type: 'tel', value: value('phone') })),
    el('label', {}, el('span', {}, 'تاريخ التعيين'), el('input', { name: 'hire_date', type: 'date', value: value('hire_date') })),
  ];
  if (hasFinance) {
    fields.push(
      el('label', {}, el('span', {}, 'الراتب الأساسي'), el('input', { name: 'base_salary', type: 'number', min: '0', step: '0.01', required: 'required', value: value('base_salary') }))
    );
  }
  fields.push(
    el('label', {}, el('span', {}, 'الحالة'), select('status', statusOptions, value('status') || 'active', true))
  );
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
    if (hasFinance && payload.base_salary !== undefined && payload.base_salary !== '') {
      payload.base_salary = Number(payload.base_salary);
    } else {
      delete payload.base_salary;
    }
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

function openAbsenceForm(api) {
  const staffOptions = staff.filter(s => (s.status || 'active') === 'active').map(s => [s.id, `${s.name} (${s.role_title || 'موظف'})`]);
  if (!staffOptions.length) {
    toast('لا يوجد موظفون متاحون', true);
    return;
  }

  const today = fmtDate(new Date());
  const staffSelect = select('staff_id', staffOptions, staffOptions[0][0], true);
  const dateInput = el('input', { name: 'date', type: 'date', required: 'required', value: today });
  const statusSelect = select('status', [['غائب', 'غائب'], ['متأخر', 'متأخر'], ['مستأذن', 'مستأذن'], ['حاضر', 'حاضر']], 'غائب', true);
  const deductionInput = el('input', { name: 'deduction', type: 'number', min: '0', step: '0.01', placeholder: 'مبلغ الخصم (اختياري)' });
  const noteInput = el('textarea', { name: 'note', placeholder: 'سبب الغياب أو تفاصيل الخصم...' });

  function calcDeduction() {
    const member = staff.find(s => s.id === staffSelect.value);
    const salary = Number(member?.base_salary || 0);
    const dayRate = salary ? Math.round((salary / 30) * 100) / 100 : 0;
    if (statusSelect.value === 'غائب') {
      deductionInput.value = dayRate > 0 ? String(dayRate) : '';
    } else if (statusSelect.value === 'متأخر') {
      deductionInput.value = dayRate > 0 ? String(Math.round((dayRate / 4) * 100) / 100) : '';
    } else {
      deductionInput.value = '0';
    }
  }

  staffSelect.addEventListener('change', calcDeduction);
  statusSelect.addEventListener('change', calcDeduction);
  calcDeduction();

  const fields = [
    el('label', {}, el('span', {}, 'الموظف'), staffSelect),
    el('label', {}, el('span', {}, 'التاريخ'), dateInput),
    el('label', {}, el('span', {}, 'الحالة'), statusSelect),
    el('label', {}, el('span', {}, 'مبلغ الخصم المحسوب (ر.س)'), deductionInput),
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', {}, 'الملاحظات'), noteInput),
  ];

  const submit = el('button', { class: 'button', type: 'submit' }, 'حفظ الغياب والخصم');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions', style: 'grid-column:1/-1;' }, submit, cancel));
  const dialog = modal('تسجيل غياب / خصم موظف', form);
  cancel.addEventListener('click', dialog.close);

  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const deduction = Number(deductionInput.value || 0);
    const userNote = String(noteInput.value || '').trim();
    const fullNote = deduction > 0 ? `خصم: ${deduction} ر.س | ${userNote}` : userNote;

    const payload = [{
      staff_id: staffSelect.value,
      date: dateInput.value,
      status: statusSelect.value,
      note: fullNote || null
    }];

    try {
      await api.post('attendance/staff', payload);
      dialog.close();
      toast('تم تسجيل غياب الموظف بنجاح');
      await reload(api);
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

export async function render(container, api) {
  staff = [];
  rooms = [];
  absences = [];
  container.replaceChildren();

  // Parse initial tab from URL hash params
  const params = getParams();
  const tab = params.get('tab');
  if (tab === 'absences') activeTab = 'absences';
  else if (tab === 'teachers_supervisors') activeTab = 'teachers_supervisors';
  else if (tab === 'teachers') activeTab = 'teachers';
  else if (tab === 'supervisors') activeTab = 'supervisors';
  else activeTab = 'all';

  const TABS = [
    ['all', 'كل الكادر'],
    ['teachers_supervisors', 'المعلمون والمشرفون'],
    ['teachers', 'المعلمون'],
    ['supervisors', 'المشرفون'],
    ['absences', 'غياب الموظفين والخصومات']
  ];

  tabsContainer = el('div', { class: 'toolbar', style: 'margin-bottom:12px; gap:6px;' });
  const theadEl = el('thead');
  tbody = el('tbody', {}, el('tr', {}, el('td', { colspan: '6', class: 'muted' }, 'جارٍ التحميل...')));

  function updateTableHeaders() {
    if (activeTab === 'absences') {
      theadEl.replaceChildren(
        el('tr', {},
          el('th', {}, 'الموظف'),
          el('th', {}, 'المسمى الوظيفي'),
          el('th', {}, 'التاريخ'),
          el('th', {}, 'الحالة'),
          el('th', {}, 'الخصم والملاحظة')
        )
      );
    } else {
      theadEl.replaceChildren(
        el('tr', {},
          el('th', {}, 'الاسم'),
          el('th', {}, 'المسمى الوظيفي'),
          el('th', {}, 'الجوال'),
          el('th', {}, 'الراتب الأساسي'),
          el('th', {}, 'الحالة'),
          el('th', {}, 'إجراءات')
        )
      );
    }
  }

  const toolbar = el('div', { class: 'toolbar' });

  function updateToolbar() {
    toolbar.replaceChildren();
    if (activeTab === 'absences') {
      const recordBtn = el('button', { class: 'button', type: 'button' }, '➕ تسجيل غياب / خصم جديد');
      recordBtn.onclick = () => openAbsenceForm(api);
      toolbar.append(recordBtn);
    } else {
      const addStaffBtn = el('button', { class: 'button', type: 'button' }, '➕ إضافة موظف');
      addStaffBtn.onclick = () => openStaffForm(api, null);
      toolbar.append(addStaffBtn);
    }
  }

  function updateTabs() {
    tabsContainer.replaceChildren(
      ...TABS.map(([id, label]) => {
        const btn = el('button', {
          class: activeTab === id ? 'button' : 'button button-outline',
          type: 'button'
        }, label);
        btn.onclick = async () => {
          activeTab = id;
          updateTabs();
          updateTableHeaders();
          updateToolbar();
          await reload(api);
        };
        return btn;
      })
    );
  }

  updateTabs();
  updateTableHeaders();
  updateToolbar();

  container.append(
    el('div', { class: 'view-header' }, el('h1', {}, 'إدارة الموظفين والكادر')),
    tabsContainer,
    toolbar,
    el('div', { class: 'table-wrap' }, el('table', {}, theadEl, tbody))
  );

  try { rooms = toList(await api.get('rooms')); } catch (error) { toast('تعذر تحميل الحلقات', true); }
  await reload(api);
}
