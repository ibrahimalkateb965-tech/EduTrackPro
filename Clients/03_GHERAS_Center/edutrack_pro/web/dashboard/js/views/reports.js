import { el, toast, modal } from '../ui.js';

const PRINT_BASE = '../print/templates/';

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

const STATUS_CONFIG = {
  draft: { label: 'مسودة', bg: '#f1f5f9', color: '#475569', border: '#cbd5e1' },
  pending_approval: { label: 'بانتظار الاعتماد', bg: '#fff7ed', color: '#c2410c', border: '#fed7aa' },
  needs_revision: { label: 'معاد للتعديل', bg: '#fef2f2', color: '#b91c1c', border: '#fecaca' },
  approved: { label: 'معتمد', bg: '#eff6ff', color: '#1d4ed8', border: '#bfdbfe' },
  published: { label: 'منشور للأسر', bg: '#f0fdf4', color: '#15803d', border: '#bbf7d0' }
};

const RATING_CONFIG = {
  'متميز': { bg: '#dcfce7', color: '#15803d' },
  'متقن': { bg: '#e0f2fe', color: '#0369a1' },
  'جيد': { bg: '#f3e8ff', color: '#7e22ce' },
  'يحتاج متابعة': { bg: '#ffedd5', color: '#c2410c' },
  'يحتاج دعم': { bg: '#fee2e2', color: '#b91c1c' }
};

const DEFAULT_SUBJECTS = ['القرآن الكريم', 'التربية الإسلامية', 'اللغة العربية', 'الرياضيات', 'العلوم'];

let students = [];
let rooms = [];
let audit = [];
let monthlyReports = [];
let activeTab = 'monthly';
let selectedMonth = '';
let selectedRoomId = '';
let selectedStatus = 'all';
let searchQuery = '';
let currentApi = null;

function toList(data) {
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.items)) return data.items;
  return [];
}

function currentMonth() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}

function studentOptions() {
  return [['', 'اختر الطالب'], ...students.map(s => [s.id, s.name || `طالب ${s.id}`])];
}

function roomOptions() {
  return [['', 'اختر القاعة'], ...rooms.map(r => [r.id, r.name || `قاعة ${r.id}`])];
}

function fmtDateTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  const day = new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(date);
  const time = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit' }).format(date);
  return `${day} ${time}`;
}

function statusBadge(status) {
  const cfg = STATUS_CONFIG[status] || { label: status || '—', bg: '#f1f5f9', color: '#475569', border: '#cbd5e1' };
  return el('span', {
    class: 'badge',
    style: `background:${cfg.bg}; color:${cfg.color}; border:1px solid ${cfg.border}; font-weight:600; padding:4px 10px; font-size:12px;`
  }, cfg.label);
}

function ratingBadge(rating) {
  const cfg = RATING_CONFIG[rating] || { bg: '#f1f5f9', color: '#475569' };
  return el('span', {
    class: 'badge',
    style: `background:${cfg.bg}; color:${cfg.color}; font-weight:600; font-size:11px; padding:2px 8px;`
  }, rating || '—');
}

// -----------------------------------------------------------------------------
// TAB 1: Print Documents & Audit Log (Preserved Legacy M2 Scope)
// -----------------------------------------------------------------------------

function selectControl(name, options, selected, required) {
  const attributes = { name };
  if (required) attributes.required = 'required';
  const node = el('select', attributes);
  options.forEach(([val, lbl]) => node.append(el('option', { value: val }, lbl)));
  node.value = selected === undefined || selected === null ? '' : String(selected);
  return node;
}

function fieldControl(field) {
  if (field.kind === 'student') return selectControl(field.query, studentOptions(), '', true);
  if (field.kind === 'room') return selectControl(field.query, roomOptions(), '', true);
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
      if (!value) { toast('أكمل الحقول المطلوبة أولاً', true); return; }
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

function auditRow(entry) {
  return el('tr', {},
    el('td', {}, fmtDateTime(entry.at)),
    el('td', {}, entry.actor || '—'),
    el('td', {}, entry.action || '—'),
    el('td', {}, entry.entity || '—')
  );
}

function renderPrintSection() {
  const isFinanceAllowed = !currentApi?.currentUser || currentApi.currentUser.role === 'manager';
  const financeDocs = ['admin_report', 'monthly_report', 'student_receipt', 'statistics_report'];
  const allowedDocs = DOCS.filter(doc => isFinanceAllowed || !financeDocs.includes(doc.file));

  const printSection = el('section', { class: 'panel' },
    el('div', { class: 'panel-head' }, el('h2', {}, 'مستندات الطباعة الرسمية (11)')),
    el('div', { class: 'cards-grid' }, ...allowedDocs.map(docCard))
  );

  const auditBody = el('tbody');
  const auditRows = audit.map(auditRow);
  auditBody.replaceChildren(...(auditRows.length ? auditRows : [el('tr', {}, el('td', { colspan: '4', class: 'muted' }, 'لا توجد عمليات مسجلة بعد'))]));

  const auditSection = el('section', { class: 'panel', style: 'margin-top: 24px;' },
    el('div', { class: 'panel-head' }, el('h2', {}, 'سجل العمليات الإدارية')),
    el('div', { class: 'table-wrap' },
      el('table', { class: 'table' },
        el('thead', {}, el('tr', {},
          el('th', {}, 'الوقت'),
          el('th', {}, 'المستخدم'),
          el('th', {}, 'العملية'),
          el('th', {}, 'الكيان')
        )),
        auditBody
      )
    )
  );

  const wrap = el('div', { class: 'tab-content' });
  wrap.replaceChildren(printSection, auditSection);
  return wrap;
}

// -----------------------------------------------------------------------------
// TAB 2: Interactive Milestone M5 Monthly Reports Portal
// -----------------------------------------------------------------------------

async function fetchMonthlyReports() {
  try {
    const params = new URLSearchParams();
    if (selectedMonth) params.set('month', selectedMonth);
    if (selectedRoomId) params.set('room_id', selectedRoomId);
    if (selectedStatus && selectedStatus !== 'all') params.set('status', selectedStatus);

    const queryStr = params.toString() ? `?${params.toString()}` : '';
    const data = await currentApi.get(`monthly-reports${queryStr}`);
    monthlyReports = toList(data);
  } catch (err) {
    toast(`خطأ أثناء تحميل التقارير: ${err.message}`, true);
    monthlyReports = [];
  }
}

function showReportDetailsModal(report, onActionDone) {
  const isSupervisorOrManager = !currentApi?.currentUser || ['manager', 'supervisor'].includes(currentApi.currentUser.role);
  const stats = report.attendance_stats || {};
  const ratings = Array.isArray(report.subject_ratings) ? report.subject_ratings : [];

  // 1. Attendance KPIs Box
  const statsBox = el('div', {
    class: 'card',
    style: 'background:#f8fafc; border:1px solid #e2e8f0; margin-bottom:16px; padding:14px;'
  },
    el('h4', { style: 'margin:0 0 10px 0; color:var(--g-teal-dark); font-size:14px;' }, '📊 إحصائيات الحضور والالتزام'),
    el('div', { style: 'display:grid; grid-template-columns:repeat(5, 1fr); gap:10px; text-align:center;' },
      el('div', {}, el('small', { class: 'muted' }, 'إجمالي الأيام'), el('strong', { style: 'display:block; font-size:16px;' }, String(stats.total_days ?? 0))),
      el('div', {}, el('small', { class: 'muted' }, 'أيام الحضور'), el('strong', { style: 'display:block; font-size:16px; color:#15803d;' }, String(stats.present ?? 0))),
      el('div', {}, el('small', { class: 'muted' }, 'غياب بدون عذر'), el('strong', { style: 'display:block; font-size:16px; color:#b91c1c;' }, String(stats.absent ?? 0))),
      el('div', {}, el('small', { class: 'muted' }, 'غياب بعذر'), el('strong', { style: 'display:block; font-size:16px; color:#0284c7;' }, String(stats.excused ?? 0))),
      el('div', {}, el('small', { class: 'muted' }, 'نسبة الحضور'), el('strong', { style: 'display:block; font-size:16px; color:var(--g-teal);' }, `${stats.attendance_pct ?? 0}%`))
    )
  );

  // 2. Subject Ratings Table
  const ratingsRows = ratings.map(r => el('tr', {},
    el('td', { style: 'font-weight:600;' }, r.subject || '—'),
    el('td', {}, ratingBadge(r.rating)),
    el('td', { class: 'muted', style: 'font-size:13px;' }, r.notes || '—')
  ));

  const ratingsBox = el('div', { class: 'table-wrap', style: 'margin-bottom:16px;' },
    el('h4', { style: 'margin:0 0 8px 0; color:var(--g-teal-dark); font-size:14px;' }, '📚 مصفوفة تقييم المواد والمهارات'),
    el('table', { class: 'table' },
      el('thead', {}, el('tr', {},
        el('th', {}, 'المادة / المهارة'),
        el('th', {}, 'التقدير'),
        el('th', {}, 'ملاحظات المعلمة')
      )),
      el('tbody', {}, ...(ratingsRows.length ? ratingsRows : [el('tr', {}, el('td', { colspan: '3', class: 'muted' }, 'لم يتم تقييم مواد بعد'))]))
    )
  );

  // 3. Qualitative Notes Cards
  const notesGrid = el('div', { style: 'display:grid; grid-template-columns:repeat(3, 1fr); gap:12px; margin-bottom:16px;' },
    el('div', { class: 'card', style: 'background:#f0fdf4; border-color:#bbf7d0;' },
      el('strong', { style: 'color:#166534; display:block; margin-bottom:4px;' }, '🌟 نقاط القوة والتميز'),
      el('p', { style: 'margin:0; font-size:13px;' }, report.strengths || 'لا توجد ملاحظات')
    ),
    el('div', { class: 'card', style: 'background:#fefce8; border-color:#fef08a;' },
      el('strong', { style: 'color:#854d0e; display:block; margin-bottom:4px;' }, '🎯 جوانب التحسين'),
      el('p', { style: 'margin:0; font-size:13px;' }, report.improvements || 'لا توجد ملاحظات')
    ),
    el('div', { class: 'card', style: 'background:#eff6ff; border-color:#bfdbfe;' },
      el('strong', { style: 'color:#1e40af; display:block; margin-bottom:4px;' }, '💡 التوصيات المنزلية'),
      el('p', { style: 'margin:0; font-size:13px;' }, report.recommendations || 'لا توجد توصيات')
    )
  );

  // 4. Revision notes if exists
  let revisionBox = null;
  if (report.revision_notes) {
    revisionBox = el('div', { class: 'card', style: 'background:#fef2f2; border:1px solid #fecaca; margin-bottom:16px;' },
      el('strong', { style: 'color:#991b1b; display:block; margin-bottom:4px;' }, '⚠️ ملاحظات المشرفة للتعديل:'),
      el('p', { style: 'margin:0; font-size:13px; color:#7f1d1d;' }, report.revision_notes)
    );
  }

  // 5. Actions Footer
  const actionsWrap = el('div', { class: 'form-actions', style: 'justify-content:space-between; margin-top:20px; align-items:center;' });
  const rightButtons = el('div', { style: 'display:flex; gap:8px;' });
  const leftButtons = el('div', { style: 'display:flex; gap:8px;' });

  // Print button
  const printBtn = el('button', {
    class: 'button button-outline',
    type: 'button',
    onclick: () => window.open(`${PRINT_BASE}monthly_report.html?month=${report.month}&student=${report.student_id}`, '_blank')
  }, '🖨️ طباعة التقرير');
  rightButtons.append(printBtn);

  let currentModal = null;

  if (isSupervisorOrManager) {
    // Approve button (if pending_approval or needs_revision)
    if (['pending_approval', 'needs_revision'].includes(report.status)) {
      const approveBtn = el('button', {
        class: 'button',
        type: 'button',
        style: 'background:#16a34a;',
        onclick: async () => {
          try {
            await currentApi.post(`monthly-reports/${report.id}/review`, { action: 'approve' });
            toast('تم اعتماد التقرير الشهري بنجاح');
            currentModal?.close();
            onActionDone();
          } catch (err) {
            toast(err.message, true);
          }
        }
      }, '✅ اعتماد التقرير');
      leftButtons.append(approveBtn);
    }

    // Request revision button (if pending_approval)
    if (report.status === 'pending_approval') {
      const revisionBtn = el('button', {
        class: 'button button-outline',
        type: 'button',
        style: 'border-color:#dc2626; color:#dc2626;',
        onclick: () => {
          const notesInput = el('textarea', {
            placeholder: 'اكتب التوجيهات والملاحظات المطلوب من المعلمة تعديلها...',
            style: 'min-height:90px; margin-top:8px;'
          });
          const submitRevBtn = el('button', {
            class: 'button button-danger',
            type: 'button',
            onclick: async () => {
              const notes = notesInput.value.trim();
              if (notes.length < 5) {
                toast('يرجى كتابة ملاحظات واضحة لا تقل عن 5 أحرف', true);
                return;
              }
              try {
                await currentApi.post(`monthly-reports/${report.id}/review`, { action: 'request_revision', revision_notes: notes });
                toast('تمت إعادة التقرير للمعلمة للتعديل بنجاح');
                revModal.close();
                currentModal?.close();
                onActionDone();
              } catch (err) {
                toast(err.message, true);
              }
            }
          }, 'تأكيد طلب التعديل');

          const revModal = modal('إعادة التقرير للمعلمة للتعديل', el('div', {},
            el('p', { class: 'muted' }, 'ستظهر هذه الملاحظات في واجهة المعلمة لإرشادها أثناء تعديل التقرير.'),
            notesInput,
            el('div', { class: 'form-actions', style: 'justify-content:flex-end; margin-top:12px;' }, submitRevBtn)
          ));
        }
      }, '↩️ طلب تعديل');
      leftButtons.append(revisionBtn);
    }

    // Publish button (if approved)
    if (report.status === 'approved') {
      const publishBtn = el('button', {
        class: 'button',
        type: 'button',
        style: 'background:#059669;',
        onclick: async () => {
          try {
            await currentApi.post(`monthly-reports/${report.id}/publish`, {});
            toast('تم نشر التقرير للأسر وإرسال الإشعار بنجاح');
            currentModal?.close();
            onActionDone();
          } catch (err) {
            toast(err.message, true);
          }
        }
      }, '📢 نشر لولي الأمر');
      leftButtons.append(publishBtn);
    }
  }

  actionsWrap.append(rightButtons, leftButtons);

  const content = el('div', { class: 'report-details-view' },
    el('div', { style: 'display:flex; justify-content:space-between; align-items:center; margin-bottom:14px;' },
      el('div', {},
        el('h3', { style: 'margin:0; font-size:17px;' }, `${report.student_name || 'الطالب'} — شهر ${report.month}`),
        el('span', { class: 'muted', style: 'font-size:13px;' }, `القاعة: ${report.room_name || '—'} | المعلمة: ${report.teacher_name || '—'}`)
      ),
      statusBadge(report.status)
    ),
    statsBox,
    ratingsBox,
    notesGrid,
    revisionBox,
    actionsWrap
  );

  currentModal = modal(`تقرير شهري: ${report.student_name || ''}`, content);
}

function showCreateReportModal(onCreated) {
  const roomSelect = selectControl('room_id', roomOptions(), selectedRoomId || '', true);
  const studentSelect = selectControl('student_id', [['', 'اختر القاعة أولاً']], '', true);
  const monthInput = el('input', { type: 'month', value: selectedMonth || currentMonth(), required: 'required' });

  // Update students when room changes
  function updateStudentsForRoom() {
    const roomId = roomSelect.value;
    const filtered = roomId ? students.filter(s => s.room_id === roomId) : students;
    studentSelect.replaceChildren(
      el('option', { value: '' }, 'اختر الطالب'),
      ...filtered.map(s => el('option', { value: s.id }, s.name || `طالب ${s.id}`))
    );
  }
  roomSelect.addEventListener('change', updateStudentsForRoom);
  if (selectedRoomId) updateStudentsForRoom();

  // Auto-stats preview box
  const statsPreview = el('div', {
    class: 'card',
    style: 'background:#f8fafc; border:1px dashed #cbd5e1; padding:10px; margin-bottom:14px; font-size:13px;'
  }, 'اختر الطالب والشهر لجلب الإحصائيات التلقائية للحضور والغياب.');

  let fetchedStats = null;

  async function tryFetchAutoStats() {
    const stId = studentSelect.value;
    const m = monthInput.value;
    if (!stId || !m) return;
    try {
      statsPreview.textContent = 'جاري احتساب الإحصائيات من واقع سجل الحضور...';
      const stats = await currentApi.get(`monthly-reports/auto-stats?student_id=${stId}&month=${m}`);
      fetchedStats = stats;
      statsPreview.replaceChildren(
        el('div', { style: 'display:flex; justify-content:space-around; text-align:center;' },
          el('span', {}, `إجمالي الأيام: ${stats.total_days || 0}`),
          el('span', { style: 'color:#15803d; font-weight:600;' }, `حاضر: ${stats.present || 0}`),
          el('span', { style: 'color:#b91c1c; font-weight:600;' }, `غائب: ${stats.absent || 0}`),
          el('span', { style: 'color:#0284c7; font-weight:600;' }, `بعذر: ${stats.excused || 0}`),
          el('span', { style: 'color:var(--g-teal); font-weight:700;' }, `الالتزام: ${stats.attendance_pct || 0}%`)
        )
      );
    } catch {
      statsPreview.textContent = 'تعذر جلب الإحصائيات التلقائية لهذا الشهر.';
    }
  }

  studentSelect.addEventListener('change', tryFetchAutoStats);
  monthInput.addEventListener('change', tryFetchAutoStats);

  // Subject ratings table inputs
  const ratingRows = DEFAULT_SUBJECTS.map(subj => {
    const ratingSelect = selectControl('rating', [
      ['متميز', 'متميز'],
      ['متقن', 'متقن'],
      ['جيد', 'جيد'],
      ['يحتاج متابعة', 'يحتاج متابعة'],
      ['يحتاج دعم', 'يحتاج دعم']
    ], 'متقن', true);
    const noteInput = el('input', { type: 'text', placeholder: 'ملاحظة موجزة (اختياري)...', style: 'padding:6px;' });
    return { subj, ratingSelect, noteInput, tr: el('tr', {},
      el('td', { style: 'font-weight:600;' }, subj),
      el('td', {}, ratingSelect),
      el('td', {}, noteInput)
    )};
  });

  const strengthsInput = el('textarea', { placeholder: 'أبرز نقاط التميز في الحفظ والمشاركة...' });
  const improvementsInput = el('textarea', { placeholder: 'المهارات المطلوب التركيز عليها وتطويرها...' });
  const recommendationsInput = el('textarea', { placeholder: 'التوجيهات الموصى بها لولي الأمر في المنزل...' });

  let formModal = null;

  async function submitReport(action) {
    const roomId = roomSelect.value;
    const studentId = studentSelect.value;
    const month = monthInput.value;

    if (!roomId || !studentId || !month) {
      toast('يرجى اختيار القاعة والطالب والشهر المستهدف', true);
      return;
    }

    const payload = {
      student_id: studentId,
      room_id: roomId,
      month: month,
      attendance_stats: fetchedStats || {},
      subject_ratings: ratingRows.map(r => ({
        subject: r.subj,
        rating: r.ratingSelect.value,
        notes: r.noteInput.value.trim() || null
      })),
      strengths: strengthsInput.value.trim() || null,
      improvements: improvementsInput.value.trim() || null,
      recommendations: recommendationsInput.value.trim() || null,
      action: action
    };

    try {
      await currentApi.post('monthly-reports', payload);
      toast(action === 'submit' ? 'تم رفع التقرير الشهري للاعتماد بنجاح' : 'تم حفظ مسودة التقرير بنجاح');
      formModal?.close();
      onCreated();
    } catch (err) {
      toast(`تعذر حفظ التقرير: ${err.message}`, true);
    }
  }

  const form = el('form', { onsubmit: e => e.preventDefault() },
    el('div', { class: 'form-grid', style: 'margin-bottom:12px;' },
      el('label', {}, el('span', {}, 'القاعة (الفصل)'), roomSelect),
      el('label', {}, el('span', {}, 'الطالب'), studentSelect),
      el('label', {}, el('span', {}, 'الشهر المستهدف'), monthInput)
    ),
    statsPreview,
    el('h4', { style: 'margin:14px 0 8px 0; color:var(--g-teal-dark);' }, 'مصفوفة تقييم المواد'),
    el('div', { class: 'table-wrap', style: 'margin-bottom:14px;' },
      el('table', { class: 'table' },
        el('thead', {}, el('tr', {}, el('th', {}, 'المادة'), el('th', {}, 'التقدير'), el('th', {}, 'ملاحظات'))),
        el('tbody', {}, ...ratingRows.map(r => r.tr))
      )
    ),
    el('div', { style: 'display:grid; grid-template-columns:1fr 1fr 1fr; gap:10px; margin-bottom:16px;' },
      el('label', {}, el('span', {}, 'نقاط القوة'), strengthsInput),
      el('label', {}, el('span', {}, 'جوانب التحسين'), improvementsInput),
      el('label', {}, el('span', {}, 'توصيات الأسرة'), recommendationsInput)
    ),
    el('div', { class: 'form-actions', style: 'justify-content:flex-end; gap:10px;' },
      el('button', { class: 'button button-outline', type: 'button', onclick: () => submitReport('save_draft') }, '💾 حفظ كمسودة'),
      el('button', { class: 'button', type: 'button', onclick: () => submitReport('submit') }, '📤 إرسال للاعتماد')
    )
  );

  formModal = modal('إعداد تقرير شهري جديد للطالب', form);
}

function renderMonthlySection(container) {
  const isSupervisorOrManager = !currentApi?.currentUser || ['manager', 'supervisor'].includes(currentApi.currentUser.role);

  // 1. Filter reports by search query
  const filteredReports = monthlyReports.filter(rep => {
    if (!searchQuery) return true;
    const q = searchQuery.toLowerCase();
    const stName = (rep.student_name || '').toLowerCase();
    const teachName = (rep.teacher_name || '').toLowerCase();
    const rName = (rep.room_name || '').toLowerCase();
    return stName.includes(q) || teachName.includes(q) || rName.includes(q);
  });

  // 2. KPIs calculations
  const totalCount = monthlyReports.length;
  const pendingCount = monthlyReports.filter(r => r.status === 'pending_approval').length;
  const approvedCount = monthlyReports.filter(r => r.status === 'approved').length;
  const publishedCount = monthlyReports.filter(r => r.status === 'published').length;

  const kpiSection = el('div', { class: 'kpis' },
    el('div', { class: 'kpi card' },
      el('small', { class: 'muted' }, 'إجمالي تقارير الشهر'),
      el('strong', {}, String(totalCount))
    ),
    el('div', { class: 'kpi card', style: 'border-right-color:#f97316;' },
      el('small', { class: 'muted' }, 'بانتظار الاعتماد'),
      el('strong', { style: 'color:#ea580c;' }, String(pendingCount))
    ),
    el('div', { class: 'kpi card', style: 'border-right-color:#2563eb;' },
      el('small', { class: 'muted' }, 'معتمد وبانتظار النشر'),
      el('strong', { style: 'color:#2563eb;' }, String(approvedCount))
    ),
    el('div', { class: 'kpi card', style: 'border-right-color:#16a34a;' },
      el('small', { class: 'muted' }, 'منشور لأولياء الأمور'),
      el('strong', { style: 'color:#16a34a;' }, String(publishedCount))
    )
  );

  // 3. Toolbar Controls
  const monthFilter = el('input', {
    type: 'month',
    value: selectedMonth,
    style: 'max-width:160px;',
    title: 'الشهر المستهدف'
  });
  monthFilter.addEventListener('change', async () => {
    selectedMonth = monthFilter.value;
    await fetchMonthlyReports();
    renderMainView(container);
  });

  const roomFilter = selectControl('room_filter', [['', 'جميع القاعات'], ...rooms.map(r => [r.id, r.name])], selectedRoomId, false);
  roomFilter.style.maxWidth = '180px';
  roomFilter.addEventListener('change', async () => {
    selectedRoomId = roomFilter.value;
    await fetchMonthlyReports();
    renderMainView(container);
  });

  const statusSelect = selectControl('status_filter', [
    ['all', 'جميع الحالات'],
    ['pending_approval', 'بانتظار الاعتماد'],
    ['draft', 'مسودة'],
    ['needs_revision', 'معاد للتعديل'],
    ['approved', 'معتمد'],
    ['published', 'منشور للأسر']
  ], selectedStatus, false);
  statusSelect.style.maxWidth = '160px';
  statusSelect.addEventListener('change', async () => {
    selectedStatus = statusSelect.value;
    await fetchMonthlyReports();
    renderMainView(container);
  });

  const searchInput = el('input', {
    type: 'search',
    placeholder: 'بحث باسم الطالب أو المعلمة...',
    value: searchQuery,
    style: 'max-width:240px;'
  });
  searchInput.addEventListener('input', () => {
    searchQuery = searchInput.value.trim();
    renderTableBody(tbody);
  });

  const refreshBtn = el('button', {
    class: 'button button-outline',
    type: 'button',
    onclick: async () => {
      await fetchMonthlyReports();
      renderMainView(container);
    }
  }, '🔄 تحديث');

  const newReportBtn = el('button', {
    class: 'button',
    type: 'button',
    onclick: () => showCreateReportModal(async () => {
      await fetchMonthlyReports();
      renderMainView(container);
    })
  }, '➕ إعداد تقرير شهري');

  const batchPublishBtn = el('button', {
    class: 'button',
    type: 'button',
    style: 'background:#059669;',
    title: 'نشر كافة التقارير المعتمدة لهذه القاعة دفعة واحدة للأسر',
    onclick: async () => {
      if (!selectedRoomId) {
        toast('يرجى اختيار قاعة محددة أولاً لتنفيذ النشر الدفعي', true);
        return;
      }
      if (!confirm(`هل أنت متأكد من رغبتك في نشر كافة التقارير المعتمدة لهذه القاعة لشهر ${selectedMonth} لأولياء الأمور؟`)) {
        return;
      }
      try {
        const res = await currentApi.post('monthly-reports/batch-publish', {
          room_id: selectedRoomId,
          month: selectedMonth
        });
        toast(`تم نشر ${res?.published_count || approvedCount} تقرير بنجاح وإشعار الأسر`);
        await fetchMonthlyReports();
        renderMainView(container);
      } catch (err) {
        toast(`تعذر النشر الدفعي: ${err.message}`, true);
      }
    }
  }, '📢 نشر دفعي للفصل');

  const toolbar = el('div', { class: 'toolbar', style: 'justify-content:space-between; align-items:center; margin-bottom:18px;' },
    el('div', { style: 'display:flex; gap:8px; flex-wrap:wrap; align-items:center;' },
      monthFilter,
      roomFilter,
      statusSelect,
      searchInput,
      refreshBtn
    ),
    el('div', { style: 'display:flex; gap:8px;' },
      ...(isSupervisorOrManager && selectedRoomId ? [batchPublishBtn] : []),
      newReportBtn
    )
  );

  // 4. Reports Table Body
  const tbody = el('tbody');

  function renderTableBody(targetTbody) {
    if (!filteredReports.length) {
      targetTbody.replaceChildren(
        el('tr', {}, el('td', { colspan: '7', class: 'muted', style: 'text-align:center; padding:32px;' }, 'لا توجد تقارير مطابقة للفلاتر المحددة'))
      );
      return;
    }

    const rows = filteredReports.map(rep => {
      const stats = rep.attendance_stats || {};
      const pct = stats.attendance_pct ?? 0;
      const pctColor = pct >= 90 ? '#15803d' : (pct >= 75 ? '#0369a1' : '#c2410c');

      // Row Actions
      const viewBtn = el('button', {
        class: 'button button-outline',
        type: 'button',
        style: 'padding:4px 8px; font-size:12px;',
        title: 'معاينة ومراجعة التقرير',
        onclick: () => showReportDetailsModal(rep, async () => {
          await fetchMonthlyReports();
          renderMainView(container);
        })
      }, '👁️ معاينة');

      const printBtn = el('button', {
        class: 'button button-outline',
        type: 'button',
        style: 'padding:4px 8px; font-size:12px;',
        title: 'طباعة مستند رسمي',
        onclick: () => window.open(`${PRINT_BASE}monthly_report.html?month=${rep.month}&student=${rep.student_id}`, '_blank')
      }, '🖨️');

      const actionsCell = el('td', { style: 'display:flex; gap:6px;' }, viewBtn, printBtn);

      // Fast approve for supervisor if pending
      if (isSupervisorOrManager && rep.status === 'pending_approval') {
        const quickApproveBtn = el('button', {
          class: 'button',
          type: 'button',
          style: 'padding:4px 8px; font-size:12px; background:#16a34a;',
          title: 'اعتماد سريع',
          onclick: async () => {
            try {
              await currentApi.post(`monthly-reports/${rep.id}/review`, { action: 'approve' });
              toast(`تم اعتماد تقرير الطالب ${rep.student_name}`);
              await fetchMonthlyReports();
              renderMainView(container);
            } catch (err) {
              toast(err.message, true);
            }
          }
        }, '✅');
        actionsCell.append(quickApproveBtn);
      }

      // Fast publish for supervisor if approved
      if (isSupervisorOrManager && rep.status === 'approved') {
        const quickPublishBtn = el('button', {
          class: 'button',
          type: 'button',
          style: 'padding:4px 8px; font-size:12px; background:#059669;',
          title: 'نشر سريع لولي الأمر',
          onclick: async () => {
            try {
              await currentApi.post(`monthly-reports/${rep.id}/publish`, {});
              toast(`تم نشر تقرير الطالب ${rep.student_name} لولي الأمر`);
              await fetchMonthlyReports();
              renderMainView(container);
            } catch (err) {
              toast(err.message, true);
            }
          }
        }, '📢');
        actionsCell.append(quickPublishBtn);
      }

      return el('tr', {},
        el('td', { style: 'font-weight:700;' }, rep.student_name || '—'),
        el('td', {}, rep.room_name || '—'),
        el('td', { class: 'muted' }, rep.teacher_name || '—'),
        el('td', {},
          el('span', { style: `font-weight:700; color:${pctColor};` }, `${pct}%`),
          el('small', { class: 'muted', style: 'margin-right:6px;' }, `(${stats.present || 0} حاضر / ${stats.absent || 0} غياب)`)
        ),
        el('td', {}, statusBadge(rep.status)),
        el('td', { class: 'muted', style: 'font-size:12px;' }, fmtDateTime(rep.updated_at || rep.created_at)),
        actionsCell
      );
    });

    targetTbody.replaceChildren(...rows);
  }

  renderTableBody(tbody);

  const tableSection = el('section', { class: 'panel' },
    el('div', { class: 'panel-head' },
      el('h2', {}, `طابور التقارير الشهرية — لشهر ${selectedMonth}`),
      el('span', { class: 'muted', style: 'font-size:13px;' }, `${filteredReports.length} تقرير متوفر`)
    ),
    el('div', { class: 'table-wrap' },
      el('table', { class: 'table' },
        el('thead', {}, el('tr', {},
          el('th', {}, 'الطالب'),
          el('th', {}, 'القاعة (الفصل)'),
          el('th', {}, 'المعلمة'),
          el('th', {}, 'الحضور والالتزام'),
          el('th', {}, 'الحالة'),
          el('th', {}, 'تاريخ التحديث'),
          el('th', {}, 'الإجراءات')
        )),
        tbody
      )
    )
  );

  const wrap = el('div', { class: 'tab-content' });
  wrap.replaceChildren(kpiSection, toolbar, tableSection);
  return wrap;
}

// -----------------------------------------------------------------------------
// MAIN RENDER CONTROLLER
// -----------------------------------------------------------------------------

function renderMainView(container) {
  const pendingCount = monthlyReports.filter(r => r.status === 'pending_approval').length;

  const header = el('div', { class: 'view-header' },
    el('div', {},
      el('h1', {}, '📑 التقارير الشهرية ومستندات المركز'),
      el('p', { class: 'muted' }, 'إدارة واعتماد ونشر التقارير الشهرية للطلاب، واستخراج المستندات الرسمية وسجلات التدقيق')
    )
  );

  // Tabs Bar
  const tabMonthlyBtn = el('button', {
    class: `tab-btn${activeTab === 'monthly' ? ' active' : ''}`,
    type: 'button',
    onclick: () => {
      activeTab = 'monthly';
      renderMainView(container);
    }
  },
    '📊 تقارير الطلاب الشهرية (M5)',
    ...(pendingCount > 0 ? [el('span', { class: 'tab-badge', style: 'background:#ea580c; color:#fff;' }, String(pendingCount))] : [])
  );

  const tabPrintBtn = el('button', {
    class: `tab-btn${activeTab === 'print' ? ' active' : ''}`,
    type: 'button',
    onclick: () => {
      activeTab = 'print';
      renderMainView(container);
    }
  }, '📑 مستندات وسجلات الطباعة (11)');

  const tabsBar = el('div', { class: 'tabs-bar' }, tabMonthlyBtn, tabPrintBtn);

  const activeContent = activeTab === 'monthly' ? renderMonthlySection(container) : renderPrintSection();

  container.replaceChildren(header, tabsBar, activeContent);
}

export async function render(container, api) {
  currentApi = api;
  students = [];
  rooms = [];
  audit = [];
  monthlyReports = [];
  selectedMonth = currentMonth();

  // Load prerequisites
  try { students = toList(await api.fetchAll('students')); } catch (e) { toast(e.message, true); }
  try { rooms = toList(await api.fetchAll('rooms')); } catch (e) { toast(e.message, true); }
  try { audit = toList(await api.fetchAll('audit-log')); } catch (e) { toast(e.message, true); }

  // Initial fetch for monthly reports
  await fetchMonthlyReports();

  renderMainView(container);
}
