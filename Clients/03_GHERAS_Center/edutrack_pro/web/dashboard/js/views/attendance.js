import { el, toast, fmtDate } from '../ui.js';

const GROUPS = ['الصباح', 'المساء', 'الإنجليزي', 'القدرات'];
const STATUSES = ['حاضر', 'غائب', 'متأخر', 'مستأذن'];
const SUBJECTS = ['القرآن', 'التجويد', 'الحديث', 'اللغة الإنجليزية', 'الرياضيات', 'عام'];

let students = [];
let entries = new Map();
let evalEntries = new Map();
let selectedDate = fmtDate(new Date());
let selectedSubject = 'القرآن';
let groupFilter = '';
let activeTab = 'attendance';
let tbody = null;
let printLink = null;
let tabsContainer = null;
let toolbarContainer = null;
let theadContainer = null;

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

function setEvalEntry(studentId, key, value) {
  const entry = evalEntries.get(studentId) || { value: 10, note: '' };
  entry[key] = value;
  evalEntries.set(studentId, entry);
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

function evaluationRow(student) {
  const entry = evalEntries.get(student.id) || { value: 10, note: '' };
  const scoreInput = el('input', {
    type: 'number',
    min: '0',
    max: '10',
    step: '0.5',
    value: entry.value ?? 10,
    style: 'max-width: 100px;',
    'aria-label': `درجة ${student.name || ''}`
  });
  scoreInput.addEventListener('input', () => setEvalEntry(student.id, 'value', Number(scoreInput.value)));

  const noteInput = el('input', {
    type: 'text',
    value: entry.note || '',
    placeholder: 'المقطع أو ملاحظة المتابعة',
    'aria-label': `ملاحظة تقييم ${student.name || ''}`
  });
  noteInput.addEventListener('input', () => setEvalEntry(student.id, 'note', noteInput.value));

  return el('tr', {},
    el('td', {}, student.name || '—'),
    el('td', {}, student.group_name || '—'),
    el('td', {}, scoreInput),
    el('td', {}, noteInput)
  );
}

function paint() {
  const list = students.filter(student => !groupFilter || student.group_name === groupFilter);
  if (activeTab === 'evaluation') {
    const rows = list.map(student => evaluationRow(student));
    const emptyText = students.length ? 'لا يوجد طلاب في هذه المجموعة' : 'لا يوجد طلاب نشطون بعد';
    tbody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '4', class: 'muted', style: 'text-align:center;' }, emptyText))]));
  } else {
    const rows = list.map(student => attendanceRow(student));
    const emptyText = students.length ? 'لا يوجد طلاب في هذه المجموعة' : 'لا يوجد طلاب نشطون بعد';
    tbody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '4', class: 'muted', style: 'text-align:center;' }, emptyText))]));
  }
}

async function loadEntries(api) {
  if (activeTab === 'evaluation') {
    const defaults = new Map(students.map(student => [student.id, { value: 10, note: '' }]));
    try {
      const res = await api.get(`evaluations/daily?date=${selectedDate}`);
      const items = toList(res);
      const byStudent = new Map(items.filter(it => it.subject === selectedSubject).map(it => [String(it.student_id), it]));
      evalEntries = new Map(students.map(student => {
        const rec = byStudent.get(String(student.id));
        return [student.id, { value: rec ? Number(rec.value) : 10, note: rec?.note || '' }];
      }));
    } catch (error) {
      evalEntries = defaults;
    }
  } else {
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
}

async function saveAttendance(api, button) {
  button.disabled = true;
  const targetList = groupFilter ? students.filter(student => student.group_name === groupFilter) : students;
  const rows = targetList.map(student => {
    const entry = entries.get(student.id) || {};
    return { student_id: student.id, date: selectedDate, status: entry.status || 'حاضر', note: entry.note || null };
  });
  try {
    await api.post('attendance/students', rows);
    toast('تم حفظ حضور الطلاب بنجاح');
  } catch (error) {
    toast(error.message, true);
  }
  button.disabled = false;
}

async function saveEvaluations(api, button) {
  button.disabled = true;
  const targetList = groupFilter ? students.filter(student => student.group_name === groupFilter) : students;
  const rows = targetList.map(student => {
    const entry = evalEntries.get(student.id) || {};
    return {
      student_id: student.id,
      date: selectedDate,
      subject: selectedSubject,
      value: entry.value ?? 10,
    };
  });
  try {
    await api.post('evaluations/daily', rows);
    toast('تم حفظ التقييم اليومي للطلاب بنجاح');
  } catch (error) {
    toast(error.message, true);
  }
  button.disabled = false;
}

export async function render(container, api) {
  students = [];
  entries = new Map();
  evalEntries = new Map();
  selectedDate = fmtDate(new Date());
  selectedSubject = 'القرآن';
  groupFilter = '';
  container.replaceChildren();

  // Parse initial tab from URL hash params
  const params = getParams();
  activeTab = params.get('tab') === 'evaluation' ? 'evaluation' : 'attendance';

  const titleEl = el('h1', {}, activeTab === 'evaluation' ? 'التقييم اليومي للطلاب' : 'الحضور والغياب');

  const TABS = [
    ['attendance', '☑ تسجيل الحضور والغياب'],
    ['evaluation', '★ التقييم اليومي للطلاب']
  ];

  tabsContainer = el('div', { class: 'toolbar', style: 'margin-bottom:12px; gap:6px;' });
  theadContainer = el('thead');
  tbody = el('tbody', {}, el('tr', {}, el('td', { colspan: '4', class: 'muted' }, 'جارٍ التحميل...')));
  toolbarContainer = el('div', { class: 'toolbar' });

  function updateViewMode() {
    titleEl.textContent = activeTab === 'evaluation' ? 'التقييم اليومي للطلاب' : 'الحضور والغياب';

    tabsContainer.replaceChildren(
      ...TABS.map(([id, label]) => {
        const btn = el('button', {
          class: activeTab === id ? 'button' : 'button button-outline',
          type: 'button'
        }, label);
        btn.onclick = async () => {
          activeTab = id;
          updateViewMode();
          await loadEntries(api);
          paint();
        };
        return btn;
      })
    );

    const dateInput = el('input', { type: 'date', value: selectedDate, 'aria-label': 'التاريخ' });
    dateInput.addEventListener('change', async () => {
      if (!dateInput.value) return;
      selectedDate = dateInput.value;
      if (printLink) printLink.setAttribute('href', reportHref());
      await loadEntries(api);
      paint();
    });

    const groupSelect = el('select', { 'aria-label': 'تصفية بالمجموعة' });
    groupSelect.append(el('option', { value: '' }, 'كل المجموعات'));
    GROUPS.forEach(group => groupSelect.append(el('option', { value: group }, group)));
    groupSelect.value = groupFilter;
    groupSelect.addEventListener('change', () => { groupFilter = groupSelect.value; paint(); });

    toolbarContainer.replaceChildren();

    if (activeTab === 'evaluation') {
      const subjectSelect = el('select', { 'aria-label': 'المادة' });
      SUBJECTS.forEach(s => subjectSelect.append(el('option', { value: s }, s)));
      subjectSelect.value = selectedSubject;
      subjectSelect.addEventListener('change', async () => {
        selectedSubject = subjectSelect.value;
        await loadEntries(api);
        paint();
      });

      const saveBtn = el('button', { class: 'button', type: 'button' }, '💾 حفظ التقييم اليومي');
      saveBtn.onclick = () => saveEvaluations(api, saveBtn);

      toolbarContainer.append(dateInput, groupSelect, subjectSelect, saveBtn);

      theadContainer.replaceChildren(
        el('tr', {},
          el('th', {}, 'الطالب'),
          el('th', {}, 'المجموعة'),
          el('th', {}, 'الدرجة (من 10)'),
          el('th', {}, 'الملاحظة')
        )
      );
    } else {
      const saveButton = el('button', { class: 'button', type: 'button' }, '💾 حفظ الحضور');
      saveButton.addEventListener('click', () => saveAttendance(api, saveButton));
      printLink = el('a', { class: 'button button-outline', href: reportHref(), target: '_blank', rel: 'noopener' }, '📄 تقرير الحضور');

      toolbarContainer.append(dateInput, groupSelect, saveButton, printLink);

      theadContainer.replaceChildren(
        el('tr', {},
          el('th', {}, 'الطالب'),
          el('th', {}, 'المجموعة'),
          el('th', {}, 'الحالة'),
          el('th', {}, 'ملاحظة')
        )
      );
    }
  }

  updateViewMode();

  container.append(
    el('div', { class: 'view-header' }, titleEl),
    tabsContainer,
    toolbarContainer,
    el('div', { class: 'table-wrap' }, el('table', {}, theadContainer, tbody))
  );

  try {
    students = toList(await api.get('students')).filter(student => (student.status || 'active') === 'active');
  } catch (error) {
    toast(error.message, true);
  }
  await loadEntries(api);
  paint();
}
