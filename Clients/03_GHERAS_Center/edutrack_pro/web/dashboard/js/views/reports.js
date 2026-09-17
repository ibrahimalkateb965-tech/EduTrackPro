import { el, toast, fmtDate, fmtSAR } from '../ui.js';

const PRINT_BASE = '../print/templates/';

const DAILY_LABELS = {
  date: 'التاريخ',
  day: 'التاريخ',
  students_count: 'عدد الطلاب',
  new_students: 'طلاب جدد',
  rooms_count: 'عدد القاعات',
  teachers_count: 'عدد المعلمين',
  attendance_count: 'الحضور',
  absence_count: 'الغياب',
  lessons_count: 'عدد الحصص',
  payments_count: 'عدد الدفعات',
  payments_total: 'إجمالي المقبوضات',
  expenses_count: 'عدد المصروفات',
  expenses_total: 'إجمالي المصروفات',
  net: 'الصافي'
};

const DOCS = [
  { file: 'receipt', title: 'سند قبض', description: 'طباعة سند قبض محدد برقم السند أو رقم الدفعة.', fields: [{ kind: 'number', query: 'payment', label: 'رقم السند' }] },
  { file: 'guardian_card', title: 'بطاقة ولي الأمر', description: 'بطاقة تعريف ولي أمر الطالب.', fields: [{ kind: 'student', query: 'student', label: 'الطالب' }] },
  { file: 'excellence_certificate', title: 'شهادة تميز', description: 'شهادة تميز لطالب مع ذكر سبب التميز.', fields: [{ kind: 'student', query: 'student', label: 'الطالب' }, { kind: 'reason', query: 'reason', label: 'سبب التميز' }] },
  { file: 'student_report', title: 'تقرير الطالب', description: 'تقرير شامل لأداء الطالب.', fields: [{ kind: 'student', query: 'student', label: 'الطالب' }] },
  { file: 'admin_report', title: 'التقرير الإداري', description: 'تقرير إداري لفترة زمنية محددة.', fields: [{ kind: 'date', query: 'from', label: 'من تاريخ' }, { kind: 'date', query: 'to', label: 'إلى تاريخ' }] },
  { file: 'monthly_report', title: 'التقرير الشهري', description: 'الملخص المالي والأكاديمي لشهر كامل.', fields: [{ kind: 'month', query: 'month', label: 'الشهر' }] },
  { file: 'schedule', title: 'الجدول الدراسي', description: 'الجدول الأسبوعي لحصص القاعة.', fields: [{ kind: 'room', query: 'room', label: 'القاعة' }] },
  { file: 'attendance_report', title: 'تقرير الحضور', description: 'كشف حضور الطلاب لفترة زمنية.', fields: [{ kind: 'date', query: 'from', label: 'من تاريخ' }, { kind: 'date', query: 'to', label: 'إلى تاريخ' }] },
  { file: 'student_receipt', title: 'كشف حساب الطالب', description: 'كشف الحساب المالي للطالب.', fields: [{ kind: 'student', query: 'student', label: 'الطالب' }] },
  { file: 'lesson_log', title: 'سجل الحصص', description: 'سجل حصص القاعة لفترة زمنية.', fields: [{ kind: 'room', query: 'room', label: 'القاعة' }, { kind: 'date', query: 'from', label: 'من تاريخ' }, { kind: 'date', query: 'to', label: 'إلى تاريخ' }] },
  { file: 'statistics_report', title: 'التقرير الإحصائي', description: 'إحصائيات الأداء لفترة زمنية.', fields: [{ kind: 'date', query: 'from', label: 'من تاريخ' }, { kind: 'date', query: 'to', label: 'إلى تاريخ' }] }
];

let students = [];
let rooms = [];

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

function studentOptions() {
  return [['', 'اختر الطالب'], ...students.map(student => [student.id, student.name || `طالب ${student.id}`])];
}

function roomOptions() {
  return [['', 'اختر القاعة'], ...rooms.map(room => [room.id, room.name || `قاعة ${room.id}`])];
}

function fieldControl(field) {
  if (field.kind === 'student') return select(field.query, studentOptions(), '', true);
  if (field.kind === 'room') return select(field.query, roomOptions(), '', true);
  if (field.kind === 'date') return el('input', { name: field.query, type: 'date', required: 'required' });
  if (field.kind === 'month') return el('input', { name: field.query, type: 'month', value: currentMonth(), required: 'required' });
  if (field.kind === 'reason') return el('input', { name: field.query, type: 'text', required: 'required', placeholder: 'مثال: التميز في حفظ القرآن' });
  return el('input', { name: field.query, type: 'number', min: '1', step: '1', required: 'required', placeholder: 'رقم الدفعة' });
}

function docCard(doc) {
  const controls = new Map();
  const fields = doc.fields.map(field => {
    const control = fieldControl(field);
    controls.set(field.query, control);
    return el('label', {}, el('span', {}, field.label), control);
  });
  const print = el('button', { class: 'button', type: 'button' }, 'طباعة');
  print.addEventListener('click', () => {
    const params = new URLSearchParams();
    for (const field of doc.fields) {
      const value = String(controls.get(field.query).value || '').trim();
      if (!value) { toast('أكمل الحقول المطلوبة أولا', true); return; }
      params.set(field.query, value);
    }
    window.open(`${PRINT_BASE}${doc.file}.html?${params.toString()}`, '_blank');
  });
  return el('article', { class: 'card' },
    el('h3', {}, doc.title),
    el('p', { class: 'muted' }, doc.description),
    ...fields,
    el('div', { class: 'form-actions' }, print)
  );
}

function fmtDateTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  const day = new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(date);
  const time = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit' }).format(date);
  return `${day} ${time}`;
}

function auditRow(entry) {
  return el('tr', {},
    el('td', {}, fmtDateTime(entry.at)),
    el('td', {}, entry.actor || '—'),
    el('td', {}, entry.action || '—'),
    el('td', {}, entry.entity || '—')
  );
}

function statItems(data) {
  const source = Array.isArray(data) ? data[0] : data;
  if (!source || typeof source !== 'object') return [];
  return Object.entries(source)
    .filter(([, value]) => typeof value === 'number' || typeof value === 'string')
    .map(([key, value]) => {
      const label = DAILY_LABELS[key] || key;
      let shown = String(value);
      if (typeof value === 'number' && /total|amount|net|balance|revenue/.test(key)) shown = fmtSAR(value);
      else if (key === 'date' || key === 'day') shown = fmtDate(value);
      return [label, shown];
    });
}

function renderDaily(daily) {
  const items = statItems(daily);
  const children = items.length
    ? items.map(([label, value]) => el('div', { class: 'stat' }, el('dt', {}, label), el('dd', {}, value)))
    : [el('div', { class: 'stat' }, el('dt', { class: 'muted' }, 'الملخص اليومي'), el('dd', { class: 'muted' }, 'لا توجد بيانات'))];
  return el('dl', { class: 'stats' }, ...children);
}

export async function render(container, api) {
  students = [];
  rooms = [];
  let daily = null;
  let audit = [];

  try { students = toList(await api.get('students')); } catch (error) { toast(error.message, true); }
  try { rooms = toList(await api.get('rooms')); } catch (error) { toast(error.message, true); }
  try { daily = await api.get('reports/daily'); } catch (error) { toast(error.message, true); }
  try { audit = toList(await api.get('audit-log')); } catch (error) { toast(error.message, true); }

  const dailySection = el('section', { class: 'panel' },
    el('div', { class: 'panel-head' }, el('h2', {}, 'ملخص اليوم')),
    renderDaily(daily)
  );

  const isFinanceAllowed = !api.currentUser || api.currentUser.role === 'manager' || api.currentUser.permissions?.finance;
  const financeDocs = ['receipt', 'admin_report', 'monthly_report', 'student_receipt', 'statistics_report'];
  const allowedDocs = DOCS.filter(doc => isFinanceAllowed || !financeDocs.includes(doc.file));

  const printSection = el('section', { class: 'panel' },
    el('div', { class: 'panel-head' }, el('h2', {}, 'مستندات الطباعة')),
    el('div', { class: 'cards-grid' }, ...allowedDocs.map(docCard))
  );

  const auditBody = el('tbody');
  const auditRows = audit.map(auditRow);
  auditBody.replaceChildren(...(auditRows.length ? auditRows : [el('tr', {}, el('td', { colspan: '4', class: 'muted' }, 'لا توجد عمليات مسجلة بعد'))]));

  const auditSection = el('section', { class: 'panel' },
    el('div', { class: 'panel-head' }, el('h2', {}, 'سجل العمليات')),
    el('table', { class: 'table' },
      el('thead', {}, el('tr', {},
        el('th', {}, 'الوقت'),
        el('th', {}, 'المستخدم'),
        el('th', {}, 'العملية'),
        el('th', {}, 'الكيان')
      )),
      auditBody
    )
  );

  container.replaceChildren(dailySection, printSection, auditSection);
}
