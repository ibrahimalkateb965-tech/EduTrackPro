import { el, toast, modal, createDatePicker } from '../ui.js';

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

function isEnglishGroup(g) {
  const norm = String(g || '').trim();
  return norm === 'الإنجليزي' || norm === 'الانجليزي' || norm === 'انجليزي' || norm === 'إنجليزي' || norm.toLowerCase() === 'english';
}

const MALE_NAMES = new Set([
  'حمزة', 'أسامة', 'اسامة', 'حذيفة', 'معاوية', 'طلحة', 'عبيدة', 'قتادة',
  'عنترة', 'سلامة', 'عكرمة', 'ميسرة', 'عمارة', 'حنظلة', 'سلمة', 'طه',
  'عطية', 'جمعة', 'رفاعة', 'شيبة', 'أمية', 'امية', 'عبدالله', 'سعدالله',
  'حارثة', 'ربيعة', 'عبيدة', 'قتيبة', 'مالك', 'محمد', 'أحمد', 'احمد',
  'علي', 'عمر', 'عمرو', 'خالد', 'سعد', 'سعود', 'فهد', 'سلمان', 'عبدالعزيز',
  'عبدالرحمن', 'يوسف', 'إبراهيم', 'ابراهيم', 'صالح', 'سليمان', 'عبد الله',
  'بلال', 'ياسر', 'طارق', 'زياد', 'وليد', 'فيصل', 'سلطان', 'ماجد', 'بندر',
  'تركي', 'مشعل', 'نايف', 'نواف', 'بدر', 'سالم', 'منصور', 'ناصر', 'حسام',
  'عبدالملك', 'عبدالرحيم', 'عبدالكريم', 'عبدالمجيد', 'عبداللطيف', 'عبدالوهاب'
]);

const FEMALE_NAMES = new Set([
  'مريم', 'فاطمة', 'فاطمه', 'عائشة', 'عائشه', 'نورة', 'نوره', 'نور', 'سارة', 'ساره',
  'ريم', 'هدى', 'أمل', 'منى', 'شهد', 'رهف', 'جنى', 'خلود', 'ليلى', 'زينب', 'لمى',
  'أسماء', 'ريناد', 'دانة', 'دانه', 'تسنيم', 'عبير', 'روان', 'شروق', 'حنين', 'يارا',
  'ريما', 'هند', 'بشاير', 'أروى', 'غيداء', 'أفنان', 'جود', 'بيان', 'خلود', 'وسن'
]);

function isGirl(student) {
  const g = String(student.gender || '').trim();
  if (g === 'بنات' || g === 'female' || g === 'أنثى') return true;
  if (g === 'بنين' || g === 'male' || g === 'ذكر' || g === 'أولاد') return false;

  const text = `${student.child_notes || ''} ${student.education_notes || ''} ${student.room_name || ''}`;
  if (text.includes('بنات') || text.includes('أنثى')) return true;
  if (text.includes('بنين') || text.includes('أولاد') || text.includes('ذكر')) return false;

  const firstName = (student.name || '').trim().split(/\s+/)[0] || '';
  if (MALE_NAMES.has(firstName)) return false;
  if (FEMALE_NAMES.has(firstName)) return true;

  if (firstName.endsWith('ة')) return true;

  return false;
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

function genderBadge(student) {
  const girl = isGirl(student);
  const text = student.gender || (girl ? 'بنات' : 'بنين');
  const isBnt = text === 'بنات';
  return el('span', { 
    class: 'badge',
    style: isBnt ? 'background: #fce7f3; color: #9d174d; border: 1px solid #fbcfe8;' : 'background: #e0f2fe; color: #0369a1; border: 1px solid #bae6fd;'
  }, isBnt ? '👧 بنات' : '👦 بنين');
}

function printCardHref(student) {
  return `../print/templates/guardian_card.html?id=${encodeURIComponent(student.id || '')}`;
}

function studentRow(student, api) {
  const edit = el('button', { class: 'button button-outline', type: 'button' }, 'تعديل');
  edit.addEventListener('click', () => openStudentForm(api, student));
  const printCard = el('a', { class: 'button button-outline', href: printCardHref(student), target: '_blank', rel: 'noopener' }, 'بطاقة الطالب');
  const commBtn = el('button', {
    class: 'button',
    type: 'button',
    style: 'background:#25d366; padding:5px 9px; font-size:12px; margin-right:4px;',
    title: 'مراسلة ولي الأمر عبر واتساب'
  }, '💬 مراسلة');
  commBtn.addEventListener('click', async () => {
    try {
      const { openComposerModal } = await import(`./communication.js${new URL(import.meta.url).search}`); // inherit app.js cache version
      await openComposerModal(api, student.id);
    } catch (err) {
      toast('تعذر فتح مركز التواصل', true);
    }
  });

  return el('tr', {},
    el('td', { style: 'font-weight:600;' }, student.name || '—'),
    el('td', {}, student.group_name || '—'),
    el('td', {}, genderBadge(student)),
    el('td', {}, roomLabel(student)),
    el('td', {}, student.guardian_phone || '—'),
    el('td', {}, statusBadge(student)),
    el('td', {}, edit, ' ', printCard, ' ', commBtn)
  );
}

const TABS = [
  ['all', 'كل الطلاب'],
  ['morning', 'الصباح'],
  ['evening', 'المساء'],
  ['english_all', 'الإنجليزي (الكل)'],
  ['english_boys', 'الإنجليزي (أولاد)'],
  ['english_girls', 'الإنجليزي (بنات)'],
  ['qudrat', 'القدرات']
];

function countForTab(tabId) {
  return students.filter(student => {
    if (tabId === 'morning') return student.group_name === 'الصباح';
    if (tabId === 'evening') return student.group_name === 'المساء';
    if (tabId === 'qudrat') return student.group_name === 'القدرات';
    if (tabId === 'english_all') return isEnglishGroup(student.group_name);
    if (tabId === 'english_boys') return isEnglishGroup(student.group_name) && !isGirl(student);
    if (tabId === 'english_girls') return isEnglishGroup(student.group_name) && isGirl(student);
    return true;
  }).length;
}

function updateTabs(api) {
  if (!tabsContainer) return;
  tabsContainer.replaceChildren(
    ...TABS.map(([id, label]) => {
      const count = countForTab(id);
      const btn = el('button', {
        class: activeTab === id ? 'button' : 'button button-outline',
        type: 'button',
        style: 'display:inline-flex; align-items:center; gap:6px;'
      }, 
        el('span', {}, label),
        el('span', { 
          class: 'badge',
          style: activeTab === id ? 'background:rgba(255,255,255,0.25); color:inherit; padding:2px 6px; font-size:11px;' : 'padding:2px 6px; font-size:11px;' 
        }, String(count))
      );
      btn.onclick = () => {
        activeTab = id;
        updateTabs(api);
        paint(api);
      };
      return btn;
    })
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
    if (activeTab === 'english_all') return isEnglishGroup(student.group_name);
    if (activeTab === 'english_boys') return isEnglishGroup(student.group_name) && !isGirl(student);
    if (activeTab === 'english_girls') return isEnglishGroup(student.group_name) && isGirl(student);
    return true;
  });
  const rows = list.map(student => studentRow(student, api));
  const emptyText = students.length ? 'لا توجد نتائج مطابقة للبحث أو التصفية الحالية' : 'لا يوجد طلاب مسجلون بعد';
  tbody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '7', class: 'muted' }, emptyText))]));
}

async function reload(api) {
  try {
    students = toList(await api.get('students'));
  } catch (error) {
    toast(error.message, true);
  }
  updateTabs(api);
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

  let defaultGroup = '';
  let defaultGender = 'بنين';
  if (activeTab === 'english_boys') {
    defaultGroup = 'الإنجليزي';
    defaultGender = 'بنين';
  } else if (activeTab === 'english_girls') {
    defaultGroup = 'الإنجليزي';
    defaultGender = 'بنات';
  } else if (activeTab === 'english_all') {
    defaultGroup = 'الإنجليزي';
    defaultGender = 'بنين';
  } else if (activeTab === 'morning') {
    defaultGroup = 'الصباح';
  } else if (activeTab === 'evening') {
    defaultGroup = 'المساء';
  } else if (activeTab === 'qudrat') {
    defaultGroup = 'القدرات';
  }

  const fields = [
    // --- 1. البيانات الأساسية والأكاديمية ---
    el('div', { class: 'form-section-title', style: 'grid-column: 1 / -1; font-weight: bold; margin: 4px 0 6px; padding-bottom: 4px; border-bottom: 1.5px solid var(--border, #e2e8f0); color: var(--primary, #0284c7); display: flex; align-items: center; gap: 6px;' }, '👤 البيانات الأساسية والأكاديمية'),

    el('label', {}, el('span', { style: 'font-weight:600;' }, 'اسم الطالب *'), el('input', { name: 'name', type: 'text', required: 'required', placeholder: 'الاسم الثلاثي أو الرباعي', value: value('name') })),

    el('label', { style: 'background: rgba(14, 165, 233, 0.08); padding: 6px 10px; border-radius: 8px; border: 1.5px solid rgba(14, 165, 233, 0.3);' }, 
      el('span', { style: 'font-weight: 700; color: #0369a1; display: flex; align-items: center; gap: 4px;' }, '⚥ تحديد الجنس (بنين / بنات) *'), 
      select('gender', [['بنين', '👦 بنين (أولاد)'], ['بنات', '👧 بنات']], value('gender') || (editing ? (isGirl(student || {}) ? 'بنات' : 'بنين') : defaultGender))
    ),

    el('label', {}, el('span', { style: 'font-weight:600;' }, 'المجموعة الدراسية *'), select('group_name', groupOptions, value('group_name') || defaultGroup)),
    el('label', {}, el('span', {}, 'الفصل / القاعة'), select('room_id', roomOptions, value('room_id'))),
    el('label', {}, el('span', {}, 'رقم الهوية الوطنية / الإقامة'), el('input', { name: 'national_id', type: 'text', value: value('national_id') })),
    createDatePicker({
      name: 'birth_date',
      value: value('birth_date'),
      label: 'تاريخ الميلاد',
      minYear: 1970,
      maxYear: new Date().getFullYear(),
      showAge: true
    }),
    el('label', {}, el('span', {}, 'الجنسية'), el('input', { name: 'nationality', type: 'text', value: value('nationality') || 'سعودي' })),

    // --- 2. بيانات ولي الأمر والتواصل ---
    el('div', { class: 'form-section-title', style: 'grid-column: 1 / -1; font-weight: bold; margin: 12px 0 6px; padding-bottom: 4px; border-bottom: 1.5px solid var(--border, #e2e8f0); color: var(--primary, #0284c7); display: flex; align-items: center; gap: 6px;' }, '📞 بيانات ولي الأمر والتواصل'),

    el('label', {}, el('span', { style: 'font-weight:600;' }, 'هاتف ولي الأمر *'), el('input', { name: 'guardian_phone', type: 'text', required: 'required', value: value('guardian_phone') })),
    el('label', {}, el('span', {}, 'علاقة ولي الأمر'), select('guardian_relation', relationOptions, value('guardian_relation') || 'الأب')),
    el('label', {}, el('span', {}, 'اسم الأب'), el('input', { name: 'father_name', type: 'text', value: value('father_name') })),
    el('label', {}, el('span', {}, 'هاتف الأب'), el('input', { name: 'father_phone', type: 'text', value: value('father_phone') })),
    el('label', {}, el('span', {}, 'اسم الأم'), el('input', { name: 'mother_name', type: 'text', value: value('mother_name') })),
    el('label', {}, el('span', {}, 'هاتف الأم'), el('input', { name: 'mother_phone', type: 'text', value: value('mother_phone') })),
    el('label', {}, el('span', {}, 'طريقة الاستلام'), el('input', { name: 'pickup_type', type: 'text', value: value('pickup_type') })),
    el('label', {}, el('span', {}, 'اسم المستلم'), el('input', { name: 'pickup_name', type: 'text', value: value('pickup_name') })),
    el('label', {}, el('span', {}, 'علاقة المستلم'), el('input', { name: 'pickup_relation', type: 'text', value: value('pickup_relation') })),
    el('label', {}, el('span', {}, 'هاتف المستلم'), el('input', { name: 'pickup_phone', type: 'text', value: value('pickup_phone') })),

    // --- 3. البيانات التعليمية والملاحظات ---
    el('div', { class: 'form-section-title', style: 'grid-column: 1 / -1; font-weight: bold; margin: 12px 0 6px; padding-bottom: 4px; border-bottom: 1.5px solid var(--border, #e2e8f0); color: var(--primary, #0284c7); display: flex; align-items: center; gap: 6px;' }, '📝 البيانات التعليمية والملاحظات'),

    el('label', {}, el('span', {}, 'دراسة سابقة'), select('previous_study', YES_NO, boolValue('previous_study'))),
    el('label', {}, el('span', {}, 'المدرسة السابقة'), el('input', { name: 'previous_school', type: 'text', value: value('previous_school') })),
    el('label', {}, el('span', {}, 'المستوى السابق'), el('input', { name: 'previous_level', type: 'text', value: value('previous_level') })),
    el('label', {}, el('span', {}, 'صعوبات تعلم'), select('has_difficulties', YES_NO, boolValue('has_difficulties'))),
    el('label', {}, el('span', {}, 'تفاصيل الصعوبات'), el('textarea', { name: 'difficulty_notes' }, value('difficulty_notes'))),
    el('label', {}, el('span', {}, 'ملاحظات عن الطالب'), el('textarea', { name: 'child_notes' }, value('child_notes'))),
    el('label', {}, el('span', {}, 'ملاحظات تعليمية'), el('textarea', { name: 'education_notes' }, value('education_notes')))
  ];

  const submit = el('button', { class: 'button', type: 'button', style: 'padding: 10px 24px; font-weight: 700;' }, editing ? 'حفظ التعديلات' : 'إضافة طالب');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const actionsBar = el('div', { class: 'form-actions', style: 'grid-column: 1 / -1; display: flex; gap: 10px; margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--border, #e2e8f0);' }, submit, cancel);
  const form = el('form', { class: 'form-grid', novalidate: 'novalidate' }, ...fields, actionsBar);
  const dialog = modal(editing ? 'تعديل بيانات الطالب' : 'إضافة طالب جديد', form);
  cancel.addEventListener('click', dialog.close);

  const saveStudent = async () => {
    const rawData = new FormData(form);
    const payload = Object.fromEntries(rawData.entries());
    Object.keys(payload).forEach(key => {
      payload[key] = typeof payload[key] === 'string' ? payload[key].trim() : payload[key];
    });

    if (!payload.name) {
      toast('يرجى كتابة اسم الطالب *', true);
      const nameInput = form.querySelector('input[name="name"]');
      if (nameInput) nameInput.focus();
      return;
    }

    if (!payload.guardian_phone) {
      toast('يرجى إدخال هاتف ولي الأمر *', true);
      const phoneInput = form.querySelector('input[name="guardian_phone"]');
      if (phoneInput) phoneInput.focus();
      return;
    }

    payload.has_difficulties = payload.has_difficulties === 'true';
    payload.previous_study = payload.previous_study === 'true';
    payload.room_id = payload.room_id || null;
    payload.gender = payload.gender || (editing ? (isGirl(student || {}) ? 'بنات' : 'بنين') : defaultGender);

    // PostgreSQL nullable & check constraint guarantees
    ['birth_date', 'group_name', 'guardian_relation', 'national_id', 'father_phone', 'mother_phone', 'pickup_phone'].forEach(k => {
      if (payload[k] === '') payload[k] = null;
    });

    submit.disabled = true;
    submit.textContent = 'جاري الحفظ...';

    try {
      if (editing) {
        await api.patch(`students/${student.id}`, payload);
      } else {
        await api.post('students', payload);
      }
      dialog.close();
      toast(editing ? 'تم تحديث بيانات الطالب بنجاح' : 'تمت إضافة الطالب بنجاح');
      await reload(api);
    } catch (error) {
      console.error('Save student failed:', error);
      toast(error.message || 'تعذر حفظ بيانات الطالب', true);
      submit.disabled = false;
      submit.textContent = editing ? 'حفظ التعديلات' : 'إضافة طالب';
    }
  };

  form.addEventListener('submit', event => {
    event.preventDefault();
    saveStudent();
  });

  submit.addEventListener('click', event => {
    event.preventDefault();
    saveStudent();
  });
}

export async function render(container, api) {
  students = [];
  rooms = [];
  query = '';
  container.replaceChildren();

  // Parse initial tab from URL hash params
  const params = getParams();
  const tabParam = params.get('tab');
  const group = params.get('group');
  const gender = params.get('gender');
  if (tabParam && ['all', 'morning', 'evening', 'english_all', 'english_boys', 'english_girls', 'qudrat'].includes(tabParam)) {
    activeTab = tabParam;
  } else if (isEnglishGroup(group)) {
    if (gender === 'girls' || gender === 'بنات') activeTab = 'english_girls';
    else if (gender === 'boys' || gender === 'بنين') activeTab = 'english_boys';
    else activeTab = 'english_all';
  } else if (group === 'الصباح' || group === 'morning') {
    activeTab = 'morning';
  } else if (group === 'المساء' || group === 'evening') {
    activeTab = 'evening';
  } else if (group === 'القدرات' || group === 'qudrat') {
    activeTab = 'qudrat';
  } else {
    activeTab = 'all';
  }

  tabsContainer = el('div', { class: 'toolbar', style: 'margin-bottom:12px; gap:6px; flex-wrap:wrap;' });
  updateTabs(api);

  const search = el('input', { type: 'search', placeholder: 'ابحث بالاسم أو رقم الهوية أو الهاتف', 'aria-label': 'بحث في الطلاب' });
  search.addEventListener('input', () => { query = search.value.trim(); paint(api); });
  const addButton = el('button', { class: 'button', type: 'button' }, '➕ إضافة طالب');
  addButton.addEventListener('click', () => openStudentForm(api, null));
  tbody = el('tbody', {}, el('tr', {}, el('td', { colspan: '7', class: 'muted' }, 'جارٍ التحميل...')));
  container.append(
    el('div', { class: 'view-header' }, el('h1', {}, 'الطلاب')),
    tabsContainer,
    el('div', { class: 'toolbar' }, search, addButton),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {},
        el('th', {}, 'الاسم'),
        el('th', {}, 'المجموعة'),
        el('th', {}, 'القسم (الجنس)'),
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
