import { el, toast, modal, fmtDate, fmtSAR } from '../ui.js';

let studentsList = [];
let roomsList = [];
let messagesList = [];
let feePlansList = [];
let paymentsList = [];
let attendanceList = [];
let activeTab = 'composer'; // 'composer' | 'history' | 'directory'

function toList(data) {
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.items)) return data.items;
  return [];
}

export function normalizePhone(raw) {
  if (!raw) return '';
  let digits = String(raw).replace(/\D/g, '');
  if (digits.startsWith('00966')) digits = digits.slice(2);
  if (digits.startsWith('966')) return digits;
  if (digits.startsWith('05') && digits.length === 10) return '966' + digits.slice(1);
  if (digits.startsWith('5') && digits.length === 9) return '966' + digits;
  return digits;
}

function getStudentPhone(student) {
  return student?.guardian_phone || student?.father_phone || student?.mother_phone || student?.phone || student?.guardianPhone || '';
}

function getGuardianName(student) {
  return student?.guardian_name || student?.guardianName || 'ولي الأمر المحترم';
}

function getRoomName(roomId) {
  if (!roomId) return 'غير محدد';
  const r = roomsList.find(x => x.id === roomId);
  return r ? (r.name || `قاعة ${r.id}`) : 'غير محدد';
}

function getStudentBalance(studentId) {
  if (!studentId) return 0;
  // Calculate total fee plan
  const plan = feePlansList.find(p => p.student_id === studentId || p.studentId === studentId);
  const totalDue = Number(plan?.total_amount || 0);

  // Calculate sum of payments
  const paid = paymentsList
    .filter(p => (p.student_id === studentId || p.studentId === studentId) && !p.deleted_at)
    .reduce((sum, p) => sum + Number(p.amount || 0), 0);

  return Math.max(0, totalDue - paid);
}

function getStudentAbsences(studentId) {
  if (!studentId) return 0;
  return attendanceList.filter(a => 
    (a.student_id === studentId || a.studentId === studentId) &&
    !a.deleted_at &&
    ['غائب', 'غياب', 'absent'].includes(String(a.status || '').trim())
  ).length;
}

function isoDay(d) {
  return new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(d);
}

// student_attendance is not a CRUD resource; /reports/attendance is the read path.
// Window: last 90 days. Supervisors without the attendance permission get 403 -> empty list.
async function loadAttendance(api) {
  try {
    const to = new Date();
    const from = new Date(to.getTime() - 90 * 86400000);
    return toList(await api.get(`reports/attendance?from=${isoDay(from)}&to=${isoDay(to)}`));
  } catch (e) { return []; }
}

const TEMPLATES = {
  attendance: {
    name: '⚠️ إشعار غياب',
    build: (s, today, remaining) =>
      `السلام عليكم ورحمة الله وبركاته،\nولي أمر الطالب/ة ${s.name || ''} المحترم،\nنود إشعاركم بتسجيل غياب للطالب اليوم ${today}، ونأمل متابعة الحضور والحرص على الانتظام لما فيه مصلحة الطالب التعليمية.\nشاكرين ومقدرين حسن تعاونكم.\n— إدارة مركز غراس`
  },
  excellent: {
    name: '⭐ إشعار تميز وتفوق',
    build: (s) =>
      `السلام عليكم ورحمة الله وبركاته،\nيسعدنا في مركز غراس أن نهنئكم بتميز وتألق الطالب/ة ${s.name || ''} ⭐، ونثمن حرصه واجتهاده وتفوقه المستمر.\nنعتز بشراكتكم ودعمكم الدائم.\n— إدارة مركز غراس`
  },
  fees: {
    name: '💳 تذكير بالرسوم والأقساط',
    build: (s, today, remaining) =>
      `السلام عليكم ورحمة الله وبركاته،\nولي أمر الطالب/ة ${s.name || ''} المحترم،\nنود تذكيركم بأن المتبقي من الرسوم والأقساط الدراسية المستحقة هو (${remaining ? fmtSAR(remaining) : '0.00 ر.س'}).\nنأمل التكرم بالمتابعة والسداد، شاكرين لكم حرصكم وتجاوبكم المستمر لدعم العملية التعليمية.\n— الإدارة المالية - مركز غراس`
  },
  followup: {
    name: '📝 ملاحظة متابعة تربوية',
    build: (s) =>
      `السلام عليكم ورحمة الله وبركاته،\nولي أمر الطالب/ة ${s.name || ''} المحترم،\nنود مشاركتكم ملاحظة متابعة دراسية وتربوية للطالب/ة ${s.name || ''}، ويسعدنا تواصلكم المستمر معنا لتحقيق أفضل تقدم ومستوى للطالب.\nمع خالص التقدير،\n— إدارة مركز غراس`
  },
  report: {
    name: '📊 إشعار التقرير الشهري',
    build: (s) =>
      `السلام عليكم ورحمة الله وبركاته،\nنفيدكم بصدور التقرير الدوري للطالب/ة ${s.name || ''}، ويمكنكم الاطلاع على تفاصيل الأداء والتقدم الدراسي بزيارة المركز أو التواصل مع الإدارة.\n— إدارة مركز غراس`
  },
  task: {
    name: '📚 واجب دراسي جديد',
    build: (s) =>
      `السلام عليكم ورحمة الله وبركاته،\nولي أمر الطالب/ة ${s.name || ''} المحترم،\nنود إحاطتكم بوجود تكليف دراسي جديد مطلوب متابعته مع الطالب/ة ${s.name || ''} لتعزيز التحصيل الدراسي ومراجعته.\n— إدارة مركز غراس`
  },
  custom: {
    name: '✍️ رسالة مخصصة / تنبيه عام',
    build: (s) =>
      `السلام عليكم ورحمة الله وبركاته،\nولي أمر الطالب/ة ${s.name || ''} المحترم،\n\n— إدارة مركز غراس`
  }
};

export function openWhatsApp(phone, text) {
  const norm = normalizePhone(phone);
  if (!norm) {
    toast('رقم الهاتف غير مسجل أو غير صحيح', true);
    return false;
  }
  const encoded = encodeURIComponent(text);
  const url = `https://wa.me/${norm}?text=${encoded}`;
  window.open(url, '_blank', 'noopener,noreferrer');
  return true;
}

export async function logMessage(api, studentId, body, templateKey = 'custom') {
  try {
    const res = await api.post('messages', {
      student_id: studentId,
      channel: 'whatsapp',
      template_key: templateKey,
      body: body,
      status: 'sent'
    });
    return res;
  } catch (err) {
    console.warn('Could not log message to server:', err);
    return null;
  }
}

export async function render(container, api) {
  studentsList = [];
  roomsList = [];
  messagesList = [];
  feePlansList = [];
  paymentsList = [];
  attendanceList = [];

  try { studentsList = toList(await api.fetchAll('students')); } catch (e) { toast(e.message, true); }
  try { roomsList = toList(await api.fetchAll('rooms')); } catch (e) {}
  try { messagesList = toList(await api.fetchAll('messages')); } catch (e) {}
  try { feePlansList = toList(await api.fetchAll('fee-plans')); } catch (e) {}
  try { paymentsList = toList(await api.fetchAll('payments')); } catch (e) {}
  attendanceList = await loadAttendance(api);

  // Filter out deleted students
  studentsList = studentsList.filter(s => !s.deleted_at);

  const todayStr = new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());

  const root = el('div', { class: 'comm-view' });

  // Header & Stats
  const validPhonesCount = studentsList.filter(s => normalizePhone(getStudentPhone(s))).length;
  const sentMessagesCount = messagesList.length;

  const header = el('div', { class: 'view-header' },
    el('div', {},
      el('h1', {}, '💬 مركز التواصل مع أولياء الأمور'),
      el('p', { class: 'muted' }, 'إرسال التنبيهات والرسائل الفورية عبر واتساب ومتابعة سجل التواصل بالمركز')
    )
  );

  const kpis = el('section', { class: 'kpis' },
    el('div', { class: 'card kpi' },
      el('span', { class: 'muted' }, 'إجمالي الطلاب'),
      el('strong', {}, String(studentsList.length))
    ),
    el('div', { class: 'card kpi' },
      el('span', { class: 'muted' }, 'أرقام هواتف مسجلة'),
      el('strong', {}, String(validPhonesCount))
    ),
    el('div', { class: 'card kpi' },
      el('span', { class: 'muted' }, 'إجمالي الرسائل بالسجل'),
      el('strong', { id: 'comm-stat-sent' }, String(sentMessagesCount))
    ),
    el('div', { class: 'card kpi' },
      el('span', { class: 'muted' }, 'تاريخ اليوم'),
      el('strong', { style: 'font-size:17px;' }, todayStr)
    )
  );

  // Tabs Bar
  const tabContent = el('div', { class: 'tab-content' });

  const tabsBar = el('div', { class: 'tabs-bar' });
  const tabs = [
    { id: 'composer', label: '✍️ إرسال رسالة جديدة' },
    { id: 'history', label: `📋 سجل الرسائل (${messagesList.length})` },
    { id: 'directory', label: `👥 دليل هواتف أولياء الأمور (${studentsList.length})` }
  ];

  function switchTab(tabId, preselectedStudentId = null, preselectedTemplate = null) {
    activeTab = tabId;
    tabsBar.querySelectorAll('.tab-btn').forEach(btn => {
      btn.classList.toggle('active', btn.dataset.tab === tabId);
    });
    tabContent.replaceChildren();

    if (tabId === 'composer') {
      tabContent.append(renderComposer(api, todayStr, preselectedStudentId, preselectedTemplate));
    } else if (tabId === 'history') {
      tabContent.append(renderHistory(api));
    } else if (tabId === 'directory') {
      tabContent.append(renderDirectory(api, (sid) => switchTab('composer', sid)));
    }
  }

  tabs.forEach(t => {
    const btn = el('button', {
      class: `tab-btn ${t.id === activeTab ? 'active' : ''}`,
      'data-tab': t.id,
      type: 'button',
      onclick: () => switchTab(t.id)
    }, t.label);
    tabsBar.append(btn);
  });

  root.append(header, kpis, tabsBar, tabContent);
  container.replaceChildren(root);

  switchTab(activeTab);
}

// -------------------------------------------------------------
// Component 1: Interactive Composer
// -------------------------------------------------------------
function renderComposer(api, todayStr, preselectedStudentId = null, preselectedTemplate = null) {
  const panel = el('div', { class: 'panel', style: 'display:grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap:20px;' });

  // Right column: selection & student insight card
  const rightCol = el('div', { class: 'card' },
    el('h3', { style: 'margin-top:0; color:var(--g-teal-dark);' }, '1. تحديد الطالب ونوع الرسالة')
  );

  // Filter by room
  const roomSelect = el('select', { style: 'margin-bottom:12px;' },
    el('option', { value: '' }, 'جميع القاعات والفصول'),
    ...roomsList.map(r => el('option', { value: r.id }, r.name || `قاعة ${r.id}`))
  );

  // Student select
  const studentSelect = el('select', { style: 'margin-bottom:14px;', required: 'required' },
    el('option', { value: '' }, '— اختر الطالب —')
  );

  function populateStudents(filterRoomId = '') {
    studentSelect.replaceChildren(el('option', { value: '' }, '— اختر الطالب —'));
    const filtered = filterRoomId
      ? studentsList.filter(s => s.room_id === filterRoomId || s.roomId === filterRoomId)
      : studentsList;
    filtered.forEach(s => {
      const opt = el('option', { value: s.id }, `${s.name || 'بدون اسم'} (${getRoomName(s.room_id || s.roomId)})`);
      studentSelect.append(opt);
    });
  }

  populateStudents();

  roomSelect.onchange = () => {
    populateStudents(roomSelect.value);
    updateStudentDetails();
  };

  // Guardian & Academic insight preview card
  const guardianCard = el('div', {
    style: 'background:var(--g-bg); border:1px solid var(--g-border); border-radius:10px; padding:14px; margin-bottom:14px; font-size:13px;'
  }, el('span', { class: 'muted' }, 'اختر طالباً لعرض بيانات ولي الأمر والملخص المالي والتربوي'));

  // Template selector
  const templateSelect = el('select', { style: 'margin-bottom:14px;' },
    ...Object.entries(TEMPLATES).map(([key, t]) => el('option', { value: key }, t.name))
  );

  if (preselectedTemplate && TEMPLATES[preselectedTemplate]) {
    templateSelect.value = preselectedTemplate;
  }

  rightCol.append(
    el('label', {}, 'تصفية بالقاعة', roomSelect),
    el('label', {}, 'الطالب المستهدف', studentSelect),
    guardianCard,
    el('label', {}, 'نوع / قالب الرسالة', templateSelect)
  );

  // Left column: message editor & actions
  const leftCol = el('div', { class: 'card' },
    el('h3', { style: 'margin-top:0; color:var(--g-teal-dark);' }, '2. صياغة الرسالة والمعاينة')
  );

  const textarea = el('textarea', {
    rows: '9',
    placeholder: 'نص الرسالة...',
    style: 'line-height:1.7; font-size:14px; padding:12px; resize:vertical;'
  });

  const charCounter = el('div', { class: 'muted', style: 'font-size:12px; text-align:left; margin-top:4px;' }, '0 حرف');
  textarea.oninput = () => {
    charCounter.textContent = `${textarea.value.length} حرف`;
  };

  function getSelectedStudent() {
    const sid = studentSelect.value;
    return studentsList.find(s => s.id === sid);
  }

  // Quick insertion tags
  function insertTag(tagText) {
    const s = getSelectedStudent();
    let val = tagText;
    if (tagText === '{اسم الطالب}') val = s ? (s.name || 'الطالب') : 'الطالب';
    if (tagText === '{ولي الأمر}') val = s ? getGuardianName(s) : 'ولي الأمر المحترم';
    if (tagText === '{القاعة}') val = s ? getRoomName(s.room_id || s.roomId) : 'القاعة';
    if (tagText === '{المبلغ المتبقي}') {
      const bal = s ? getStudentBalance(s.id) : 0;
      val = fmtSAR(bal);
    }

    const start = textarea.selectionStart || textarea.value.length;
    const end = textarea.selectionEnd || textarea.value.length;
    textarea.value = textarea.value.slice(0, start) + val + textarea.value.slice(end);
    textarea.focus();
    textarea.selectionStart = textarea.selectionEnd = start + val.length;
    charCounter.textContent = `${textarea.value.length} حرف`;
  }

  const tagsRow = el('div', { style: 'display:flex; gap:6px; flex-wrap:wrap; margin-bottom:8px;' },
    el('span', { class: 'muted', style: 'font-size:12px; align-self:center;' }, 'إدراج سريع:'),
    makeTagButton('[اسم الطالب]', () => insertTag('{اسم الطالب}')),
    makeTagButton('[ولي الأمر]', () => insertTag('{ولي الأمر}')),
    makeTagButton('[القاعة]', () => insertTag('{القاعة}')),
    makeTagButton('[المبلغ المتبقي]', () => insertTag('{المبلغ المتبقي}')),
    makeTagButton('[التاريخ]', () => insertTag(todayStr)),
    makeTagButton('[مركز غراس]', () => insertTag('مركز غراس'))
  );

  function updateStudentDetails() {
    const s = getSelectedStudent();
    if (!s) {
      guardianCard.replaceChildren(el('span', { class: 'muted' }, 'اختر طالباً لعرض بيانات ولي الأمر والملخص المالي والتربوي'));
      return;
    }
    const phone = getStudentPhone(s);
    const norm = normalizePhone(phone);
    const hasPhone = Boolean(norm);
    const balance = getStudentBalance(s.id);
    const absences = getStudentAbsences(s.id);

    guardianCard.replaceChildren(
      el('div', { style: 'display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;' },
        el('strong', { style: 'font-size:14px;' }, `👤 ${getGuardianName(s)}`),
        el('span', { class: `badge ${hasPhone ? '' : 'red'}` }, hasPhone ? 'رقم مسجل 🟢' : 'غير مسجل ⚠️')
      ),
      el('div', { style: 'display:grid; grid-template-columns:1fr 1fr; gap:6px; margin-bottom:8px; font-size:12px;' },
        el('div', {}, el('span', { class: 'muted' }, 'الجوال: '), el('b', { style: 'direction:ltr; display:inline-block;' }, phone || '—')),
        el('div', {}, el('span', { class: 'muted' }, 'القاعة: '), el('b', {}, getRoomName(s.room_id || s.roomId))),
        el('div', {}, el('span', { class: 'muted' }, 'المتبقي: '), el('strong', { style: balance > 0 ? 'color:var(--g-red);' : 'color:var(--g-teal);' }, fmtSAR(balance))),
        el('div', {}, el('span', { class: 'muted' }, 'الغياب (آخر 90 يوماً): '), el('b', { style: absences > 0 ? 'color:var(--g-orange);' : '' }, `${absences} يوم`))
      )
    );

    // Auto-generate template text if student is selected
    applyTemplate();
  }

  function applyTemplate() {
    const s = getSelectedStudent();
    const tKey = templateSelect.value;
    const tmpl = TEMPLATES[tKey] || TEMPLATES.custom;
    const remaining = s ? getStudentBalance(s.id) : 0;
    if (s) {
      textarea.value = tmpl.build(s, todayStr, remaining);
    } else {
      textarea.value = tmpl.build({ name: '[اسم الطالب]' }, todayStr, 0);
    }
    charCounter.textContent = `${textarea.value.length} حرف`;
  }

  studentSelect.onchange = updateStudentDetails;
  templateSelect.onchange = applyTemplate;

  // Actions
  const btnWhatsApp = el('button', {
    class: 'button',
    type: 'button',
    style: 'background:#25d366; font-weight:700; display:inline-flex; align-items:center; gap:6px;'
  }, '🟢 إرسال وتوثيق عبر واتساب');

  const btnSaveOnly = el('button', {
    class: 'button button-outline',
    type: 'button'
  }, '💾 حفظ في السجل فقط');

  btnWhatsApp.onclick = async () => {
    const s = getSelectedStudent();
    if (!s) { toast('يرجى اختيار الطالب أولاً', true); return; }
    const phone = getStudentPhone(s);
    const norm = normalizePhone(phone);
    if (!norm) { toast('لا يوجد رقم جوال مسجل لهذا الطالب', true); return; }
    const text = textarea.value.trim();
    if (!text) { toast('نص الرسالة فارغ', true); return; }

    btnWhatsApp.disabled = true;
    // 1. Log to server
    const logged = await logMessage(api, s.id, text, templateSelect.value);
    if (logged) {
      messagesList.unshift(logged);
    }

    // 2. Open WhatsApp
    openWhatsApp(norm, text);
    toast('تم توثيق الرسالة وفتح تطبيق واتساب');
    btnWhatsApp.disabled = false;
  };

  btnSaveOnly.onclick = async () => {
    const s = getSelectedStudent();
    if (!s) { toast('يرجى اختيار الطالب أولاً', true); return; }
    const text = textarea.value.trim();
    if (!text) { toast('نص الرسالة فارغ', true); return; }

    btnSaveOnly.disabled = true;
    const logged = await logMessage(api, s.id, text, templateSelect.value);
    btnSaveOnly.disabled = false;
    if (logged) {
      messagesList.unshift(logged);
      toast('تم حفظ الرسالة في سجل التواصل بنجاح');
    } else {
      toast('تعذر حفظ الرسالة على الخادم', true);
    }
  };

  leftCol.append(
    tagsRow,
    textarea,
    charCounter,
    el('div', { class: 'form-actions', style: 'margin-top:14px;' },
      btnWhatsApp,
      btnSaveOnly
    )
  );

  panel.append(rightCol, leftCol);

  // Pre-select student if provided
  if (preselectedStudentId) {
    studentSelect.value = preselectedStudentId;
    updateStudentDetails();
  }

  return panel;
}

function makeTagButton(label, onclick) {
  return el('button', {
    class: 'button button-outline',
    type: 'button',
    style: 'padding:3px 8px; font-size:11px; border-radius:4px;',
    onclick
  }, label);
}

// -------------------------------------------------------------
// Component 2: Communication History Log
// -------------------------------------------------------------
function renderHistory(api) {
  const panel = el('div', { class: 'panel' });

  const filterInput = el('input', {
    type: 'text',
    placeholder: 'بحث في سجل الرسائل (اسم الطالب، نص الرسالة)...',
    style: 'max-width:350px; margin-bottom:14px;'
  });

  const tbody = el('tbody');
  const tableWrap = el('div', { class: 'table-wrap' },
    el('table', { class: 'table' },
      el('thead', {},
        el('tr', {},
          el('th', {}, 'التاريخ والوقت'),
          el('th', {}, 'الطالب'),
          el('th', {}, 'القناة'),
          el('th', {}, 'نوع الرسالة'),
          el('th', {}, 'نص الرسالة'),
          el('th', {}, 'الحالة'),
          el('th', {}, 'إجراء')
        )
      ),
      tbody
    )
  );

  function getStudent(id) {
    return studentsList.find(s => s.id === id);
  }

  function renderRows(query = '') {
    const q = query.trim().toLowerCase();
    const rows = messagesList.filter(m => {
      if (!q) return true;
      const s = getStudent(m.student_id || m.studentId);
      const name = (s?.name || '').toLowerCase();
      const body = (m.body || '').toLowerCase();
      return name.includes(q) || body.includes(q);
    });

    if (!rows.length) {
      tbody.replaceChildren(
        el('tr', {}, el('td', { colspan: '7', class: 'muted', style: 'text-align:center; padding:24px;' }, 'لا توجد رسائل مسجلة مطابقة للبحث'))
      );
      return;
    }

    tbody.replaceChildren(
      ...rows.map(m => {
        const s = getStudent(m.student_id || m.studentId);
        const sName = s ? s.name : 'طالب غير محدد';
        const tmpl = TEMPLATES[m.template_key || m.templateKey] || { name: m.template_key || 'رسالة' };
        const timeStr = m.created_at || m.sent_at || m.date || '';

        const previewText = (m.body || '').length > 60
          ? (m.body || '').slice(0, 60) + '...'
          : (m.body || '—');

        const reSendBtn = el('button', {
          class: 'button button-outline',
          style: 'padding:4px 9px; font-size:12px;',
          type: 'button',
          onclick: () => {
            if (s) {
              const phone = getStudentPhone(s);
              openWhatsApp(phone, m.body);
            } else {
              toast('تعذر العثور على هاتف الطالب', true);
            }
          }
        }, 'إعادة إرسال');

        return el('tr', {},
          el('td', {}, fmtDate(timeStr)),
          el('td', {}, el('strong', {}, sName)),
          el('td', {}, el('span', { class: 'badge' }, 'واتساب 🟢')),
          el('td', {}, el('span', { class: 'badge' }, tmpl.name)),
          el('td', { style: 'max-width:280px; white-space:normal; font-size:12px;' }, previewText),
          el('td', {}, el('span', { class: 'badge' }, m.status === 'failed' ? 'فشل ❌' : 'تم الإرسال ✓')),
          el('td', {}, reSendBtn)
        );
      })
    );
  }

  filterInput.oninput = () => renderRows(filterInput.value);
  renderRows();

  panel.append(
    el('div', { class: 'toolbar' }, filterInput),
    tableWrap
  );
  return panel;
}

// -------------------------------------------------------------
// Component 3: Guardian Phone Directory
// -------------------------------------------------------------
function renderDirectory(api, onComposeForStudent) {
  const panel = el('div', { class: 'panel' });

  const filterInput = el('input', {
    type: 'text',
    placeholder: 'بحث باسم الطالب أو ولي الأمر أو الجوال...',
    style: 'max-width:350px; margin-bottom:14px;'
  });

  const tbody = el('tbody');
  const tableWrap = el('div', { class: 'table-wrap' },
    el('table', { class: 'table' },
      el('thead', {},
        el('tr', {},
          el('th', {}, 'الطالب'),
          el('th', {}, 'القاعة'),
          el('th', {}, 'ولي الأمر'),
          el('th', {}, 'رقم الجوال'),
          el('th', {}, 'حالة الرقم'),
          el('th', {}, 'إجراء')
        )
      ),
      tbody
    )
  );

  function renderRows(query = '') {
    const q = query.trim().toLowerCase();
    const rows = studentsList.filter(s => {
      if (!q) return true;
      const sName = (s.name || '').toLowerCase();
      const gName = (getGuardianName(s)).toLowerCase();
      const phone = (getStudentPhone(s)).toLowerCase();
      return sName.includes(q) || gName.includes(q) || phone.includes(q);
    });

    if (!rows.length) {
      tbody.replaceChildren(
        el('tr', {}, el('td', { colspan: '6', class: 'muted', style: 'text-align:center; padding:24px;' }, 'لا توجد بيانات مطابقة للبحث'))
      );
      return;
    }

    tbody.replaceChildren(
      ...rows.map(s => {
        const phone = getStudentPhone(s);
        const norm = normalizePhone(phone);
        const hasPhone = Boolean(norm);

        const composeBtn = el('button', {
          class: 'button',
          style: 'padding:5px 12px; font-size:12px;',
          type: 'button',
          onclick: () => onComposeForStudent(s.id)
        }, '💬 مراسلة');

        return el('tr', {},
          el('td', {}, el('strong', {}, s.name || 'بدون اسم')),
          el('td', {}, getRoomName(s.room_id || s.roomId)),
          el('td', {}, getGuardianName(s)),
          el('td', { style: 'direction:ltr; text-align:right;' }, phone || '—'),
          el('td', {}, el('span', { class: `badge ${hasPhone ? '' : 'red'}` }, hasPhone ? 'مسجل 🟢' : 'مفقود ⚠️')),
          el('td', {}, composeBtn)
        );
      })
    );
  }

  filterInput.oninput = () => renderRows(filterInput.value);
  renderRows();

  panel.append(
    el('div', { class: 'toolbar' }, filterInput),
    tableWrap
  );
  return panel;
}

// -------------------------------------------------------------
// Quick Modal Export (for use anywhere in the dashboard)
// -------------------------------------------------------------
export async function openComposerModal(api, studentId = null, defaultTemplate = null) {
  if (!studentsList.length) {
    try { studentsList = toList(await api.fetchAll('students')); } catch (e) {}
    try { roomsList = toList(await api.fetchAll('rooms')); } catch (e) {}
    try { feePlansList = toList(await api.fetchAll('fee-plans')); } catch (e) {}
    try { paymentsList = toList(await api.fetchAll('payments')); } catch (e) {}
    attendanceList = await loadAttendance(api);
  }
  const todayStr = new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
  const content = renderComposer(api, todayStr, studentId, defaultTemplate);
  modal('💬 مركز التواصل السريع — واتساب', content);
}
