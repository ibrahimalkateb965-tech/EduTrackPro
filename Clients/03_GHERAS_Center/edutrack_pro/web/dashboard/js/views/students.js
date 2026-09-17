import { el, toast, modal } from '../ui.js';

const GROUPS = ['الصباح', 'المساء', 'الإنجليزي', 'القدرات'];
const GUARDIAN_RELATIONS = ['الأب', 'الأم', 'ولي الأمر', 'شخص آخر'];
const STATUS_LABELS = { active: 'نشط', dismissed: 'منسحب', archived: 'مؤرشف' };
const YES_NO = [['false', 'لا'], ['true', 'نعم']];

let students = [];
let rooms = [];
let query = '';
let activeTab = 'all';
let tbody = null;
let tabsContainer = null;

function getParams() {
  const hash = location.hash.replace(/^#\/?/, '');
  const qIdx = hash.indexOf('?');
  if (qIdx === -1) return new URLSearchParams();
  return new URLSearchParams(hash.slice(qIdx + 1));
}

function isGirl(student) {
  if (student.gender === 'بنات') return true;
  if (student.gender === 'بنين') return false;
  const text = `${student.child_notes || ''} ${student.education_notes || ''} ${student.room_name || ''}`;
  if (text.includes('بنات') || text.includes('أنثى')) return true;
  if (text.includes('بنين') || text.includes('أولاد') || text.includes('ذكر')) return false;
  const firstName = (student.name || '').trim().split(/\s+/)[0] || '';
  if (firstName.endsWith('ة') || firstName.endsWith('ه')) return true;
  const femaleNames = ['مريم', 'فاطمة', 'عائشة', 'نورة', 'نور', 'سارة', 'ريم', 'هدى', 'أمل', 'منى', 'شهد', 'رهف', 'جنى', 'خلود', 'ليلى', 'زينب', 'لمى', 'أسماء', 'ريناد', 'دانة', 'تسنيم'];
  return femaleNames.includes(firstName);
}

function toList(data) {
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.items)) return data.items;
  return [];
}

function roomLabel(student) {
  if (student.room_name) return student.room_name;
  const room = rooms.find(item => item.id === student.room_id);
  return room ? room.name : '—';
}

function statusBadge(student) {
  const status = student.status || 'active';
  return el('span', { class: status === 'active' ? 'badge' : 'badge red' }, STATUS_LABELS[status] || status);
}

function printCardHref(student) {
  return `../print/templates/guardian_card.html?id=${encodeURIComponent(student.id || '')}`;
}

function studentRow(student, api) {
  const edit = el('button', { class: 'button button-outline', type: 'button' }, 'تعديل');
  edit.addEventListener('click', () => openStudentForm(api, student));
  const printCard = el('a', { class: 'button button-outline', href: printCardHref(student), target: '_blank', rel: 'noopener' }, 'بطاقة الطالب');
  return el('tr', {},
    el('td', {}, student.name || '—'),
    el('td', {}, student.group_name || '—'),
    el('td', {}, roomLabel(student)),
    el('td', {}, student.guardian_phone || '—'),
    el('td', {}, statusBadge(student)),
    el('td', {}, edit, ' ', printCard)
  );
}

function paint(api) {
  const list = students.filter(student => {
    // 1. Search filter
    if (query) {
      const matchesQuery = [student.name, student.national_id, student.guardian_phone, student.father_phone, student.mother_phone]
        .some(value => String(value || '').includes(query));
      if (!matchesQuery) return false;
    }
    // 2. Tab filter
    if (activeTab === 'morning') return student.group_name === 'الصباح';
    if (activeTab === 'evening') return student.group_name === 'المساء';
    if (activeTab === 'qudrat') return student.group_name === 'القدرات';
    if (activeTab === 'english_boys') return student.group_name === 'الإنجليزي' && !isGirl(student);
    if (activeTab === 'english_girls') return student.group_name === 'الإنجليزي' && isGirl(student);
    return true;
  });
  const rows = list.map(student => studentRow(student, api));
  const emptyText = students.length ? 'لا توجد نتائج مطابقة للبحث أو التصفية الحالية' : 'لا يوجد طلاب مسجلون بعد';
  tbody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '6', class: 'muted' }, emptyText))]));
}

async function reload(api) {
  try {
    students = toList(await api.get('students'));
  } catch (error) {
    toast(error.message, true);
  }
  paint(api);
}

function select(name, options, selected) {
  const node = el('select', { name });
  options.forEach(([optionValue, optionLabel]) => node.append(el('option', { value: optionValue }, optionLabel)));
  node.value = selected === undefined || selected === null ? '' : String(selected);
  return node;
}

function openStudentForm(api, student) {
  const editing = Boolean(student && student.id);
  const value = key => (student && student[key] !== undefined && student[key] !== null) ? String(student[key]) : '';
  const boolValue = key => String(student ? student[key] === true : false);
  const roomOptions = [['', 'بدون فصل']].concat(rooms.map(room => [room.id, room.name]));
  const groupOptions = [['', 'اختر المجموعة']].concat(GROUPS.map(group => [group, group]));
  const relationOptions = GUARDIAN_RELATIONS.map(relation => [relation, relation]);
  const fields = [
    el('label', {}, el('span', {}, 'اسم الطالب'), el('input', { name: 'name', type: 'text', required: 'required', value: value('name') })),
    el('label', {}, el('span', {}, 'رقم الهوية'), el('input', { name: 'national_id', type: 'text', value: value('national_id') })),
    el('label', {}, el('span', {}, 'تاريخ الميلاد'), el('input', { name: 'birth_date', type: 'date', value: value('birth_date') })),
    el('label', {}, el('span', {}, 'الجنسية'), el('input', { name: 'nationality', type: 'text', value: value('nationality') })),
    el('label', {}, el('span', {}, 'صعوبات تعلم'), select('has_difficulties', YES_NO, boolValue('has_difficulties'))),
    el('label', {}, el('span', {}, 'تفاصيل الصعوبات'), el('textarea', { name: 'difficulty_notes' }, value('difficulty_notes'))),
    el('label', {}, el('span', {}, 'ملاحظات عن الطالب'), el('textarea', { name: 'child_notes' }, value('child_notes'))),
    el('label', {}, el('span', {}, 'اسم الأب'), el('input', { name: 'father_name', type: 'text', value: value('father_name') })),
    el('label', {}, el('span', {}, 'هاتف الأب'), el('input', { name: 'father_phone', type: 'text', value: value('father_phone') })),
    el('label', {}, el('span', {}, 'اسم الأم'), el('input', { name: 'mother_name', type: 'text', value: value('mother_name') })),
    el('label', {}, el('span', {}, 'هاتف الأم'), el('input', { name: 'mother_phone', type: 'text', value: value('mother_phone') })),
    el('label', {}, el('span', {}, 'هاتف ولي الأمر'), el('input', { name: 'guardian_phone', type: 'text', required: 'required', value: value('guardian_phone') })),
    el('label', {}, el('span', {}, 'علاقة ولي الأمر'), select('guardian_relation', relationOptions, value('guardian_relation') || 'الأب')),
    el('label', {}, el('span', {}, 'طريقة الاستلام'), el('input', { name: 'pickup_type', type: 'text', value: value('pickup_type') })),
    el('label', {}, el('span', {}, 'اسم المستلم'), el('input', { name: 'pickup_name', type: 'text', value: value('pickup_name') })),
    el('label', {}, el('span', {}, 'علاقة المستلم'), el('input', { name: 'pickup_relation', type: 'text', value: value('pickup_relation') })),
    el('label', {}, el('span', {}, 'هاتف المستلم'), el('input', { name: 'pickup_phone', type: 'text', value: value('pickup_phone') })),
    el('label', {}, el('span', {}, 'دراسة سابقة'), select('previous_study', YES_NO, boolValue('previous_study'))),
    el('label', {}, el('span', {}, 'المدرسة السابقة'), el('input', { name: 'previous_school', type: 'text', value: value('previous_school') })),
    el('label', {}, el('span', {}, 'المستوى السابق'), el('input', { name: 'previous_level', type: 'text', value: value('previous_level') })),
    el('label', {}, el('span', {}, 'ملاحظات تعليمية'), el('textarea', { name: 'education_notes' }, value('education_notes'))),
    el('label', {}, el('span', {}, 'الفصل'), select('room_id', roomOptions, value('room_id'))),
    el('label', {}, el('span', {}, 'المجموعة'), select('group_name', groupOptions, value('group_name'))),
    el('label', {}, el('span', {}, 'القسم / الجنس'), select('gender', [['بنين', 'بنين (أولاد)'], ['بنات', 'بنات']], value('gender') || (isGirl(student || {}) ? 'بنات' : 'بنين')))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, editing ? 'حفظ التعديلات' : 'إضافة');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions' }, submit, cancel));
  const dialog = modal(editing ? 'تعديل بيانات الطالب' : 'إضافة طالب', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    Object.keys(payload).forEach(key => { payload[key] = String(payload[key]).trim(); });
    payload.has_difficulties = payload.has_difficulties === 'true';
    payload.previous_study = payload.previous_study === 'true';
    payload.room_id = payload.room_id || null;
    if (payload.birth_date === '') payload.birth_date = null;
    payload.gender = payload.gender || (isGirl(student || {}) ? 'بنات' : 'بنين');

    try {
      if (editing) await api.patch(`students/${student.id}`, payload);
      else await api.post('students', payload);
      dialog.close();
      toast(editing ? 'تم تحديث بيانات الطالب' : 'تمت إضافة الطالب');
      await reload(api);
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

export async function render(container, api) {
  students = [];
  rooms = [];
  query = '';
  container.replaceChildren();

  // Parse initial tab from URL hash params
  const params = getParams();
  const group = params.get('group');
  const gender = params.get('gender');
  if (group === 'الإنجليزي') {
    if (gender === 'girls' || gender === 'بنات') activeTab = 'english_girls';
    else activeTab = 'english_boys';
  } else if (group === 'الصباح') {
    activeTab = 'morning';
  } else if (group === 'المساء') {
    activeTab = 'evening';
  } else if (group === 'القدرات') {
    activeTab = 'qudrat';
  } else {
    activeTab = 'all';
  }

  const TABS = [
    ['all', 'كل الطلاب'],
    ['morning', 'الصباح'],
    ['evening', 'المساء'],
    ['english_boys', 'طلاب الإنجليزي (أولاد)'],
    ['english_girls', 'طالبات الإنجليزي (بنات)'],
    ['qudrat', 'القدرات']
  ];

  tabsContainer = el('div', { class: 'toolbar', style: 'margin-bottom:12px; gap:6px;' });
  function updateTabs() {
    tabsContainer.replaceChildren(
      ...TABS.map(([id, label]) => {
        const btn = el('button', {
          class: activeTab === id ? 'button' : 'button button-outline',
          type: 'button'
        }, label);
        btn.onclick = () => {
          activeTab = id;
          updateTabs();
          paint(api);
        };
        return btn;
      })
    );
  }
  updateTabs();

  const search = el('input', { type: 'search', placeholder: 'ابحث بالاسم أو رقم الهوية أو الهاتف', 'aria-label': 'بحث في الطلاب' });
  search.addEventListener('input', () => { query = search.value.trim(); paint(api); });
  const addButton = el('button', { class: 'button', type: 'button' }, 'إضافة طالب');
  addButton.addEventListener('click', () => openStudentForm(api, null));
  tbody = el('tbody', {}, el('tr', {}, el('td', { colspan: '6', class: 'muted' }, 'جارٍ التحميل...')));
  container.append(
    el('div', { class: 'view-header' }, el('h1', {}, 'الطلاب')),
    tabsContainer,
    el('div', { class: 'toolbar' }, search, addButton),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {},
        el('th', {}, 'الاسم'),
        el('th', {}, 'المجموعة'),
        el('th', {}, 'الفصل'),
        el('th', {}, 'هاتف ولي الأمر'),
        el('th', {}, 'الحالة'),
        el('th', {}, 'إجراءات')
      )),
      tbody
    ))
  );
  try { rooms = toList(await api.get('rooms')); } catch (error) { toast('تعذر تحميل قائمة الفصول', true); }
  await reload(api);
}
