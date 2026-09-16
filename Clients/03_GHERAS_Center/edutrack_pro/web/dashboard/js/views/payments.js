import { el, toast, modal, fmtSAR, fmtDate } from '../ui.js';

const METHODS = ['كاش', 'تحويل بنكي', 'تابي', 'تقسيط المركز', 'مدى', 'Apple Pay', 'بطاقة'];
const INTERVALS = [[30, 'شهري (كل 30 يوم)'], [14, 'كل 14 يوم'], [7, 'أسبوعي (كل 7 أيام)']];

let students = [];
let feePlans = [];
let payments = [];
let installments = [];
let finance = {};
let activeTab = 'plans';
let plansTabButton = null;
let paymentsTabButton = null;
let plansPanel = null;
let paymentsPanel = null;
let plansBody = null;
let paymentsBody = null;

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

function activeStudents() {
  return students.filter(student => (student.status || 'active') === 'active');
}

function studentName(record) {
  if (record?.student_name) return record.student_name;
  const student = students.find(item => item.id === record?.student_id);
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

function balanceFor(studentId) {
  if (!studentId) return null;
  const source = [finance.outstanding_by_student, finance.student_balances, finance.balances]
    .find(value => value && typeof value === 'object') || null;
  if (!source) return null;
  if (Array.isArray(source)) {
    const row = source.find(item => String(item.student_id) === String(studentId));
    return row ? Number(row.outstanding ?? row.balance ?? 0) : 0;
  }
  const value = source[studentId] ?? source[String(studentId)];
  return value === undefined ? null : Number(value);
}

function paintPlans() {
  const rows = feePlans.map(plan => el('tr', {},
    el('td', {}, studentName(plan)),
    el('td', {}, fmtSAR(plan.total_amount)),
    el('td', {}, String(plan.count ?? '—')),
    el('td', {}, intervalLabel(plan.interval_days)),
    el('td', {}, fmtDate(plan.start_date))
  ));
  plansBody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '5', class: 'muted' }, 'لا توجد خطط أقساط بعد'))]));
}

function paintPayments() {
  const rows = payments.map(payment => {
    const balance = balanceFor(payment.student_id);
    const receipt = el('a', { class: 'button button-outline', href: `../print/templates/receipt.html?payment=${encodeURIComponent(payment.id || '')}`, target: '_blank', rel: 'noopener' }, 'طباعة الإيصال');
    return el('tr', {},
      el('td', {}, studentName(payment)),
      el('td', {}, fmtSAR(payment.amount)),
      el('td', {}, payment.method || '—'),
      el('td', {}, fmtDate(payment.paid_on)),
      el('td', {}, balance === null ? '—' : fmtSAR(balance)),
      el('td', {}, receipt)
    );
  });
  paymentsBody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '6', class: 'muted' }, 'لا توجد مدفوعات مسجلة'))]));
}

function syncTabs() {
  plansTabButton.className = activeTab === 'plans' ? 'button' : 'button button-outline';
  paymentsTabButton.className = activeTab === 'payments' ? 'button' : 'button button-outline';
  plansTabButton.setAttribute('aria-selected', String(activeTab === 'plans'));
  paymentsTabButton.setAttribute('aria-selected', String(activeTab === 'payments'));
  plansPanel.hidden = activeTab !== 'plans';
  paymentsPanel.hidden = activeTab !== 'payments';
}

async function refreshPlans(api) {
  try { feePlans = toList(await api.get('fee-plans')); } catch (error) { toast(error.message, true); }
  paintPlans();
}

async function refreshPayments(api) {
  const results = await Promise.allSettled([api.get('payments'), api.get('reports/finance'), api.get('installments')]);
  const [paymentsRes, financeRes, installmentsRes] = results;
  if (paymentsRes.status === 'fulfilled') payments = toList(paymentsRes.value); else toast(paymentsRes.reason?.message || 'تعذر تحميل المدفوعات', true);
  if (financeRes.status === 'fulfilled') finance = financeRes.value || {}; else toast(financeRes.reason?.message || 'تعذر تحميل البيانات المالية', true);
  if (installmentsRes.status === 'fulfilled') installments = toList(installmentsRes.value); else toast(installmentsRes.reason?.message || 'تعذر تحميل الأقساط', true);
  paintPayments();
}

function openPlanForm(api) {
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
  const dialog = modal('إضافة خطة أقساط', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    Object.keys(payload).forEach(key => { payload[key] = String(payload[key]).trim(); });
    payload.total_amount = Number(payload.total_amount);
    payload.count = Number(payload.count);
    payload.interval_days = Number(payload.interval_days);
    try {
      await api.post('fee-plans', payload);
      dialog.close();
      toast('تمت إضافة خطة الأقساط');
      await refreshPlans(api);
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

function openPaymentForm(api) {
  const studentOptions = [['', 'اختر الطالب']].concat(activeStudents().map(student => [student.id, student.name]));
  const studentSelect = select('student_id', studentOptions, '', true);
  const installmentSelect = select('installment_id', [['', 'بدون قسط']], '');
  const refreshInstallments = () => {
    installmentSelect.replaceChildren(el('option', { value: '' }, 'بدون قسط'));
    const studentId = studentSelect.value;
    const list = studentId ? installments.filter(item => planFor(item.fee_plan_id)?.student_id === studentId) : [];
    if (!list.length) {
      installmentSelect.append(el('option', { value: '', disabled: 'disabled' }, studentId ? 'لا توجد أقساط لهذا الطالب' : 'اختر الطالب لعرض أقساطه'));
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
    el('label', {}, el('span', {}, 'المبلغ'), el('input', { name: 'amount', type: 'number', min: '0', step: '0.01', required: 'required' })),
    el('label', {}, el('span', {}, 'طريقة الدفع'), select('method', METHODS.map(method => [method, method]), 'كاش', true)),
    el('label', {}, el('span', {}, 'تاريخ الدفع'), el('input', { name: 'paid_on', type: 'date', required: 'required', value: fmtDate(new Date()) })),
    el('label', {}, el('span', {}, 'ملاحظة'), el('textarea', { name: 'note' }, ''))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, 'تسجيل');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions' }, submit, cancel));
  const dialog = modal('تسجيل دفعة', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    Object.keys(payload).forEach(key => { payload[key] = String(payload[key]).trim(); });
    payload.amount = Number(payload.amount);
    payload.installment_id = payload.installment_id || null;
    try {
      await api.post('payments', payload);
      dialog.close();
      toast('تم تسجيل الدفعة');
      await refreshPayments(api);
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

async function loadAll(api) {
  const results = await Promise.allSettled([
    api.get('students'),
    api.get('fee-plans'),
    api.get('payments'),
    api.get('installments'),
    api.get('reports/finance')
  ]);
  const [studentsRes, plansRes, paymentsRes, installmentsRes, financeRes] = results;
  if (studentsRes.status === 'fulfilled') students = toList(studentsRes.value); else toast(studentsRes.reason?.message || 'تعذر تحميل قائمة الطلاب', true);
  if (plansRes.status === 'fulfilled') feePlans = toList(plansRes.value); else toast(plansRes.reason?.message || 'تعذر تحميل خطط الأقساط', true);
  if (paymentsRes.status === 'fulfilled') payments = toList(paymentsRes.value); else toast(paymentsRes.reason?.message || 'تعذر تحميل المدفوعات', true);
  if (installmentsRes.status === 'fulfilled') installments = toList(installmentsRes.value); else toast(installmentsRes.reason?.message || 'تعذر تحميل الأقساط', true);
  if (financeRes.status === 'fulfilled') finance = financeRes.value || {}; else toast(financeRes.reason?.message || 'تعذر تحميل البيانات المالية', true);
  paintPlans();
  paintPayments();
}

export async function render(container, api) {
  students = [];
  feePlans = [];
  payments = [];
  installments = [];
  finance = {};
  activeTab = 'plans';
  container.replaceChildren();
  plansTabButton = el('button', { class: 'button', type: 'button', role: 'tab', 'aria-selected': 'true' }, 'خطط الأقساط');
  paymentsTabButton = el('button', { class: 'button button-outline', type: 'button', role: 'tab', 'aria-selected': 'false' }, 'المدفوعات');
  plansTabButton.addEventListener('click', () => { activeTab = 'plans'; syncTabs(); });
  paymentsTabButton.addEventListener('click', () => { activeTab = 'payments'; syncTabs(); });
  const addPlanButton = el('button', { class: 'button', type: 'button' }, 'إضافة خطة أقساط');
  addPlanButton.addEventListener('click', () => openPlanForm(api));
  const addPaymentButton = el('button', { class: 'button', type: 'button' }, 'تسجيل دفعة');
  addPaymentButton.addEventListener('click', () => openPaymentForm(api));
  plansBody = el('tbody', {}, el('tr', {}, el('td', { colspan: '5', class: 'muted' }, 'جارٍ التحميل...')));
  paymentsBody = el('tbody', {}, el('tr', {}, el('td', { colspan: '6', class: 'muted' }, 'جارٍ التحميل...')));
  plansPanel = el('section', {},
    el('div', { class: 'toolbar' }, addPlanButton),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {},
        el('th', {}, 'الطالب'),
        el('th', {}, 'إجمالي المبلغ'),
        el('th', {}, 'عدد الأقساط'),
        el('th', {}, 'الفاصل بين الأقساط'),
        el('th', {}, 'تاريخ البدء')
      )),
      plansBody
    ))
  );
  paymentsPanel = el('section', { hidden: 'hidden' },
    el('div', { class: 'toolbar' }, addPaymentButton),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {},
        el('th', {}, 'الطالب'),
        el('th', {}, 'المبلغ'),
        el('th', {}, 'طريقة الدفع'),
        el('th', {}, 'تاريخ الدفع'),
        el('th', {}, 'المتبقي على الطالب'),
        el('th', {}, 'إجراءات')
      )),
      paymentsBody
    ))
  );
  container.append(
    el('div', { class: 'view-header' }, el('h1', {}, 'المدفوعات')),
    el('div', { class: 'toolbar', role: 'tablist' }, plansTabButton, paymentsTabButton),
    plansPanel,
    paymentsPanel
  );
  syncTabs();
  await loadAll(api);
}
