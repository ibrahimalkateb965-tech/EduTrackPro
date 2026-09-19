import { el, toast, modal, fmtSAR, fmtDate } from '../ui.js';

const PAYMENT_METHODS = ['كاش', 'تحويل بنكي', 'تابي', 'تقسيط المركز', 'مدى', 'Apple Pay', 'بطاقة'];
const EXPENSE_METHODS = ['كاش', 'تحويل بنكي', 'مدى', 'بطاقة', 'Apple Pay'];
const INTERVALS = [[30, 'شهري (كل 30 يوم)'], [14, 'كل 14 يوم'], [7, 'أسبوعي (كل 7 أيام)']];
const KINDS = { cash: 'كاش', bank: 'بنك' };
const ENTRY_TYPES = { in: 'قبض', out: 'صرف', transfer_in: 'تحويل وارد', transfer_out: 'تحويل صادر' };

let students = [];
let feePlans = [];
let payments = [];
let installments = [];
let expenses = [];
let categories = [];
let accounts = [];
let entries = [];
let staff = [];
let payrollRuns = [];
let financeReport = {};
let activeTab = 'overview';
let categoryFilter = '';

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

function activeStudents() {
  return students.filter(student => (student.status || 'active') === 'active');
}

function studentName(record) {
  if (record?.student_name) return record.student_name;
  const student = students.find(item => item.id === (record?.student_id || record?.id));
  return student ? student.name : '—';
}

function planFor(feePlanId) {
  return feePlans.find(plan => plan.id === feePlanId) || null;
}

function installmentLabel(installment) {
  const plan = planFor(installment.fee_plan_id);
  const student = plan ? studentName({ student_id: plan.student_id }) : '—';
  const parts = [`قسط ${installment.seq_no ?? ''}`, fmtDate(installment.due_date)];
  if (student !== '—') parts.push(student);
  return parts.join(' — ');
}

function intervalLabel(days) {
  const found = INTERVALS.find(([value]) => Number(value) === Number(days));
  return found ? found[1] : `${days} يوم`;
}

function categoryLabel(expense) {
  if (expense.category_name) return expense.category_name;
  const category = categories.find(item => item.id === expense.category_id);
  return category ? category.name : '—';
}

function accountLabel(rec) {
  if (rec.account_name) return rec.account_name;
  const account = accounts.find(item => item.id === rec.account_id);
  return account ? account.name : '—';
}

function getQueryTab() {
  const hash = location.hash.replace(/^#\/?/, '');
  const qIdx = hash.indexOf('?');
  if (qIdx === -1) return null;
  const params = new URLSearchParams(hash.slice(qIdx + 1));
  return params.get('tab');
}

function setQueryTab(tab) {
  activeTab = tab;
  location.hash = `#/finance?tab=${tab}`;
}

// -------------------------------------------------------------
// Modals & Forms
// -------------------------------------------------------------

function openPlanForm(api, onSuccess) {
  const studentOptions = [['', 'اختر الطالب']].concat(activeStudents().map(student => [student.id, student.name]));
  const fields = [
    el('label', {}, el('span', {}, 'الطالب'), select('student_id', studentOptions, '', true)),
    el('label', {}, el('span', {}, 'إجمالي المبلغ'), el('input', { name: 'total_amount', type: 'number', min: '0', step: '0.01', required: 'required' })),
    el('label', {}, el('span', {}, 'عدد الأقساط'), el('input', { name: 'count', type: 'number', min: '1', step: '1', required: 'required', value: '1' })),
    el('label', {}, el('span', {}, 'تاريخ البدء'), el('input', { name: 'start_date', type: 'date', required: 'required', value: fmtDate(new Date()) })),
    el('label', {}, el('span', {}, 'الفاصل بين الأقساط'), select('interval_days', INTERVALS, 30))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, 'إضافة');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions' }, submit, cancel));
  const dialog = modal('إضافة خطة أقساط للرسوم', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.total_amount = Number(payload.total_amount);
    payload.count = Number(payload.count);
    payload.interval_days = Number(payload.interval_days);
    try {
      await api.post('fee-plans', payload);
      dialog.close();
      toast('تمت إضافة خطة الأقساط بنجاح');
      if (onSuccess) await onSuccess();
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

function openPaymentForm(api, defaultStudentId, onSuccess) {
  const studentOptions = [['', 'اختر الطالب']].concat(activeStudents().map(student => [student.id, student.name]));
  const studentSelect = select('student_id', studentOptions, defaultStudentId || '', true);
  const installmentSelect = select('installment_id', [['', 'بدون قسط']], '');
  
  const refreshInstallments = () => {
    installmentSelect.replaceChildren(el('option', { value: '' }, 'بدون قسط'));
    const sId = studentSelect.value;
    const list = sId ? installments.filter(item => planFor(item.fee_plan_id)?.student_id === sId && item.status !== 'مدفوع') : [];
    if (!list.length) {
      installmentSelect.append(el('option', { value: '', disabled: 'disabled' }, sId ? 'لا توجد أقساط معلقة لهذا الطالب' : 'اختر الطالب لعرض أقساطه'));
    } else {
      list.forEach(item => installmentSelect.append(el('option', { value: item.id }, installmentLabel(item))));
    }
    installmentSelect.value = '';
  };
  studentSelect.addEventListener('change', refreshInstallments);
  refreshInstallments();

  const fields = [
    el('label', {}, el('span', {}, 'الطالب'), studentSelect),
    el('label', {}, el('span', {}, 'القسط (اختياري)'), installmentSelect),
    el('label', {}, el('span', {}, 'المبلغ المحصل'), el('input', { name: 'amount', type: 'number', min: '0.01', step: '0.01', required: 'required' })),
    el('label', {}, el('span', {}, 'طريقة الدفع'), select('method', PAYMENT_METHODS.map(m => [m, m]), 'كاش', true)),
    el('label', {}, el('span', {}, 'تاريخ الدفع'), el('input', { name: 'paid_on', type: 'date', required: 'required', value: fmtDate(new Date()) })),
    el('label', {}, el('span', {}, 'ملاحظة'), el('textarea', { name: 'note' }, ''))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, 'تسجيل الدفعة');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions' }, submit, cancel));
  const dialog = modal('تسجيل دفعة جديدة', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.amount = Number(payload.amount);
    payload.installment_id = payload.installment_id || null;
    try {
      const res = await api.post('payments', payload);
      dialog.close();
      toast('تم تسجيل الدفعة وإصدار السند بنجاح');
      if (res?.id) {
        window.open(`../print/templates/receipt.html?payment=${encodeURIComponent(res.id)}`, '_blank');
      }
      if (onSuccess) await onSuccess();
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

function openExpenseForm(api, expense, onSuccess) {
  const editing = Boolean(expense && expense.id);
  const value = key => (expense && expense[key] !== undefined && expense[key] !== null) ? String(expense[key]) : '';
  const categoryOptions = [['', 'اختر التصنيف']].concat(categories.map(cat => [cat.id, cat.name]));
  const accountOptions = [['', 'بدون تحديد']].concat(accounts.map(acc => [acc.id, acc.name]));
  const fields = [
    el('label', {}, el('span', {}, 'الوصف / البيان'), el('input', { name: 'description', type: 'text', required: 'required', value: value('description') })),
    el('label', {}, el('span', {}, 'التصنيف'), select('category_id', categoryOptions, value('category_id'), true)),
    el('label', {}, el('span', {}, 'المبلغ المصروف'), el('input', { name: 'amount', type: 'number', min: '0.01', step: '0.01', required: 'required', value: value('amount') })),
    el('label', {}, el('span', {}, 'تاريخ الصرف'), el('input', { name: 'paid_on', type: 'date', required: 'required', value: value('paid_on') || fmtDate(new Date()) })),
    el('label', {}, el('span', {}, 'طريقة الدفع'), select('method', EXPENSE_METHODS.map(m => [m, m]), value('method') || 'كاش')),
    el('label', {}, el('span', {}, 'خصم من حساب / خزينة'), select('account_id', accountOptions, value('account_id'))),
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', {}, 'ملاحظات إضافية'), el('textarea', { name: 'note' }, value('note')))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, editing ? 'حفظ التعديلات' : 'تسجيل المصروف');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions', style: 'grid-column:1/-1;' }, submit, cancel));
  const dialog = modal(editing ? 'تعديل مصروف' : 'تسجيل مصروف جديد', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.amount = Number(payload.amount);
    payload.category_id = payload.category_id || null;
    payload.account_id = payload.account_id || null;
    try {
      if (editing) await api.patch(`expenses/${expense.id}`, payload);
      else await api.post('expenses', payload);
      dialog.close();
      toast(editing ? 'تم تحديث المصروف' : 'تم تسجيل المصروف بنجاح');
      if (onSuccess) await onSuccess();
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

function openAccountForm(api, onSuccess) {
  const fields = [
    el('label', {}, el('span', {}, 'اسم الحساب / الخزينة'), el('input', { name: 'name', type: 'text', required: 'required', placeholder: 'مثال: خزينة المركز أو حساب الراجحي' })),
    el('label', {}, el('span', {}, 'النوع'), select('kind', [['cash', 'خزينة نقدية (كاش)'], ['bank', 'حساب بنكي']], 'cash', true)),
    el('label', {}, el('span', {}, 'الرصيد الافتتاحي'), el('input', { name: 'opening_balance', type: 'number', step: '0.01', value: '0' }))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, 'حفظ الحساب');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions' }, submit, cancel));
  const dialog = modal('إضافة حساب مالي / خزينة', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.opening_balance = Number(payload.opening_balance || 0);
    try {
      await api.post('ledger-accounts', payload);
      dialog.close();
      toast('تمت إضافة الحساب المالي بنجاح');
      if (onSuccess) await onSuccess();
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

function openTransferForm(api, onSuccess) {
  const accountOptions = [['', 'اختر الحساب']].concat(accounts.map(acc => [acc.id, `${acc.name} (${fmtSAR(acc.balance)})`]));
  const fields = [
    el('label', {}, el('span', {}, 'من حساب (المصدر)'), select('from_account_id', accountOptions, '', true)),
    el('label', {}, el('span', {}, 'إلى حساب (الوجهة)'), select('to_account_id', accountOptions, '', true)),
    el('label', {}, el('span', {}, 'المبلغ المحول'), el('input', { name: 'amount', type: 'number', min: '0.01', step: '0.01', required: 'required' })),
    el('label', {}, el('span', {}, 'تاريخ التحويل'), el('input', { name: 'occurred_on', type: 'date', required: 'required', value: fmtDate(new Date()) })),
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', {}, 'بيان التحويل'), el('input', { name: 'note', type: 'text', placeholder: 'مثال: تغذية الخزينة من الحساب البنكي' }))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, 'تنفيذ التحويل');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions', style: 'grid-column:1/-1;' }, submit, cancel));
  const dialog = modal('تحويل أموال بين الحسابات والخزائن', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const payload = Object.fromEntries(new FormData(form).entries());
    if (payload.from_account_id === payload.to_account_id) {
      toast('لا يمكن التحويل لنفس الحساب المصدر', true);
      return;
    }
    submit.disabled = true;
    payload.amount = Number(payload.amount);
    try {
      await api.post('ledger-entries', payload);
      dialog.close();
      toast('تم التحويل وتسجيل الحركات بنجاح');
      if (onSuccess) await onSuccess();
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

function openPayrollForm(api, defaultStaff, onSuccess) {
  const staffOptions = [['', 'اختر الموظف']].concat(staff.filter(s => (s.status || 'active') === 'active').map(s => [s.id, `${s.name} (${s.role_title || 'موظف'})`]));
  const staffSelect = select('staff_id', staffOptions, defaultStaff?.id || '', true);
  const baseInput = el('input', { name: 'base', type: 'number', min: '0', step: '0.01', required: 'required', value: defaultStaff?.base_salary ?? '0' });
  const allowInput = el('input', { name: 'allowances', type: 'number', min: '0', step: '0.01', value: '0' });
  const bonusInput = el('input', { name: 'bonus', type: 'number', min: '0', step: '0.01', value: '0' });
  const dedInput = el('input', { name: 'deductions', type: 'number', min: '0', step: '0.01', value: '0' });
  const advInput = el('input', { name: 'advance_deducted', type: 'number', min: '0', step: '0.01', value: '0' });
  const noteInput = el('textarea', { name: 'note' }, '');

  staffSelect.addEventListener('change', () => {
    const s = staff.find(item => item.id === staffSelect.value);
    if (s && s.base_salary !== null && s.base_salary !== undefined) {
      baseInput.value = String(s.base_salary);
    }
  });

  const fields = [
    el('label', {}, el('span', {}, 'الموظف'), staffSelect),
    el('label', {}, el('span', {}, 'الشهر المستحق'), el('input', { name: 'month', type: 'month', required: 'required', value: currentMonth() })),
    el('label', {}, el('span', {}, 'الراتب الأساسي'), baseInput),
    el('label', {}, el('span', {}, 'البدلات الإضافية'), allowInput),
    el('label', {}, el('span', {}, 'المكافآت والتحفيز'), bonusInput),
    el('label', {}, el('span', {}, 'الخصومات والجزاءات'), dedInput),
    el('label', {}, el('span', {}, 'سلفة مخصومة'), advInput),
    el('label', { style: 'grid-column:1/-1;' }, el('span', {}, 'ملاحظات وتفاصيل'), noteInput)
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, 'تسجيل وصرف الراتب');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions', style: 'grid-column:1/-1;' }, submit, cancel));
  const dialog = modal('تسجيل مسير راتب موظف', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    ['base', 'allowances', 'bonus', 'deductions', 'advance_deducted'].forEach(k => { payload[k] = Number(payload[k] || 0); });
    try {
      await api.post('payroll-runs', payload);
      dialog.close();
      toast('تم تسجيل مسير الراتب بنجاح');
      if (onSuccess) await onSuccess();
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

// -------------------------------------------------------------
// Sub-View Renderers
// -------------------------------------------------------------

function renderOverview(api, container, reloadAll) {
  const fin = financeReport || {};
  const collected = Number(fin.collected || 0);
  const expTotal = Number(fin.expenses || 0);
  const payTotal = Number(fin.payroll || 0);
  const outTotal = Number(fin.outstanding_total || 0);
  const netProfit = collected - expTotal - payTotal;

  const totalBankAndCash = accounts.reduce((sum, a) => sum + Number(a.balance || 0), 0);

  const kpis = el('div', { class: 'kpis' },
    el('div', { class: 'kpi', style: 'border-right-color: var(--g-teal);' },
      el('span', { class: 'muted' }, 'إجمالي الإيرادات والتحصيل'),
      el('strong', { style: 'color:var(--g-teal);' }, fmtSAR(collected))
    ),
    el('div', { class: 'kpi', style: 'border-right-color: var(--g-red);' },
      el('span', { class: 'muted' }, 'إجمالي المصروفات التشغيلية'),
      el('strong', { style: 'color:var(--g-red);' }, fmtSAR(expTotal))
    ),
    el('div', { class: 'kpi', style: 'border-right-color: #7851c9;' },
      el('span', { class: 'muted' }, 'مسير الرواتب المعتمد'),
      el('strong', { style: 'color:#7851c9;' }, fmtSAR(payTotal))
    ),
    el('div', { class: 'kpi', style: `border-right-color: ${netProfit >= 0 ? '#10b981' : 'var(--g-red)'};` },
      el('span', { class: 'muted' }, 'صافي الفائض / الأرباح'),
      el('strong', { style: `color:${netProfit >= 0 ? '#10b981' : 'var(--g-red)'};` }, fmtSAR(netProfit))
    )
  );

  const cardBalance = el('div', { class: 'fin-balance-card' },
    el('h4', {}, 'السيولة النقدية الحالية (الصندوق والبنوك)'),
    el('div', { class: 'big-val' }, fmtSAR(totalBankAndCash)),
    el('div', { class: 'sub-vals' },
      el('div', {}, el('small', {}, 'عدد الحسابات'), el('strong', {}, String(accounts.length))),
      el('div', {}, el('small', {}, 'المستحقات المتبقية'), el('strong', {}, fmtSAR(outTotal))),
      el('div', {}, el('small', {}, 'سندات القبض المسجلة'), el('strong', {}, String(payments.length)))
    )
  );

  const quickActions = el('div', { class: 'card' },
    el('h3', { style: 'margin-top:0; color:var(--g-teal-dark);' }, '⚡ إجراءات مالية سريعة'),
    el('p', { class: 'muted', style: 'font-size:13px; margin-bottom:14px;' }, 'سجل العمليات اليومية بسهولة وسرعة:'),
    el('div', { style: 'display:grid; grid-template-columns: repeat(2, 1fr); gap:10px;' },
      el('button', { class: 'button', type: 'button', onclick: () => openPaymentForm(api, null, reloadAll) }, '＋ تحصيل دفعة جديدة'),
      el('button', { class: 'button button-outline', type: 'button', onclick: () => openExpenseForm(api, null, reloadAll) }, '＋ تسجيل مصروف جديد'),
      el('button', { class: 'button button-outline', type: 'button', onclick: () => openTransferForm(api, reloadAll) }, '🔄 تحويل بين الحسابات'),
      el('button', { class: 'button button-outline', type: 'button', onclick: () => openPayrollForm(api, null, reloadAll) }, '💼 صرف راتب موظف')
    )
  );

  const grid2 = el('div', { class: 'fin-grid-2' }, cardBalance, quickActions);

  // Recent 5 payments and recent 5 expenses table preview
  const recentPayments = payments.slice(0, 5).map(p => el('tr', {},
    el('td', {}, studentName(p)),
    el('td', {}, fmtSAR(p.amount)),
    el('td', {}, p.method || 'كاش'),
    el('td', {}, fmtDate(p.paid_on)),
    el('td', {}, el('a', { class: 'button button-outline', style: 'padding:4px 8px; font-size:11px;', href: `../print/templates/receipt.html?payment=${encodeURIComponent(p.id || '')}`, target: '_blank' }, 'إيصال'))
  ));

  const recentExpenses = expenses.slice(0, 5).map(x => el('tr', {},
    el('td', {}, x.description || '—'),
    el('td', {}, categoryLabel(x)),
    el('td', { style: 'color:var(--g-red); font-weight:700;' }, fmtSAR(x.amount)),
    el('td', {}, fmtDate(x.paid_on))
  ));

  const tablesGrid = el('div', { class: 'fin-grid-2', style: 'margin-top:20px;' },
    el('div', { class: 'card' },
      el('div', { style: 'display:flex; justify-content:space-between; align-items:center; margin-bottom:10px;' },
        el('h3', { style: 'margin:0;' }, '💳 آخر الدفعات المحصلة'),
        el('button', { class: 'button button-outline', style: 'font-size:12px;', onclick: () => setQueryTab('payments') }, 'عرض الكل')
      ),
      el('div', { class: 'table-wrap' }, el('table', {},
        el('thead', {}, el('tr', {}, el('th', {}, 'الطالب'), el('th', {}, 'المبلغ'), el('th', {}, 'الطريقة'), el('th', {}, 'التاريخ'), el('th', {}, 'الإيصال'))),
        el('tbody', {}, recentPayments.length ? recentPayments : el('tr', {}, el('td', { colspan: '5', class: 'muted' }, 'لا توجد دفعات مسجلة')))
      ))
    ),
    el('div', { class: 'card' },
      el('div', { style: 'display:flex; justify-content:space-between; align-items:center; margin-bottom:10px;' },
        el('h3', { style: 'margin:0;' }, '🔻 آخر المصروفات المسجلة'),
        el('button', { class: 'button button-outline', style: 'font-size:12px;', onclick: () => setQueryTab('expenses') }, 'عرض الكل')
      ),
      el('div', { class: 'table-wrap' }, el('table', {},
        el('thead', {}, el('tr', {}, el('th', {}, 'البيان'), el('th', {}, 'التصنيف'), el('th', {}, 'المبلغ'), el('th', {}, 'التاريخ'))),
        el('tbody', {}, recentExpenses.length ? recentExpenses : el('tr', {}, el('td', { colspan: '4', class: 'muted' }, 'لا توجد مصروفات مسجلة')))
      ))
    )
  );

  container.append(kpis, grid2, tablesGrid);
}

function renderPayments(api, container, reloadAll) {
  const toolbar = el('div', { class: 'toolbar' },
    el('button', { class: 'button', type: 'button', onclick: () => openPaymentForm(api, null, reloadAll) }, '＋ تسجيل دفعة جديدة'),
    el('button', { class: 'button button-outline', type: 'button', onclick: () => openPlanForm(api, reloadAll) }, '＋ إضافة خطة أقساط')
  );

  const planRows = feePlans.map(plan => el('tr', {},
    el('td', {}, studentName(plan)),
    el('td', { style: 'font-weight:700;' }, fmtSAR(plan.total_amount)),
    el('td', {}, String(plan.count ?? '1')),
    el('td', {}, intervalLabel(plan.interval_days)),
    el('td', {}, fmtDate(plan.start_date))
  ));

  const paymentRows = payments.map(p => {
    const receipt = el('a', { class: 'button button-outline', href: `../print/templates/receipt.html?payment=${encodeURIComponent(p.id || '')}`, target: '_blank', rel: 'noopener' }, '🧾 طباعة السند');
    return el('tr', {},
      el('td', {}, studentName(p)),
      el('td', { style: 'font-weight:700; color:var(--g-teal);' }, fmtSAR(p.amount)),
      el('td', {}, p.method || '—'),
      el('td', {}, fmtDate(p.paid_on)),
      el('td', {}, p.note || '—'),
      el('td', {}, receipt)
    );
  });

  const sectionPayments = el('div', { class: 'card', style: 'margin-bottom:20px;' },
    el('h3', { style: 'margin-top:0;' }, 'سجل الدفعات والمتحصلات'),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {}, el('th', {}, 'الطالب'), el('th', {}, 'المبلغ'), el('th', {}, 'طريقة الدفع'), el('th', {}, 'تاريخ الدفع'), el('th', {}, 'ملاحظة'), el('th', {}, 'سند القبض'))),
      el('tbody', {}, paymentRows.length ? paymentRows : el('tr', {}, el('td', { colspan: '6', class: 'muted' }, 'لا توجد مدفوعات مسجلة بعد')))
    ))
  );

  const sectionPlans = el('div', { class: 'card' },
    el('h3', { style: 'margin-top:0;' }, 'خطط الرسوم والأقساط للطلاب'),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {}, el('th', {}, 'الطالب'), el('th', {}, 'إجمالي المبلغ'), el('th', {}, 'عدد الأقساط'), el('th', {}, 'الفترة'), el('th', {}, 'تاريخ البدء'))),
      el('tbody', {}, planRows.length ? planRows : el('tr', {}, el('td', { colspan: '5', class: 'muted' }, 'لا توجد خطط أقساط بعد')))
    ))
  );

  container.append(toolbar, sectionPayments, sectionPlans);
}

function renderExpenses(api, container, reloadAll) {
  const categoryFilterSelect = el('select', { style: 'max-width:240px;', onchange: (e) => { categoryFilter = e.target.value; renderContent(); } },
    el('option', { value: '' }, 'جميع التصنيفات'),
    ...categories.map(c => el('option', { value: String(c.id) }, c.name))
  );
  categoryFilterSelect.value = categoryFilter;

  const toolbar = el('div', { class: 'toolbar' },
    el('button', { class: 'button', type: 'button', onclick: () => openExpenseForm(api, null, reloadAll) }, '＋ تسجيل مصروف جديد'),
    categoryFilterSelect
  );

  const filtered = expenses.filter(x => !categoryFilter || String(x.category_id) === categoryFilter);
  const rows = filtered.map(x => {
    const editBtn = el('button', { class: 'button button-outline', type: 'button', onclick: () => openExpenseForm(api, x, reloadAll) }, 'تعديل');
    return el('tr', {},
      el('td', {}, fmtDate(x.paid_on)),
      el('td', { style: 'font-weight:700;' }, x.description || '—'),
      el('td', {}, el('span', { class: 'badge' }, categoryLabel(x))),
      el('td', { style: 'color:var(--g-red); font-weight:700;' }, fmtSAR(x.amount)),
      el('td', {}, x.method || '—'),
      el('td', {}, accountLabel(x)),
      el('td', {}, editBtn)
    );
  });

  const totalFiltered = filtered.reduce((s, x) => s + Number(x.amount || 0), 0);

  const tableCard = el('div', { class: 'card' },
    el('div', { style: 'display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;' },
      el('h3', { style: 'margin:0;' }, 'سجل المصروفات التشغيلية'),
      el('div', { style: 'font-weight:700; font-size:15px;' }, `إجمالي المعروض: `, el('span', { style: 'color:var(--g-red);' }, fmtSAR(totalFiltered)))
    ),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {}, el('th', {}, 'التاريخ'), el('th', {}, 'الوصف / البيان'), el('th', {}, 'التصنيف'), el('th', {}, 'المبلغ'), el('th', {}, 'طريقة الدفع'), el('th', {}, 'الحساب المخصوم'), el('th', {}, 'إجراءات'))),
      el('tbody', {}, rows.length ? rows : el('tr', {}, el('td', { colspan: '7', class: 'muted' }, 'لا توجد مصروفات تطابق البحث')))
    ))
  );

  container.append(toolbar, tableCard);
}

function renderAccounts(api, container, reloadAll) {
  const toolbar = el('div', { class: 'toolbar' },
    el('button', { class: 'button', type: 'button', onclick: () => openAccountForm(api, reloadAll) }, '＋ إضافة حساب / خزينة'),
    el('button', { class: 'button button-outline', type: 'button', onclick: () => openTransferForm(api, reloadAll) }, '🔄 تحويل بين الحسابات')
  );

  const accountRows = accounts.map(a => el('tr', {},
    el('td', { style: 'font-weight:700;' }, a.name || '—'),
    el('td', {}, KINDS[a.kind] || a.kind || '—'),
    el('td', {}, fmtSAR(a.opening_balance)),
    el('td', { style: 'font-weight:900; color:var(--g-teal-dark); font-size:16px;' }, fmtSAR(a.balance))
  ));

  const entryRows = entries.map(e => el('tr', {},
    el('td', {}, fmtDate(e.occurred_on)),
    el('td', {}, accountLabel(e)),
    el('td', {}, el('span', { class: `badge ${e.entry_type === 'out' || e.entry_type === 'transfer_out' ? 'red' : ''}` }, ENTRY_TYPES[e.entry_type] || e.entry_type || '—')),
    el('td', { style: 'font-weight:700;' }, fmtSAR(e.amount)),
    el('td', {}, e.note || e.description || '—')
  ));

  const accountsCard = el('div', { class: 'card', style: 'margin-bottom:20px;' },
    el('h3', { style: 'margin-top:0;' }, 'الحسابات والخزائن المالية'),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {}, el('th', {}, 'اسم الحساب / الخزينة'), el('th', {}, 'النوع'), el('th', {}, 'الرصيد الافتتاحي'), el('th', {}, 'الرصيد الحالي'))),
      el('tbody', {}, accountRows.length ? accountRows : el('tr', {}, el('td', { colspan: '4', class: 'muted' }, 'لا توجد حسابات مسجلة بعد')))
    ))
  );

  const entriesCard = el('div', { class: 'card' },
    el('h3', { style: 'margin-top:0;' }, 'دفتر الأستاذ والحركات المالية المباشرة'),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {}, el('th', {}, 'التاريخ'), el('th', {}, 'الحساب'), el('th', {}, 'نوع الحركة'), el('th', {}, 'المبلغ'), el('th', {}, 'البيان'))),
      el('tbody', {}, entryRows.length ? entryRows : el('tr', {}, el('td', { colspan: '5', class: 'muted' }, 'لا توجد حركات مالية مسجلة بعد')))
    ))
  );

  container.append(toolbar, accountsCard, entriesCard);
}

function renderPayroll(api, container, reloadAll) {
  const toolbar = el('div', { class: 'toolbar' },
    el('button', { class: 'button', type: 'button', onclick: () => openPayrollForm(api, null, reloadAll) }, '＋ تسجيل وصرف مسير راتب')
  );

  const rows = payrollRuns.map(run => {
    const member = staff.find(s => s.id === run.staff_id) || {};
    return el('tr', {},
      el('td', { style: 'font-weight:700;' }, member.name || run.staff_name || '—'),
      el('td', {}, member.role_title || '—'),
      el('td', {}, run.month || '—'),
      el('td', {}, fmtSAR(run.base)),
      el('td', { style: 'color:#10b981;' }, fmtSAR(run.allowances + run.bonus)),
      el('td', { style: 'color:var(--g-red);' }, fmtSAR(run.deductions + run.advance_deducted)),
      el('td', { style: 'font-weight:900; color:var(--g-teal-dark); font-size:15px;' }, fmtSAR(run.net))
    );
  });

  const totalPayroll = payrollRuns.reduce((s, r) => s + Number(r.net || 0), 0);

  const card = el('div', { class: 'card' },
    el('div', { style: 'display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;' },
      el('h3', { style: 'margin:0;' }, '💼 مسير رواتب الموظفين والمعلمين'),
      el('div', { style: 'font-weight:700; font-size:15px;' }, `إجمالي المسير: `, el('span', { style: 'color:var(--g-teal);' }, fmtSAR(totalPayroll)))
    ),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {}, el('th', {}, 'الموظف'), el('th', {}, 'المسمى'), el('th', {}, 'الشهر'), el('th', {}, 'الأساسي'), el('th', {}, 'البدلات والمكافآت'), el('th', {}, 'الخصومات والسلف'), el('th', {}, 'صافي المستحق'))),
      el('tbody', {}, rows.length ? rows : el('tr', {}, el('td', { colspan: '7', class: 'muted' }, 'لا توجد مسيرات رواتب مسجلة حتى الآن')))
    ))
  );

  container.append(toolbar, card);
}

function renderReceivables(api, container, reloadAll) {
  const list = Array.isArray(financeReport.outstanding_by_student) ? financeReport.outstanding_by_student : [];
  const totalOut = list.reduce((s, x) => s + Number(x.outstanding || 0), 0);

  const rows = list.map(item => {
    const student = students.find(s => s.id === item.student_id) || {};
    const phone = student.guardian_phone || student.father_phone || student.mother_phone || '';
    const cleanPhone = phone.replace(/\D/g, '');
    const intl = cleanPhone.startsWith('0') ? '966' + cleanPhone.slice(1) : cleanPhone;
    
    const whatsappBtn = cleanPhone ? el('a', {
      class: 'button button-outline',
      style: 'padding:4px 8px; font-size:11px;',
      href: `https://wa.me/${intl}?text=${encodeURIComponent(`السلام عليكم ورحمة الله، نود تذكيركم بوجود مستحقات مالية متبقية للطالب (${item.student_name}) بمبلغ ${fmtSAR(item.outstanding)} لدى مركز غراس.`)}`,
      target: '_blank'
    }, '💬 تذكير واتساب') : el('span', { class: 'muted', style: 'font-size:11px;' }, 'لا يوجد هاتف');

    const payBtn = el('button', {
      class: 'button',
      style: 'padding:4px 8px; font-size:11px;',
      type: 'button',
      onclick: () => openPaymentForm(api, item.student_id, reloadAll)
    }, '＋ تحصيل');

    return el('tr', {},
      el('td', { style: 'font-weight:700;' }, item.student_name || '—'),
      el('td', {}, fmtSAR(item.total_planned)),
      el('td', { style: 'color:#10b981;' }, fmtSAR(item.total_paid)),
      el('td', { style: 'font-weight:900; color:var(--g-red); font-size:15px;' }, fmtSAR(item.outstanding)),
      el('td', {}, whatsappBtn),
      el('td', {}, payBtn)
    );
  });

  const card = el('div', { class: 'card' },
    el('div', { style: 'display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;' },
      el('h3', { style: 'margin:0;' }, '📌 سجل الذمم والمتأخرات ومتابعة التحصيل'),
      el('div', { style: 'font-weight:700; font-size:15px;' }, `إجمالي المتبقي: `, el('span', { style: 'color:var(--g-red);' }, fmtSAR(totalOut)))
    ),
    el('p', { class: 'muted', style: 'font-size:13px; margin-bottom:12px;' }, 'قائمة بالطلاب المتبقي عليهم مبالغ مالية مرتبة من الأعلى مديونية للمتابعة والتحصيل الفوري:'),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {}, el('th', {}, 'اسم الطالب'), el('th', {}, 'إجمالي الرسوم'), el('th', {}, 'المسدد'), el('th', {}, 'المتبقي المستحق'), el('th', {}, 'تواصل ولي الأمر'), el('th', {}, 'إجراء'))),
      el('tbody', {}, rows.length ? rows : el('tr', {}, el('td', { colspan: '6', class: 'muted' }, 'الحمد لله، لا توجد أي متأخرات معلقة على الطلاب!')))
    ))
  );

  container.append(card);
}

function renderReportsTab(api, container) {
  const fin = financeReport || {};
  const monthInput = el('input', { type: 'month', value: currentMonth(), style: 'max-width:200px;' });
  
  const reportBody = el('div', { style: 'margin-top:16px;' });

  const buildReportView = () => {
    reportBody.replaceChildren(
      el('div', { class: 'card' },
        el('h3', { style: 'margin-top:0; color:var(--g-teal-dark);' }, `تقرير الإقفال والمطابقة المالية — شهر ${monthInput.value}`),
        el('div', { class: 'kpis', style: 'margin-top:16px;' },
          el('div', { class: 'kpi', style: 'border-right-color:var(--g-teal);' }, el('span', { class: 'muted' }, 'إجمالي الإيرادات المحصلة'), el('strong', {}, fmtSAR(fin.collected || 0))),
          el('div', { class: 'kpi', style: 'border-right-color:var(--g-red);' }, el('span', { class: 'muted' }, 'إجمالي المصروفات'), el('strong', {}, fmtSAR(fin.expenses || 0))),
          el('div', { class: 'kpi', style: 'border-right-color:#7851c9;' }, el('span', { class: 'muted' }, 'مسير الرواتب'), el('strong', {}, fmtSAR(fin.payroll || 0))),
          el('div', { class: 'kpi', style: 'border-right-color:#10b981;' }, el('span', { class: 'muted' }, 'صافي الشهر'), el('strong', {}, fmtSAR(fin.net || 0)))
        ),
        el('div', { class: 'form-actions', style: 'margin-top:20px;' },
          el('button', { class: 'button', type: 'button', onclick: () => window.print() }, '🖨️ طباعة التقرير المالي المعتمد'),
          el('a', { class: 'button button-outline', href: `#/reports?tab=monthly`, style: 'text-decoration:none;' }, 'عرض التقارير الإدارية الشاملة')
        )
      )
    );
  };

  monthInput.addEventListener('change', buildReportView);
  buildReportView();

  container.append(
    el('div', { class: 'toolbar' }, el('label', { style: 'display:flex; align-items:center; gap:8px;' }, 'اختر الشهر:', monthInput)),
    reportBody
  );
}

// -------------------------------------------------------------
// Main Render Entry
// -------------------------------------------------------------

let currentContainer = null;
let currentApi = null;

function renderContent() {
  if (!currentContainer || !currentApi) return;

  const subViewContainer = currentContainer.querySelector('#finance-subview');
  if (!subViewContainer) return;
  subViewContainer.replaceChildren();

  // Sync tab active states
  currentContainer.querySelectorAll('.tab-btn').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.tab === activeTab);
  });

  if (activeTab === 'overview') renderOverview(currentApi, subViewContainer, () => reloadAll(currentApi));
  else if (activeTab === 'payments') renderPayments(currentApi, subViewContainer, () => reloadAll(currentApi));
  else if (activeTab === 'expenses') renderExpenses(currentApi, subViewContainer, () => reloadAll(currentApi));
  else if (activeTab === 'accounts') renderAccounts(currentApi, subViewContainer, () => reloadAll(currentApi));
  else if (activeTab === 'payroll') renderPayroll(currentApi, subViewContainer, () => reloadAll(currentApi));
  else if (activeTab === 'receivables') renderReceivables(currentApi, subViewContainer, () => reloadAll(currentApi));
  else if (activeTab === 'reports') renderReportsTab(currentApi, subViewContainer);
}

async function reloadAll(api) {
  try {
    const results = await Promise.allSettled([
      api.get('students'),
      api.get('fee-plans'),
      api.get('payments'),
      api.get('installments'),
      api.get('expenses'),
      api.get('expense-categories'),
      api.get('ledger-accounts'),
      api.get('ledger-entries'),
      api.get('staff'),
      api.get('payroll-runs'),
      api.get('reports/finance')
    ]);

    const [sR, fpR, pR, iR, eR, ecR, aR, leR, stR, prR, fR] = results;
    if (sR.status === 'fulfilled') students = toList(sR.value);
    if (fpR.status === 'fulfilled') feePlans = toList(fpR.value);
    if (pR.status === 'fulfilled') payments = toList(pR.value);
    if (iR.status === 'fulfilled') installments = toList(iR.value);
    if (eR.status === 'fulfilled') expenses = toList(eR.value);
    if (ecR.status === 'fulfilled') categories = toList(ecR.value);
    if (aR.status === 'fulfilled') accounts = toList(aR.value);
    if (leR.status === 'fulfilled') entries = toList(leR.value);
    if (stR.status === 'fulfilled') staff = toList(stR.value);
    if (prR.status === 'fulfilled') payrollRuns = toList(prR.value);
    if (fR.status === 'fulfilled') financeReport = fR.value || {};
  } catch (error) {
    toast('تعذر تحديث بعض البيانات المالية', true);
  }
  renderContent();
}

export async function render(container, api) {
  currentContainer = container;
  currentApi = api;
  container.replaceChildren();

  const isManager = !api.currentUser || api.currentUser.role === 'manager';

  const requestedTab = getQueryTab();
  if (requestedTab) {
    activeTab = requestedTab;
  }

  // Header
  const header = el('div', { class: 'view-header' },
    el('div', {},
      el('h1', {}, '💰 الإدارة المالية الشاملة'),
      el('p', { class: 'muted', style: 'margin:4px 0 0;' }, 'لوحة مركزية موحدة لإدارة الرسوم، المصروفات، الخزائن، والرواتب')
    )
  );

  // Tabs Bar
  const tabs = [
    { id: 'overview', label: '📊 نظرة عامة ومؤشرات' },
    { id: 'payments', label: '💳 الرسوم والمدفوعات', count: payments.length },
    { id: 'expenses', label: '🔻 المصروفات', count: expenses.length },
    { id: 'accounts', label: '🏦 الخزائن والبنوك', count: accounts.length },
    { id: 'receivables', label: '📌 الذمم والمتأخرات' },
    ...(isManager ? [{ id: 'payroll', label: '💼 مسير الرواتب' }] : []),
    { id: 'reports', label: '📑 المطابقة والتقارير' }
  ];

  const tabsNav = el('div', { class: 'tabs-bar' },
    ...tabs.map(t => {
      const btn = el('button', {
        class: `tab-btn ${t.id === activeTab ? 'active' : ''}`,
        type: 'button',
        'data-tab': t.id,
        onclick: () => setQueryTab(t.id)
      }, t.label);
      return btn;
    })
  );

  const subview = el('div', { id: 'finance-subview' }, el('p', { class: 'muted' }, 'جارٍ تحميل البيانات المالية...'));

  container.append(header, tabsNav, subview);

  await reloadAll(api);
}
