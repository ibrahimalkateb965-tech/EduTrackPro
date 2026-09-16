import { el, toast, fmtDate } from '../ui.js';

const GROUPS = ['الصباح', 'المساء', 'الإنجليزي', 'القدرات'];
const STATUSES = ['حاضر', 'غائب', 'متأخر', 'مستأذن'];

let students = [];
let entries = new Map();
let selectedDate = fmtDate(new Date());
let groupFilter = '';
let tbody = null;
let printLink = null;

function toList(data) {
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.items)) return data.items;
  return [];
}

function reportHref() {
  return `../print/templates/attendance_report.html?from=${selectedDate}&to=${selectedDate}`;
}

function attendanceRecords(report) {
  if (Array.isArray(report)) return report;
  return ['attendance', 'items', 'rows', 'records'].map(key => report?.[key]).find(Array.isArray) || [];
}

function setEntry(studentId, key, value) {
  const entry = entries.get(studentId) || { status: 'حاضر', note: '' };
  entry[key] = value;
  entries.set(studentId, entry);
}

function attendanceRow(student) {
  const entry = entries.get(student.id) || { status: 'حاضر', note: '' };
  const status = el('select', { 'aria-label': `حالة ${student.name || ''}` });
  STATUSES.forEach(value => status.append(el('option', { value }, value)));
  status.value = entry.status || 'حاضر';
  status.addEventListener('change', () => setEntry(student.id, 'status', status.value));
  const note = el('input', { type: 'text', value: entry.note || '', placeholder: 'ملاحظة اختيارية', 'aria-label': `ملاحظة ${student.name || ''}` });
  note.addEventListener('input', () => setEntry(student.id, 'note', note.value));
  return el('tr', {},
    el('td', {}, student.name || '—'),
    el('td', {}, student.group_name || '—'),
    el('td', {}, status),
    el('td', {}, note)
  );
}

function paint() {
  const list = students.filter(student => !groupFilter || student.group_name === groupFilter);
  const rows = list.map(student => attendanceRow(student));
  const emptyText = students.length ? 'لا يوجد طلاب في هذه المجموعة' : 'لا يوجد طلاب نشطون بعد';
  tbody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '4', class: 'muted' }, emptyText))]));
}

async function loadEntries(api) {
  const defaults = new Map(students.map(student => [student.id, { status: 'حاضر', note: '' }]));
  try {
    const report = await api.get(`reports/attendance?from=${selectedDate}&to=${selectedDate}`);
    const byStudent = new Map(attendanceRecords(report).map(record => [String(record.student_id), record]));
    entries = new Map(students.map(student => {
      const record = byStudent.get(String(student.id));
      return [student.id, { status: record?.status || 'حاضر', note: record?.note || '' }];
    }));
  } catch (error) {
    toast(error.message, true);
    entries = defaults;
  }
}

async function saveAttendance(api, button) {
  button.disabled = true;
  const rows = students.map(student => {
    const entry = entries.get(student.id) || {};
    return { student_id: student.id, date: selectedDate, status: entry.status || 'حاضر', note: entry.note || null };
  });
  try {
    await api.post('attendance/students', rows);
    toast('تم حفظ الحضور');
  } catch (error) {
    toast(error.message, true);
  }
  button.disabled = false;
}

export async function render(container, api) {
  students = [];
  entries = new Map();
  selectedDate = fmtDate(new Date());
  groupFilter = '';
  container.replaceChildren();
  const dateInput = el('input', { type: 'date', value: selectedDate, 'aria-label': 'تاريخ الحضور' });
  dateInput.addEventListener('change', async () => {
    if (!dateInput.value) return;
    selectedDate = dateInput.value;
    printLink.setAttribute('href', reportHref());
    await loadEntries(api);
    paint();
  });
  const groupSelect = el('select', { 'aria-label': 'تصفية بالمجموعة' });
  groupSelect.append(el('option', { value: '' }, 'كل المجموعات'));
  GROUPS.forEach(group => groupSelect.append(el('option', { value: group }, group)));
  groupSelect.addEventListener('change', () => { groupFilter = groupSelect.value; paint(); });
  const saveButton = el('button', { class: 'button', type: 'button' }, 'حفظ الحضور');
  saveButton.addEventListener('click', () => saveAttendance(api, saveButton));
  printLink = el('a', { class: 'button button-outline', href: reportHref(), target: '_blank', rel: 'noopener' }, 'تقرير الحضور');
  tbody = el('tbody', {}, el('tr', {}, el('td', { colspan: '4', class: 'muted' }, 'جارٍ التحميل...')));
  container.append(
    el('div', { class: 'view-header' }, el('h1', {}, 'الحضور والغياب')),
    el('div', { class: 'toolbar' }, dateInput, groupSelect, saveButton, printLink),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {},
        el('th', {}, 'الطالب'),
        el('th', {}, 'المجموعة'),
        el('th', {}, 'الحالة'),
        el('th', {}, 'ملاحظة')
      )),
      tbody
    ))
  );
  try {
    students = toList(await api.get('students')).filter(student => (student.status || 'active') === 'active');
  } catch (error) {
    toast(error.message, true);
  }
  await loadEntries(api);
  paint();
}
