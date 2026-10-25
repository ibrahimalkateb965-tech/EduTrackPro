import { el, toast } from '../ui.js';
import { openCreateStudentModal, openCreateGuardianModal } from '../account_modals.js';
import { openQuickPaymentModal } from './students/quick_payment_modal.js?v=3.5';
import { openStudentForm } from './students/student_form_modal.js?v=3.5';
import {
  DEFAULT_GROUPS,
  getCustomGroups,
  saveCustomGroup,
  getAllGroups,
  openAddGroupModal,
  openDeleteStudentModal,
  openBehaviorLogModal,
  isGirl
} from './students/student_modals.js?v=3.5';

// Re-export modular functions for full backward compatibility
export {
  getCustomGroups,
  saveCustomGroup,
  getAllGroups,
  openAddGroupModal,
  openDeleteStudentModal,
  openBehaviorLogModal,
  openQuickPaymentModal,
  openStudentForm
};

const STATUS_LABELS = { active: 'نشط', dismissed: 'منسحب', archived: 'مؤرشف' };

let students = [];
let users = [];
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
  const isManager = !api.currentUser || api.currentUser.role === 'manager';
  const edit = el('button', { class: 'button button-outline', type: 'button' }, 'تعديل');
  edit.addEventListener('click', () => openStudentForm(api, student, {
    rooms,
    activeTab,
    onReload: () => reload(api),
    studentsList: students,
    onGroupAdded: () => updateTabs(api)
  }));

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

  // Account checks
  const studentUser = users.find(u => u.student_id === student.id || (student.national_id && u.national_id === student.national_id));
  const guardianPhone = student.guardian_phone || student.father_phone || student.mother_phone;
  const guardianUser = users.find(u => u.role === 'guardian' && (
    (guardianPhone && (u.username === guardianPhone || u.phone === guardianPhone)) ||
    (u.children && u.children.some(c => c.id === student.id))
  ));

  const nameCol = el('td', { style: 'font-weight:600;' },
    el('div', { style: 'display:flex; align-items:center; gap:6px; flex-wrap:wrap;' },
      el('span', {}, student.name || '—'),
      studentUser
        ? el('span', { class: 'badge', style: 'background:#e0f2fe; color:#0369a1; font-size:11px;' }, '🎓 حساب مفعل')
        : (isManager ? el('button', {
            class: 'button button-outline',
            type: 'button',
            style: 'padding:2px 6px; font-size:11px; color:#0284c7; border-color:#bae6fd;',
            title: 'إنشاء حساب دخول للطالب في التطبيق'
          }, '+ حساب طالب') : null)
    )
  );

  if (!studentUser && isManager) {
    const btn = nameCol.querySelector('button');
    if (btn) btn.onclick = () => openCreateStudentModal(api, () => reload(api), student.id);
  }

  const guardianCol = el('td', {},
    el('div', { style: 'display:flex; align-items:center; gap:6px; flex-wrap:wrap;' },
      el('span', {}, student.guardian_phone || '—'),
      guardianUser
        ? el('span', { class: 'badge', style: 'background:#dcfce7; color:#166534; font-size:11px;' }, '👨‍👩‍👧 ولي أمر نشط')
        : (isManager && guardianPhone ? el('button', {
            class: 'button button-outline',
            type: 'button',
            style: 'padding:2px 6px; font-size:11px; color:#059669; border-color:#a7f3d0;',
            title: 'إنشاء حساب دخول لولي الأمر وربطه بالطالب'
          }, '+ حساب ولي أمر') : null)
    )
  );

  if (!guardianUser && isManager) {
    const btn = guardianCol.querySelector('button');
    if (btn) btn.onclick = () => openCreateGuardianModal(api, () => reload(api), student.id);
  }

  const behaviorBtn = el('button', {
    class: 'button button-outline',
    type: 'button',
    style: 'color:#854d0e; border-color:#fde047; background:#fefce8; padding:5px 8px; font-size:12px; margin-right:4px;',
    title: 'السجل السلوكي وتواصل ولي الأمر'
  }, '⭐ سلوكي');
  behaviorBtn.addEventListener('click', () => openBehaviorLogModal(api, student));

  const receiptBtn = el('button', {
    class: 'button button-outline',
    type: 'button',
    style: 'color:#047857; border-color:#6ee7b7; background:#ecfdf5; padding:5px 8px; font-size:12px; margin-right:4px;',
    title: 'تحصيل رسوم وطباعة سند قبض'
  }, '💳 سند قبض');
  receiptBtn.addEventListener('click', () => openQuickPaymentModal(api, student, () => reload(api)));

  const deleteBtn = el('button', {
    class: 'button button-outline',
    type: 'button',
    style: 'color:#dc2626; border-color:#fca5a5; background:#fef2f2; padding:5px 8px; font-size:12px; margin-right:4px;',
    title: 'حذف هذا الطالب نهائياً من النظام'
  }, '🗑️ حذف');
  deleteBtn.addEventListener('click', () => openDeleteStudentModal(api, student, () => reload(api)));

  return el('tr', {},
    nameCol,
    el('td', {}, student.group_name || '—'),
    el('td', {}, genderBadge(student)),
    el('td', {}, roomLabel(student)),
    guardianCol,
    el('td', {}, statusBadge(student)),
    el('td', {}, edit, ' ', receiptBtn, ' ', printCard, ' ', commBtn, ' ', behaviorBtn, ' ', deleteBtn)
  );
}

function getTabs() {
  const baseTabs = [
    ['all', 'كل الطلاب'],
    ['morning', 'الصباح'],
    ['evening', 'المساء'],
    ['english_all', 'الإنجليزي (الكل)'],
    ['english_boys', 'الإنجليزي (أولاد)'],
    ['english_girls', 'الإنجليزي (بنات)'],
    ['qudrat', 'القدرات']
  ];
  const allGroups = getAllGroups(students);
  allGroups.forEach(g => {
    if (!DEFAULT_GROUPS.includes(g)) {
      baseTabs.push([`group_${g}`, g]);
    }
  });
  return baseTabs;
}

function countForTab(tabId) {
  if (tabId.startsWith('group_')) {
    const targetGroup = tabId.slice('group_'.length);
    return students.filter(student => student.group_name === targetGroup).length;
  }
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
  const activeTabsList = getTabs();
  const tabButtons = activeTabsList.map(([id, label]) => {
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
  });

  const addGroupTabBtn = el('button', {
    class: 'button button-outline',
    type: 'button',
    style: 'border-color: #0284c7; color: #0284c7; background: #f0f9ff; display: inline-flex; align-items: center; gap: 4px; padding: 4px 10px; font-size: 13px; border-style: dashed;',
    title: 'إضافة مجموعة دراسية جديدة عند الرغبة'
  }, '📚 + مجموعة دراسية');
  addGroupTabBtn.onclick = () => openAddGroupModal(api, () => {
    updateTabs(api);
    paint(api);
  });

  tabsContainer.replaceChildren(...tabButtons, addGroupTabBtn);
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
    if (activeTab.startsWith('group_')) {
      const targetGroup = activeTab.slice('group_'.length);
      return student.group_name === targetGroup;
    }
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
    const [st, u] = await Promise.all([
      api.fetchAll('students'),
      api.fetchAll('users').catch(() => [])
    ]);
    students = toList(st);
    users = toList(u);
  } catch (error) {
    toast(error.message, true);
  }
  updateTabs(api);
  paint(api);
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
  addButton.addEventListener('click', () => openStudentForm(api, null, {
    rooms,
    activeTab,
    onReload: () => reload(api),
    studentsList: students,
    onGroupAdded: () => updateTabs(api)
  }));

  const addStudentAccBtn = el('button', {
    class: 'button',
    type: 'button',
    style: 'background: #0284c7; border-color: #0369a1; color: #fff;'
  }, '🎓 إضافة حساب طالب');
  addStudentAccBtn.onclick = () => openCreateStudentModal(api, () => reload(api));

  const addGuardianAccBtn = el('button', {
    class: 'button',
    type: 'button',
    style: 'background: #059669; border-color: #047857; color: #fff;'
  }, '👨‍👩‍👧 إضافة حساب ولي أمر');
  addGuardianAccBtn.onclick = () => openCreateGuardianModal(api, () => reload(api));

  tbody = el('tbody', {}, el('tr', {}, el('td', { colspan: '7', class: 'muted' }, 'جارٍ التحميل...')));
  container.append(
    el('div', { class: 'view-header' }, el('h1', {}, 'الطلاب')),
    tabsContainer,
    el('div', { class: 'toolbar', style: 'display:flex; gap:8px; flex-wrap:wrap; align-items:center;' },
      search,
      addButton,
      addStudentAccBtn,
      addGuardianAccBtn
    ),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {},
        el('th', {}, 'اسم الطالب والحساب'),
        el('th', {}, 'المجموعة'),
        el('th', {}, 'القسم (الجنس)'),
        el('th', {}, 'الفصل'),
        el('th', {}, 'ولي الأمر والهاتف'),
        el('th', {}, 'الحالة'),
        el('th', {}, 'إجراءات')
      )),
      tbody
    ))
  );
  try { rooms = toList(await api.fetchAll('rooms')); } catch (error) { toast('تعذر تحميل قائمة الفصول', true); }
  await reload(api);
}
