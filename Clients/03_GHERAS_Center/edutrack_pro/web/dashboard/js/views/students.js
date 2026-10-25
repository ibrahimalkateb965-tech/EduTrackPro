import { el, toast, modal, createDatePicker, fmtSAR, fmtDate } from '../ui.js';
import { openCreateStudentModal, openCreateGuardianModal } from '../account_modals.js';

const DEFAULT_GROUPS = ['الصباح', 'المساء', 'الإنجليزي', 'القدرات'];

export function getCustomGroups() {
  try {
    const raw = localStorage.getItem('gheras_custom_groups');
    return raw ? JSON.parse(raw) : [];
  } catch (err) {
    return [];
  }
}

export function saveCustomGroup(name) {
  const clean = String(name || '').trim();
  if (!clean) return null;
  const current = getCustomGroups();
  if (!DEFAULT_GROUPS.includes(clean) && !current.includes(clean)) {
    current.push(clean);
    localStorage.setItem('gheras_custom_groups', JSON.stringify(current));
  }
  return clean;
}

export function getAllGroups() {
  const fromStudents = (students || []).map(s => s.group_name).filter(Boolean);
  return Array.from(new Set([...DEFAULT_GROUPS, ...getCustomGroups(), ...fromStudents]));
}

export function openAddGroupModal(api, onAdded) {
  const nameInput = el('input', { type: 'text', placeholder: 'مثال: مسار الموهوبين، حفظ المتون...', required: 'required', style: 'width:100%; padding:8px; border-radius:8px; border:1px solid #cbd5e1;' });
  const saveBtn = el('button', { class: 'button', type: 'submit' }, 'إضافة المجموعة');
  const cancelBtn = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const modalForm = el('form', { class: 'form-grid' },
    el('label', { style: 'grid-column: 1 / -1;' },
      el('span', { style: 'font-weight:600; display:block; margin-bottom:6px;' }, 'اسم المجموعة الدراسية الجديدة *'),
      nameInput
    ),
    el('div', { class: 'form-actions', style: 'grid-column: 1 / -1; display:flex; gap:8px; margin-top:10px;' }, saveBtn, cancelBtn)
  );

  const dialog = modal('إضافة مجموعة دراسية جديدة', modalForm);
  cancelBtn.onclick = dialog.close;

  modalForm.onsubmit = ev => {
    ev.preventDefault();
    const clean = nameInput.value.trim();
    if (!clean) {
      toast('يرجى إدخال اسم المجموعة', true);
      return;
    }
    saveCustomGroup(clean);
    toast(`تمت إضافة المجموعة الدراسية "${clean}" بنجاح`);
    dialog.close();
    updateTabs(api);
    if (typeof onAdded === 'function') onAdded(clean);
  };
}

export function openDeleteStudentModal(api, student, onDeleted) {
  const confirmBtn = el('button', {
    class: 'button',
    type: 'submit',
    style: 'background: #dc2626; border-color: #b91c1c; color: #fff; font-weight: 700; padding: 8px 18px;'
  }, '🗑️ تأكيد الحذف');
  const cancelBtn = el('button', {
    class: 'button button-outline',
    type: 'button',
    style: 'padding: 8px 16px;'
  }, 'إلغاء');

  const warningContent = el('div', { style: 'display: flex; flex-direction: column; gap: 12px; min-width: 320px;' },
    el('div', { style: 'display: flex; align-items: center; gap: 10px;' },
      el('span', { style: 'font-size: 32px;' }, '⚠️'),
      el('div', {},
        el('div', { style: 'font-weight: 700; font-size: 16px; color: #1e293b;' }, `حذف الطالب: ${student.name || '—'}`),
        el('div', { style: 'font-size: 13px; color: #64748b; margin-top: 2px;' }, student.group_name ? `المجموعة: ${student.group_name}` : '')
      )
    ),
    el('p', { style: 'margin: 0; font-size: 14px; line-height: 1.6; color: #334155; background: #fef2f2; border: 1px solid #fee2e2; border-radius: 8px; padding: 12px 14px;' },
      `هل أنت متأكد من رغبتك في حذف الطالب "${student.name || ''}" نهائياً من النظام؟ سيتم حذف بياناته وسجلاته وأرشفتها.`
    ),
    el('div', { class: 'form-actions', style: 'display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px;' },
      cancelBtn,
      confirmBtn
    )
  );

  const modalForm = el('form', {}, warningContent);
  const { close } = modal('تأكيد حذف الطالب', modalForm);
  cancelBtn.onclick = () => close();

  modalForm.onsubmit = async ev => {
    ev.preventDefault();
    confirmBtn.disabled = true;
    confirmBtn.textContent = 'جاري الحذف...';
    try {
      await api.del(`students/${student.id}`);
      toast('تم حذف الطالب بنجاح');
      close();
      if (typeof onDeleted === 'function') {
        onDeleted();
      } else {
        await reload(api);
      }
    } catch (err) {
      toast(err.message || 'تعذر حذف الطالب', true);
      confirmBtn.disabled = false;
      confirmBtn.textContent = '🗑️ تأكيد الحذف';
    }
  };
}

const GUARDIAN_RELATIONS = ['الأب', 'الأم', 'ولي الأمر', 'شخص آخر'];
const STATUS_LABELS = { active: 'نشط', dismissed: 'منسحب', archived: 'مؤرشف' };
const YES_NO = [['false', 'لا'], ['true', 'نعم']];
const INTERVAL_OPTIONS = [[30, 'شهري (كل 30 يوم)'], [14, 'كل 14 يوم'], [7, 'أسبوعي (كل 7 أيام)']];

function isoTomorrow() {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

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
  const isManager = !api.currentUser || api.currentUser.role === 'manager';
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
  receiptBtn.addEventListener('click', () => openQuickPaymentModal(api, student));

  const deleteBtn = el('button', {
    class: 'button button-outline',
    type: 'button',
    style: 'color:#dc2626; border-color:#fca5a5; background:#fef2f2; padding:5px 8px; font-size:12px; margin-right:4px;',
    title: 'حذف هذا الطالب نهائياً من النظام'
  }, '🗑️ حذف');
  deleteBtn.addEventListener('click', () => openDeleteStudentModal(api, student));

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

async function openQuickPaymentModal(api, student) {
  const container = el('div', { style: 'min-width:320px; max-width:640px;' },
    el('div', { style: 'text-align:center; padding:36px 16px; color:#64748b; font-size:14px;' },
      el('div', { style: 'display:inline-block; margin-bottom:10px; font-size:26px;' }, '⏳'),
      el('div', { style: 'font-weight:600;' }, 'جاري تحميل بيانات الرسوم والأقساط للطالب...')
    )
  );

  const dialog = modal(`سند قبض وتحصيل الرسوم — ${student.name}`, container);

  let profile = null;
  try {
    profile = await api.get('reports/student/' + student.id);
  } catch (err) {
    container.replaceChildren(
      el('div', { style: 'padding:16px; background:#fee2e2; border-radius:8px; color:#991b1b; font-size:13px; text-align:center;' },
        'تعذر تحميل بيانات الرسوم: ' + (err.message || 'خطأ في الاتصال'),
        el('div', { style: 'margin-top:12px;' },
          el('button', { class: 'button', type: 'button', onclick: dialog.close }, 'إغلاق')
        )
      )
    );
    return;
  }

  const todayStr = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Riyadh' }).format(new Date());
  const stData = profile.student || student;
  const balance = profile.balance || { total_planned: 0, total_paid: 0, outstanding: 0 };
  const tuitionPlanned = Number(balance.total_planned || 0);
  const tuitionPaid = Number(balance.total_paid || 0);
  const tuitionOutstanding = Math.max(0, Number(balance.outstanding !== undefined ? balance.outstanding : (tuitionPlanned - tuitionPaid)));

  const hasBus = Boolean(stData.has_bus_subscription);
  const busFee = hasBus ? Number(stData.bus_fee || 0) : 0;
  const busPaid = hasBus ? Number(stData.bus_paid || 0) : 0;
  const busRemaining = hasBus ? Math.max(0, Number(stData.bus_remaining !== undefined ? stData.bus_remaining : (busFee - busPaid))) : 0;

  const totalOutstanding = tuitionOutstanding + busRemaining;

  const rawInstallments = profile.installments || [];
  const installments = rawInstallments.map(inst => {
    const amt = Number(inst.amount || 0);
    const paid = Number(inst.paid_amount || 0);
    const rem = Math.max(0, amt - paid);
    return { ...inst, amountNum: amt, paidNum: paid, remainingNum: rem };
  });
  const unpaidInstallments = installments.filter(inst => inst.remainingNum > 0);

  // 1. Summary Cards
  const summaryGrid = el('div', {
    style: 'display:grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap:10px; margin-bottom:14px;'
  },
    // Tuition Card
    el('div', {
      style: `padding:10px 12px; border-radius:8px; border:1px solid ${tuitionOutstanding > 0 ? '#fed7aa' : '#bbf7d0'}; background:${tuitionOutstanding > 0 ? '#fff7ed' : '#f0fdf4'};`
    },
      el('div', { style: 'font-size:12px; font-weight:700; color:#475569; display:flex; justify-content:space-between; align-items:center;' },
        el('span', {}, '🎓 الرسوم الدراسية'),
        tuitionOutstanding <= 0 ? el('span', { style: 'color:#15803d; font-size:11px;' }, '✓ مسدد بالكامل') : null
      ),
      el('div', { style: 'font-size:18px; font-weight:800; color:' + (tuitionOutstanding > 0 ? '#c2410c' : '#15803d') + '; margin:4px 0;' },
        fmtSAR(tuitionOutstanding)
      ),
      el('div', { style: 'font-size:11px; color:#64748b;' },
        `المسدد: ${fmtSAR(tuitionPaid)} / الإجمالي: ${fmtSAR(tuitionPlanned)}`
      )
    ),

    // Bus Card
    el('div', {
      style: `padding:10px 12px; border-radius:8px; border:1px solid ${hasBus ? (busRemaining > 0 ? '#fed7aa' : '#bbf7d0') : '#e2e8f0'}; background:${hasBus ? (busRemaining > 0 ? '#fff7ed' : '#f0fdf4') : '#f8fafc'};`
    },
      el('div', { style: 'font-size:12px; font-weight:700; color:#475569; display:flex; justify-content:space-between; align-items:center;' },
        el('span', {}, '🚌 رسوم الباص والنقل'),
        hasBus ? (busRemaining <= 0 ? el('span', { style: 'color:#15803d; font-size:11px;' }, '✓ مسدد بالكامل') : null) : el('span', { style: 'color:#94a3b8; font-size:11px;' }, 'غير مشترك')
      ),
      el('div', { style: 'font-size:18px; font-weight:800; color:' + (hasBus ? (busRemaining > 0 ? '#c2410c' : '#15803d') : '#94a3b8') + '; margin:4px 0;' },
        hasBus ? fmtSAR(busRemaining) : '0.00 ر.س'
      ),
      el('div', { style: 'font-size:11px; color:#64748b;' },
        hasBus ? `المسدد: ${fmtSAR(busPaid)} / الإجمالي: ${fmtSAR(busFee)}` : 'الطالب غير مشترك بخدمة النقل'
      )
    ),

    // Total Remaining Card
    el('div', {
      style: `padding:10px 12px; border-radius:8px; border:1px solid ${totalOutstanding > 0 ? '#fca5a5' : '#bbf7d0'}; background:${totalOutstanding > 0 ? '#fef2f2' : '#f0fdf4'};`
    },
      el('div', { style: 'font-size:12px; font-weight:700; color:#475569;' }, '💰 إجمالي المتبقي الكلي'),
      el('div', { style: 'font-size:18px; font-weight:800; color:' + (totalOutstanding > 0 ? '#b91c1c' : '#15803d') + '; margin:4px 0;' },
        fmtSAR(totalOutstanding)
      ),
      el('div', { style: 'font-size:11px; color:#64748b;' },
        totalOutstanding > 0 ? 'مجموع المتبقي للدراسة والباص' : 'تم سداد جميع الرسوم المقررة'
      )
    )
  );

  // 2. Installments Table
  let installmentsSection = null;
  if (installments.length > 0) {
    const tableRows = installments.map(inst => {
      const isPaid = inst.remainingNum <= 0;
      const isPartial = inst.paidNum > 0 && inst.remainingNum > 0;
      const badgeStyle = isPaid
        ? 'background:#dcfce7; color:#15803d;'
        : (isPartial ? 'background:#fef3c7; color:#b45309;' : 'background:#fee2e2; color:#b91c1c;');
      const statusText = isPaid ? 'مسدد' : (isPartial ? 'سداد جزئي' : 'مستحق');

      const selectBtn = !isPaid ? el('button', {
        class: 'button button-outline',
        type: 'button',
        style: 'padding:2px 8px; font-size:11px; font-weight:600; color:#047857; border-color:#10b981; background:#ffffff;',
        onclick: () => selectInstallment(inst)
      }, 'سداد هذا القسط') : el('span', { style: 'color:#94a3b8; font-size:11px;' }, '✓');

      return el('tr', { style: 'border-bottom:1px solid #f1f5f9; font-size:12px;' },
        el('td', { style: 'padding:6px 8px; font-weight:600;' }, `القسط ${inst.seq_no}`),
        el('td', { style: 'padding:6px 8px; color:#64748b;' }, fmtDate(inst.due_date)),
        el('td', { style: 'padding:6px 8px;' }, fmtSAR(inst.amountNum)),
        el('td', { style: 'padding:6px 8px; font-weight:700; color:' + (inst.remainingNum > 0 ? '#c2410c' : '#15803d') + ';' }, fmtSAR(inst.remainingNum)),
        el('td', { style: 'padding:6px 8px;' },
          el('span', { style: `display:inline-block; padding:2px 6px; border-radius:4px; font-size:10px; font-weight:700; ${badgeStyle}` }, statusText)
        ),
        el('td', { style: 'padding:6px 8px; text-align:center;' }, selectBtn)
      );
    });

    installmentsSection = el('div', {
      style: 'margin-bottom:14px; border:1px solid #e2e8f0; border-radius:8px; overflow:hidden; background:#ffffff;'
    },
      el('div', { style: 'background:#f8fafc; padding:6px 12px; font-size:12px; font-weight:700; color:#334155; border-bottom:1px solid #e2e8f0; display:flex; justify-content:space-between; align-items:center;' },
        el('span', {}, '📋 جدول الأقساط الدراسية المقررة'),
        el('span', { style: 'font-size:11px; font-weight:500; color:#64748b;' }, `عدد الأقساط: ${installments.length}`)
      ),
      el('div', { style: 'max-height:130px; overflow-y:auto;' },
        el('table', { style: 'width:100%; text-align:right; border-collapse:collapse;' },
          el('thead', { style: 'background:#f1f5f9; font-size:11px; color:#475569;' },
            el('tr', {},
              el('th', { style: 'padding:6px 8px;' }, 'القسط'),
              el('th', { style: 'padding:6px 8px;' }, 'الاستحقاق'),
              el('th', { style: 'padding:6px 8px;' }, 'المبلغ'),
              el('th', { style: 'padding:6px 8px;' }, 'المتبقي'),
              el('th', { style: 'padding:6px 8px;' }, 'الحالة'),
              el('th', { style: 'padding:6px 8px; text-align:center;' }, 'إجراء')
            )
          ),
          el('tbody', {}, ...tableRows)
        )
      )
    );
  }

  // 3. Form Controls
  const purposeOptions = [
    el('option', { value: 'tuition' }, '🎓 سداد رسوم دراسية / أقساط')
  ];
  if (hasBus) {
    purposeOptions.push(el('option', { value: 'bus' }, `🚌 سداد رسوم الباص والنقل (متبقي: ${fmtSAR(busRemaining)})`));
    purposeOptions.push(el('option', { value: 'combined' }, '🔄 سداد مشترك (رسوم دراسية + باص)'));
  }

  const purposeSelect = el('select', {
    style: 'width:100%; padding:8px 10px; border-radius:8px; border:1px solid #cbd5e1; font-weight:600; background:#fff; font-size:13px;'
  }, ...purposeOptions);

  const installmentSelect = el('select', {
    style: 'width:100%; padding:8px 10px; border-radius:8px; border:1px solid #cbd5e1; font-size:13px; background:#fff;'
  },
    el('option', { value: '' }, 'توزيع تلقائي على أقرب قسط مستحق'),
    ...unpaidInstallments.map(inst =>
      el('option', { value: inst.id }, `القسط ${inst.seq_no} — استحقاق ${fmtDate(inst.due_date)} (متبقي: ${fmtSAR(inst.remainingNum)})`)
    )
  );

  const installmentRow = el('div', { style: 'margin-bottom:10px;' },
    el('label', { style: 'display:block; font-size:12px; font-weight:700; margin-bottom:4px; color:#334155;' }, 'تحديد القسط المستهدف'),
    installmentSelect
  );

  // Single Amount Input (Tuition or Bus)
  const singleAmountLabel = el('label', { style: 'display:block; font-size:12px; font-weight:700; margin-bottom:4px; color:#334155;' }, 'المبلغ المسدد (ر.س) *');
  const singleAmountInput = el('input', {
    type: 'number',
    step: '0.01',
    min: '0.01',
    placeholder: 'مثال: 500',
    required: 'required',
    style: 'width:100%; padding:8px; border-radius:8px; border:1px solid #cbd5e1; font-size:14px; font-weight:600;'
  });
  const singleAmountRow = el('div', { style: 'margin-bottom:10px;' },
    singleAmountLabel,
    singleAmountInput
  );

  // Combined Amount Inputs
  const combinedTuitionInput = el('input', {
    type: 'number',
    step: '0.01',
    min: '0.00',
    placeholder: '0.00',
    style: 'width:100%; padding:8px; border-radius:8px; border:1px solid #cbd5e1; font-size:13px; font-weight:600;'
  });
  const combinedBusInput = el('input', {
    type: 'number',
    step: '0.01',
    min: '0.00',
    placeholder: '0.00',
    style: 'width:100%; padding:8px; border-radius:8px; border:1px solid #cbd5e1; font-size:13px; font-weight:600;'
  });
  const combinedTotalPreview = el('div', {
    style: 'padding:8px 12px; background:#f8fafc; border:1px solid #e2e8f0; border-radius:8px; font-weight:700; color:#047857; font-size:14px;'
  }, '0.00 ر.س');

  const combinedRow = el('div', { style: 'display:none; margin-bottom:10px; background:#f8fafc; padding:10px; border-radius:8px; border:1px solid #e2e8f0;' },
    el('div', { style: 'display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:8px;' },
      el('div', {},
        el('label', { style: 'display:block; font-size:11px; font-weight:700; margin-bottom:4px; color:#334155;' }, 'مبلغ الرسوم الدراسية (ر.س)'),
        combinedTuitionInput
      ),
      el('div', {},
        el('label', { style: 'display:block; font-size:11px; font-weight:700; margin-bottom:4px; color:#334155;' }, 'مبلغ رسوم الباص (ر.س)'),
        combinedBusInput
      )
    ),
    el('div', { style: 'display:flex; justify-content:space-between; align-items:center;' },
      el('span', { style: 'font-size:12px; font-weight:700; color:#475569;' }, 'الإجمالي الكلي للدفعة:'),
      combinedTotalPreview
    )
  );

  const methodSelect = el('select', {
    style: 'width:100%; padding:8px 10px; border-radius:8px; border:1px solid #cbd5e1; font-size:13px; background:#fff;'
  },
    el('option', { value: 'كاش' }, 'كاش'),
    el('option', { value: 'مدى' }, 'مدى / بطاقة'),
    el('option', { value: 'تحويل بنكي' }, 'تحويل بنكي')
  );

  const dateInput = el('input', {
    type: 'date',
    value: todayStr,
    style: 'width:100%; padding:8px; border-radius:8px; border:1px solid #cbd5e1; font-size:13px;'
  });

  const noteInput = el('input', {
    type: 'text',
    value: 'سداد رسوم دراسية',
    style: 'width:100%; padding:8px; border-radius:8px; border:1px solid #cbd5e1; font-size:13px;'
  });

  const saveBtn = el('button', {
    class: 'button',
    type: 'button',
    style: 'width:100%; padding:10px; font-weight:700; margin-top:14px; background:#047857; font-size:14px;'
  }, '💳 تسجيل الدفعة وإصدار سند القبض');

  // Form State Synchronization Logic
  function updateCombinedTotal() {
    const t = parseFloat(combinedTuitionInput.value) || 0;
    const b = parseFloat(combinedBusInput.value) || 0;
    const total = t + b;
    combinedTotalPreview.textContent = fmtSAR(total);
  }

  combinedTuitionInput.addEventListener('input', updateCombinedTotal);
  combinedBusInput.addEventListener('input', updateCombinedTotal);

  function applyPurposeUI() {
    const purpose = purposeSelect.value;
    if (purpose === 'tuition') {
      installmentRow.style.display = unpaidInstallments.length > 0 ? 'block' : 'none';
      singleAmountRow.style.display = 'block';
      combinedRow.style.display = 'none';
      singleAmountLabel.textContent = 'المبلغ المسدد للدراسة (ر.س) *';

      // Default amount and max bounds
      if (installmentSelect.value) {
        const found = unpaidInstallments.find(x => x.id === installmentSelect.value);
        if (found) {
          singleAmountInput.value = found.remainingNum > 0 ? found.remainingNum.toFixed(2) : '';
          singleAmountInput.max = found.remainingNum.toFixed(2);
        }
      } else if (unpaidInstallments.length > 0) {
        singleAmountInput.value = unpaidInstallments[0].remainingNum.toFixed(2);
        singleAmountInput.max = tuitionOutstanding.toFixed(2);
      } else if (tuitionOutstanding > 0) {
        singleAmountInput.value = tuitionOutstanding.toFixed(2);
        singleAmountInput.max = tuitionOutstanding.toFixed(2);
      } else {
        singleAmountInput.value = '';
        singleAmountInput.max = '0.00';
      }
      noteInput.value = 'سداد رسوم دراسية';
    } else if (purpose === 'bus') {
      installmentRow.style.display = 'none';
      singleAmountRow.style.display = 'block';
      combinedRow.style.display = 'none';
      singleAmountLabel.textContent = 'المبلغ المسدد للباص والنقل (ر.س) *';
      singleAmountInput.value = busRemaining > 0 ? busRemaining.toFixed(2) : '';
      singleAmountInput.max = busRemaining.toFixed(2);
      noteInput.value = 'سداد رسوم اشتراك الباص';
    } else if (purpose === 'combined') {
      installmentRow.style.display = unpaidInstallments.length > 0 ? 'block' : 'none';
      singleAmountRow.style.display = 'none';
      combinedRow.style.display = 'block';

      // Prefill combined values and set max bounds
      const defaultTuition = unpaidInstallments.length > 0 ? unpaidInstallments[0].remainingNum : tuitionOutstanding;
      combinedTuitionInput.value = defaultTuition > 0 ? defaultTuition.toFixed(2) : '0.00';
      combinedTuitionInput.max = tuitionOutstanding.toFixed(2);
      combinedBusInput.value = busRemaining > 0 ? busRemaining.toFixed(2) : '0.00';
      combinedBusInput.max = busRemaining.toFixed(2);
      updateCombinedTotal();
      noteInput.value = 'سداد رسوم دراسية واشتراك باص';
    }
  }

  purposeSelect.addEventListener('change', applyPurposeUI);

  installmentSelect.addEventListener('change', () => {
    if (purposeSelect.value === 'tuition' && installmentSelect.value) {
      const found = unpaidInstallments.find(x => x.id === installmentSelect.value);
      if (found) {
        singleAmountInput.value = found.remainingNum.toFixed(2);
        noteInput.value = `سداد القسط ${found.seq_no} — رسوم دراسية`;
      }
    }
  });

  function selectInstallment(inst) {
    purposeSelect.value = 'tuition';
    applyPurposeUI();
    installmentSelect.value = inst.id;
    singleAmountInput.value = inst.remainingNum.toFixed(2);
    noteInput.value = `سداد القسط ${inst.seq_no} — رسوم دراسية`;
    singleAmountInput.focus();
  }

  // Initial trigger
  if (tuitionOutstanding <= 0 && busRemaining > 0 && hasBus) {
    purposeSelect.value = 'bus';
  }
  applyPurposeUI();

  // Content assemble
  const content = el('div', {},
    summaryGrid,
    installmentsSection,
    el('div', { style: 'background:#f8fafc; border:1px solid #e2e8f0; border-radius:8px; padding:12px; margin-bottom:12px;' },
      el('div', { style: 'margin-bottom:10px;' },
        el('label', { style: 'display:block; font-size:12px; font-weight:700; margin-bottom:4px; color:#334155;' }, 'تحديد وجهة السداد'),
        purposeSelect
      ),
      installmentRow,
      singleAmountRow,
      combinedRow,
      el('div', { style: 'display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;' },
        el('div', {},
          el('label', { style: 'display:block; font-size:12px; font-weight:700; margin-bottom:4px; color:#334155;' }, 'طريقة السداد'),
          methodSelect
        ),
        el('div', {},
          el('label', { style: 'display:block; font-size:12px; font-weight:700; margin-bottom:4px; color:#334155;' }, 'تاريخ السداد'),
          dateInput
        )
      ),
      el('div', { style: 'margin-bottom:4px;' },
        el('label', { style: 'display:block; font-size:12px; font-weight:700; margin-bottom:4px; color:#334155;' }, 'البيان / ملاحظات السند'),
        noteInput
      )
    ),
    saveBtn
  );

  container.replaceChildren(content);

  // Submit Handler
  saveBtn.onclick = async () => {
    const purpose = purposeSelect.value;
    let totalVal = 0;
    let busVal = 0;
    let tuitionVal = 0;
    let targetInstallmentId = null;

    if (purpose === 'tuition') {
      totalVal = parseFloat(singleAmountInput.value) || 0;
      busVal = 0;
      tuitionVal = totalVal;
      targetInstallmentId = installmentSelect.value || null;
    } else if (purpose === 'bus') {
      totalVal = parseFloat(singleAmountInput.value) || 0;
      busVal = totalVal;
      tuitionVal = 0;
      targetInstallmentId = null;
    } else if (purpose === 'combined') {
      const t = parseFloat(combinedTuitionInput.value) || 0;
      const b = parseFloat(combinedBusInput.value) || 0;
      totalVal = t + b;
      busVal = b;
      tuitionVal = t;
      targetInstallmentId = installmentSelect.value || null;
    }

    if (totalVal <= 0) {
      toast('يرجى إدخال مبلغ سداد صحيح أكبر من الصفر', true);
      return;
    }

    // Q3 & Q1: Tuition Cap Validation
    if (tuitionVal > 0) {
      if (tuitionOutstanding <= 0) {
        toast('تم سداد كامل الرسوم الدراسية المقررة للطالب مسبقاً', true);
        return;
      }
      if (tuitionVal > (tuitionOutstanding + 0.01)) {
        toast(`المبلغ المسدد للدراسة (${fmtSAR(tuitionVal)}) يتجاوز الرسوم الدراسية المتبقية (${fmtSAR(tuitionOutstanding)})`, true);
        return;
      }
      if (targetInstallmentId) {
        const found = unpaidInstallments.find(x => x.id === targetInstallmentId);
        if (found && tuitionVal > (found.remainingNum + 0.01)) {
          toast(`المبلغ المسدد (${fmtSAR(tuitionVal)}) يتجاوز المتبقي من هذا القسط (${fmtSAR(found.remainingNum)})`, true);
          return;
        }
      }
    }

    // Bus Cap Validation
    if (busVal > 0) {
      if (!hasBus) {
        toast('الطالب غير مشترك في خدمة الباص', true);
        return;
      }
      if (busRemaining <= 0) {
        toast('تم سداد كامل رسوم الباص مسبقاً', true);
        return;
      }
      if (busVal > (busRemaining + 0.01)) {
        toast(`المبلغ المسدد للباص (${fmtSAR(busVal)}) أكبر من المتبقي (${fmtSAR(busRemaining)})`, true);
        return;
      }
    }

    saveBtn.disabled = true;
    saveBtn.textContent = 'جاري تسجيل الدفعة وإصدار السند...';
    const printWindow = window.open('about:blank', '_blank');

    try {
      const payload = {
        student_id: student.id,
        amount: totalVal,
        bus_amount: busVal,
        method: methodSelect.value,
        paid_on: dateInput.value || todayStr,
        note: noteInput.value.trim() || 'سداد رسوم'
      };
      if (targetInstallmentId) {
        payload.installment_id = targetInstallmentId;
      }

      const res = await api.post('payments', payload);
      toast('تم تسجيل الدفعة بنجاح! جاري فتح سند القبض...');
      if (dialog && dialog.close) dialog.close();
      const paymentId = res.id;
      if (paymentId && printWindow) {
        printWindow.location.href = `../print/templates/receipt.html?payment=${encodeURIComponent(paymentId)}`;
      } else if (paymentId) {
        window.open(`../print/templates/receipt.html?payment=${encodeURIComponent(paymentId)}`, '_blank');
      }
      await reload(api);
    } catch (err) {
      if (printWindow) printWindow.close();
      toast(err.message || 'تعذر تسجيل الدفعة وسند القبض', true);
      saveBtn.disabled = false;
      saveBtn.textContent = '💳 تسجيل الدفعة وإصدار سند القبض';
    }
  };
}

async function openBehaviorLogModal(api, student) {
  toast('جاري تحميل السجل السلوكي...');
  let logs = [];
  try {
    const res = await api.fetchAll(`student-behavior-logs?student_id=${student.id}`);
    logs = toList(res);
  } catch (err) {
    logs = [];
  }

  const historyBody = el('div', { style: 'max-height:220px; overflow-y:auto; border:1px solid #e2e8f0; border-radius:8px; padding:8px; background:#f8fafc; margin-bottom:12px;' });

  function renderHistory() {
    if (!logs.length) {
      historyBody.replaceChildren(el('div', { style: 'text-align:center; padding:16px; color:#64748b; font-size:13px;' }, 'لا توجد ملاحظات سلوكية سابقة'));
      return;
    }
    const items = logs.map(l => {
      const isPos = l.log_type === 'positive';
      const isComm = l.log_type === 'communication';
      const badgeColor = isPos ? 'background:#dcfce7; color:#166534;' : (isComm ? 'background:#e0f2fe; color:#0369a1;' : 'background:#fee2e2; color:#991b1b;');
      const typeLabel = isPos ? '🌟 إيجابي (+نقاط)' : (isComm ? '💬 تواصل' : '⚠️ ملاحظة سلبية');
      return el('div', { style: 'padding:8px 10px; border-bottom:1px solid #e2e8f0; background:#fff; border-radius:6px; margin-bottom:6px;' },
        el('div', { style: 'display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;' },
          el('span', { class: 'badge', style: badgeColor }, typeLabel),
          el('span', { style: 'font-size:11px; color:#64748b;' }, String(l.logged_at || l.created_at || '').slice(0, 10))
        ),
        el('p', { style: 'margin:2px 0 0; font-size:13px; color:#334155;' }, l.note || '—')
      );
    });
    historyBody.replaceChildren(...items);
  }

  renderHistory();

  // New Note Form
  const typeSelect = el('select', { name: 'log_type', style: 'padding:8px; border-radius:8px; border:1px solid #cbd5e1; width:100%;' },
    el('option', { value: 'positive' }, '🌟 نقطة إيجابية / تميز (+1)'),
    el('option', { value: 'negative' }, '⚠️ ملاحظة سلوكية / انضباط (-1)'),
    el('option', { value: 'communication' }, '💬 تواصل مباشر مع ولي الأمر')
  );

  const noteInput = el('textarea', { name: 'note', placeholder: 'اكتب تفاصيل الملاحظة السلوكية أو نتيجة التواصل...', required: 'required', style: 'width:100%; min-height:70px; padding:8px; border-radius:8px; border:1px solid #cbd5e1; margin-top:6px;' });
  const saveBtn = el('button', { class: 'button', type: 'button', style: 'width:100%; padding:9px; font-weight:700; margin-top:8px;' }, 'تسجيل الملاحظة في السجل');

  const content = el('div', {},
    el('p', { style: 'font-size:13px; color:#64748b; margin-top:0;' }, `السجل التاريخي للملاحظات والنقاط السلوكية للطالب: <strong>${student.name}</strong>`),
    historyBody,
    el('div', { style: 'border-top:1px solid #e2e8f0; padding-top:10px;' },
      el('label', { style: 'display:block; font-size:12px; font-weight:700; margin-bottom:4px;' }, 'نوع الملاحظة'),
      typeSelect,
      el('label', { style: 'display:block; font-size:12px; font-weight:700; margin-top:8px; margin-bottom:4px;' }, 'نص الملاحظة'),
      noteInput,
      saveBtn
    )
  );

  const dialog = modal(`السجل السلوكي — ${student.name}`, content);

  saveBtn.onclick = async () => {
    const text = noteInput.value.trim();
    if (!text) { toast('يرجى كتابة نص الملاحظة', true); return; }
    saveBtn.disabled = true;
    saveBtn.textContent = 'جاري الحفظ...';
    try {
      const newLog = await api.post('student-behavior-logs', {
        student_id: student.id,
        room_id: student.room_id || null,
        author_id: api.currentUser?.staff_id || api.currentUser?.id,
        log_type: typeSelect.value,
        points: typeSelect.value === 'positive' ? 1 : (typeSelect.value === 'negative' ? -1 : 0),
        note: text
      });
      logs.unshift(newLog);
      renderHistory();
      noteInput.value = '';
      toast('تم تسجيل الملاحظة في السجل السلوكي بنجاح');
    } catch (err) {
      toast(err.message || 'فشل الحفظ', true);
    } finally {
      saveBtn.disabled = false;
      saveBtn.textContent = 'تسجيل الملاحظة في السجل';
    }
  };
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
  const allGroups = getAllGroups();
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

  const allGroups = getAllGroups();
  let groupSelect = select('group_name', [['', 'اختر المجموعة']].concat(allGroups.map(g => [g, g])), value('group_name') || defaultGroup);

  const addGroupInlineBtn = el('button', {
    type: 'button',
    class: 'button button-outline',
    style: 'padding: 2px 8px; font-size: 11px; margin-right: 6px; color: #0284c7; border-color: #bae6fd; font-weight:600;',
    title: 'إضافة مجموعة دراسية جديدة'
  }, '+ مجموعة جديدة');

  addGroupInlineBtn.onclick = () => {
    openAddGroupModal(api, newName => {
      const updated = getAllGroups();
      groupSelect.replaceChildren(
        el('option', { value: '' }, 'اختر المجموعة'),
        ...updated.map(g => el('option', { value: g }, g))
      );
      groupSelect.value = newName;
    });
  };

  const groupField = el('label', {},
    el('div', { style: 'display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;' },
      el('span', { style: 'font-weight:600;' }, 'المجموعة الدراسية *'),
      addGroupInlineBtn
    ),
    groupSelect
  );

  // --- 📷 صورة الطالب: تُرفع إلى مخزن خاص على الخادم بعد حفظ الطالب (لا تُحفظ داخل بيانات الطالب) ---
  const photoState = { blob: null, remove: false, objectUrl: null };
  const fileInput = el('input', { type: 'file', accept: 'image/png, image/jpeg, image/webp', style: 'display:none;' });

  const previewImg = el('img', {
    alt: 'صورة الطالب',
    style: 'width:60px; height:60px; border-radius:10px; object-fit:cover; border:2px solid #0284c7; box-shadow:0 2px 6px rgba(0,0,0,0.08); display:none;'
  });

  const previewPlaceholder = el('div', {
    style: 'width:60px; height:60px; border-radius:10px; background:#f1f5f9; border:2px dashed #cbd5e1; display:flex; align-items:center; justify-content:center; font-size:26px; color:#94a3b8;'
  }, '📷');

  const uploadBtn = el('button', {
    type: 'button',
    class: 'button button-outline',
    style: 'display:inline-flex; align-items:center; gap:6px; padding:7px 12px; font-size:12px; font-weight:600; color:#0284c7; border-color:#38bdf8; background:#f0f9ff;'
  }, '📁 إدراج صورة من الجهاز (كمبيوتر / لابتوب)');

  const clearPhotoBtn = el('button', {
    type: 'button',
    class: 'button button-outline',
    style: 'padding:4px 8px; font-size:11px; color:#ef4444; border-color:#fecaca; background:#fff; display:none;'
  }, 'حذف الصورة');

  const showPreview = url => {
    if (photoState.objectUrl) URL.revokeObjectURL(photoState.objectUrl);
    photoState.objectUrl = url || null;
    previewImg.src = url || '';
    previewImg.style.display = url ? 'block' : 'none';
    previewPlaceholder.style.display = url ? 'none' : 'flex';
    clearPhotoBtn.style.display = url ? 'inline-block' : 'none';
  };

  if (editing && student.photo_url) {
    api.fetchBlobUrl(`students/${student.id}/photo`).then(showPreview).catch(() => showPreview(null));
  }

  uploadBtn.onclick = () => fileInput.click();

  clearPhotoBtn.onclick = () => {
    photoState.blob = null;
    photoState.remove = true;
    fileInput.value = '';
    showPreview(null);
    toast('سيتم حذف صورة الطالب عند الحفظ');
  };

  fileInput.onchange = () => {
    const file = fileInput.files && fileInput.files[0];
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      toast('يرجى اختيار ملف صورة صالح (JPEG, PNG, WebP)', true);
      return;
    }
    const sourceUrl = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(sourceUrl);
      const maxDim = 600;
      let w = img.width;
      let h = img.height;
      if (w > maxDim || h > maxDim) {
        if (w > h) { h = Math.round((h * maxDim) / w); w = maxDim; } else { w = Math.round((w * maxDim) / h); h = maxDim; }
      }
      const canvas = document.createElement('canvas');
      canvas.width = w;
      canvas.height = h;
      canvas.getContext('2d').drawImage(img, 0, 0, w, h);
      canvas.toBlob(blob => {
        if (!blob) { toast('تعذر معالجة ملف الصورة', true); return; }
        photoState.blob = blob;
        photoState.remove = false;
        showPreview(URL.createObjectURL(blob));
        toast(`تم تجهيز صورة الطالب: ${file.name}`);
      }, 'image/jpeg', 0.85);
    };
    img.onerror = () => {
      URL.revokeObjectURL(sourceUrl);
      toast('تعذر معالجة ملف الصورة', true);
    };
    img.src = sourceUrl;
  };

  const photoPickerField = el('div', { style: 'grid-column: 1 / -1; background:#f8fafc; border:1px solid #e2e8f0; border-radius:10px; padding:12px; margin-top:4px;' },
    el('label', { style: 'display:block; font-weight:600; font-size:13px; margin-bottom:8px; color:#334155;' }, '📷 صورة الطالب الشخصية (من الكمبيوتر أو اللابتوب)'),
    el('div', { style: 'display:flex; align-items:center; gap:14px; flex-wrap:wrap;' },
      previewPlaceholder,
      previewImg,
      el('div', { style: 'display:flex; flex-direction:column; gap:6px;' },
        el('div', { style: 'display:flex; gap:8px; align-items:center; flex-wrap:wrap;' },
          uploadBtn,
          clearPhotoBtn
        ),
        el('span', { class: 'muted', style: 'font-size:11px;' }, 'يدعم JPG و PNG و WebP، وتُصغَّر تلقائيًا وتظهر في البطاقة والتقرير والشهادة')
      )
    ),
    fileInput
  );

  const feeInputAttrs = { name: 'registration_fee', type: 'number', step: '0.01', min: '0', placeholder: 'مثال: 500', value: value('registration_fee') };
  const paidInputAttrs = { name: 'initial_payment_amount', type: 'number', step: '0.01', min: '0', placeholder: 'مثال: 200', value: value('initial_payment_amount') };
  if (editing) {
    feeInputAttrs.readonly = 'readonly';
    feeInputAttrs.style = 'background: #f1f5f9; color: #64748b; cursor: not-allowed; border-color: #cbd5e1;';
    feeInputAttrs.title = 'رسوم التسجيل محددة في الخطة المالية';
    paidInputAttrs.readonly = 'readonly';
    paidInputAttrs.style = 'background: #f1f5f9; color: #64748b; cursor: not-allowed; border-color: #cbd5e1;';
    paidInputAttrs.title = 'الدفعة الأولى محددة في السند الأصلي';
  }
  const feeInput = el('input', feeInputAttrs);
  const paidInput = el('input', paidInputAttrs);
  const methodSelect = select('payment_method', [
    ['كاش', 'كاش نقدي'],
    ['مدى', 'مدى / شبكة'],
    ['تحويل بنكي', 'تحويل بنكي'],
    ['Apple Pay', 'Apple Pay'],
    ['تابي', 'تابي (Tabby)'],
    ['تمارا', 'تمارا (Tamara)']
  ], 'كاش');
  if (editing) methodSelect.disabled = true;

  // اشتراك الباص المدرسي
  const hasBusSelect = select('has_bus_subscription', YES_NO, boolValue('has_bus_subscription'));
  const busFeeAttrs = { name: 'bus_fee', type: 'number', step: '0.01', min: '0', placeholder: 'مثال: 500', value: value('bus_fee') };
  const busPaidAttrs = { name: 'bus_paid', type: 'number', step: '0.01', min: '0', placeholder: 'مثال: 200', value: value('bus_paid') };
  const busFeeInput = el('input', busFeeAttrs);
  const busPaidInput = el('input', busPaidAttrs);
  const busRemainingPreview = el('span', { id: 'bus-remaining-preview', style: 'font-weight: 700; color: #0f766e;' }, '0.00 ر.س');

  const updateBusRemaining = () => {
    const fee = parseFloat(busFeeInput.value) || 0;
    const paid = parseFloat(busPaidInput.value) || 0;
    const remaining = Math.max(0, fee - paid);
    busRemainingPreview.textContent = `${remaining.toFixed(2)} ر.س`;
  };

  busFeeInput.addEventListener('input', updateBusRemaining);
  busPaidInput.addEventListener('input', updateBusRemaining);
  updateBusRemaining();

  const busFieldsContainer = el('div', {
    style: `grid-column: 1 / -1; display: ${boolValue('has_bus_subscription') === 'true' ? 'grid' : 'none'}; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 10px; background: #f0fdfa; border: 1px solid #99f6e4; border-radius: 8px; padding: 12px; margin-top: 4px;`
  },
    el('label', {}, el('span', { style: 'font-weight:600;' }, 'رسوم اشتراك الباص (ر.س)'), busFeeInput),
    el('label', {}, el('span', { style: 'font-weight:600;' }, 'الدفعة المسددة للباص (ر.س)'), busPaidInput),
    el('div', { style: 'display: flex; flex-direction: column; justify-content: center; padding: 4px;' },
      el('span', { style: 'font-size: 12px; color: #64748b; font-weight: 600;' }, 'المتبقي من اشتراك الباص:'),
      busRemainingPreview
    )
  );

  hasBusSelect.addEventListener('change', () => {
    const active = hasBusSelect.value === 'true';
    busFieldsContainer.style.display = active ? 'grid' : 'none';
    if (!active) {
      busFeeInput.value = '';
      busPaidInput.value = '';
      updateBusRemaining();
    }
  });

  // ملف الحساسية للطفل
  const hasAllergiesSelect = select('has_allergies', YES_NO, boolValue('has_allergies'));
  const allergyNotesTextarea = el('textarea', { name: 'allergy_notes', placeholder: 'يرجى توضيح نوع الحساسية (غذائية أو دوائية)...' }, value('allergy_notes'));
  const allergyNotesField = el('label', { style: `grid-column: 1 / -1; display: ${boolValue('has_allergies') === 'true' ? 'grid' : 'none'};` },
    el('span', { style: 'font-weight:600;' }, 'تفاصيل الحساسية (غذائية أو دوائية) *'),
    allergyNotesTextarea
  );
  hasAllergiesSelect.addEventListener('change', () => {
    allergyNotesField.style.display = hasAllergiesSelect.value === 'true' ? 'grid' : 'none';
  });

  // خطة أقساط الرسوم (تظهر فقط عند الإنشاء مع رسوم متبقية غير مسددة)
  let countInput = null;
  let dateInput = null;
  let intervalSelect = null;
  let datePickerWrap = null;
  const planInputs = [];
  if (!editing) {
    countInput = el('input', { name: 'installments_count', type: 'number', min: '1', step: '1', value: '1' });
    datePickerWrap = createDatePicker({
      name: 'next_due_date',
      label: 'تاريخ الدفعة القادمة',
      minDate: isoTomorrow(),
      minYear: new Date().getFullYear(),
      maxYear: new Date().getFullYear() + 2,
      showAge: false
    });
    dateInput = datePickerWrap.querySelector('input[name="next_due_date"]');
    intervalSelect = select('interval_days', INTERVAL_OPTIONS, 30);
    planInputs.push(
      el('label', {}, el('span', {}, 'عدد الدفعات المتبقية'), countInput),
      datePickerWrap,
      el('label', {}, el('span', {}, 'الفاصل بين الدفعات'), intervalSelect)
    );
  }
  const planContainer = el('div', { style: 'grid-column: 1 / -1; display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px;' }, ...planInputs);
  const planSummary = el('div', { style: 'grid-column: 1 / -1; font-size: 13px; font-weight: 600; display: none;' });

  const fields = [
    // --- 1. البيانات الأساسية والأكاديمية ---
    el('div', { class: 'form-section-title', style: 'grid-column: 1 / -1; font-weight: bold; margin: 4px 0 6px; padding-bottom: 4px; border-bottom: 1.5px solid var(--border, #e2e8f0); color: var(--primary, #0284c7); display: flex; align-items: center; gap: 6px;' }, '👤 البيانات الأساسية والأكاديمية'),

    el('label', {}, el('span', { style: 'font-weight:600;' }, 'اسم الطالب *'), el('input', { name: 'name', type: 'text', required: 'required', placeholder: 'الاسم الثلاثي أو الرباعي', value: value('name') })),

    el('label', { style: 'background: rgba(14, 165, 233, 0.08); padding: 6px 10px; border-radius: 8px; border: 1.5px solid rgba(14, 165, 233, 0.3);' }, 
      el('span', { style: 'font-weight: 700; color: #0369a1; display: flex; align-items: center; gap: 4px;' }, '⚥ تحديد الجنس (بنين / بنات) *'), 
      select('gender', [['بنين', '👦 بنين (أولاد)'], ['بنات', '👧 بنات']], value('gender') || (editing ? (isGirl(student || {}) ? 'بنات' : 'بنين') : defaultGender))
    ),

    groupField,
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
    photoPickerField,

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
    el('label', {}, el('span', {}, 'يشكو الطفل من حساسية؟'), hasAllergiesSelect),
    allergyNotesField,
    el('label', {}, el('span', {}, 'ملاحظات عن الطالب'), el('textarea', { name: 'child_notes' }, value('child_notes'))),
    el('label', {}, el('span', {}, 'ملاحظات تعليمية'), el('textarea', { name: 'education_notes' }, value('education_notes'))),

    // --- 4. الرسوم والتسجيل المالي الفوري (أتمتة الدفعة الأولى) ---
    el('div', { class: 'form-section-title', style: 'grid-column: 1 / -1; font-weight: bold; margin: 12px 0 6px; padding-bottom: 4px; border-bottom: 1.5px solid var(--border, #e2e8f0); color: #059669; display: flex; align-items: center; gap: 6px;' }, '💰 الرسوم والتسجيل المالي الفوري'),

    el('label', {}, el('span', {}, 'رسوم التسجيل المقررة (ر.س)'), feeInput),
    el('label', {}, el('span', {}, 'الدفعة الأولى المسددة الآن (ر.س)'), paidInput),
    el('label', {}, el('span', {}, 'طريقة سداد الدفعة الأولى'), methodSelect),

    el('label', {}, el('span', {}, 'الاشتراك في الباص'), hasBusSelect),
    busFieldsContainer,

    planContainer,
    planSummary
  ];

  const submit = el('button', { class: 'button', type: 'button', style: 'padding: 10px 24px; font-weight: 700;' }, editing ? 'حفظ التعديلات' : 'إضافة طالب');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');

  const deleteStudentBtn = editing ? el('button', {
    class: 'button button-outline',
    type: 'button',
    style: 'color: #dc2626; border-color: #fca5a5; background: #fef2f2; margin-inline-start: auto; padding: 10px 16px; font-weight: 600;',
    title: 'حذف هذا الطالب نهائياً من النظام'
  }, '🗑️ حذف الطالب') : null;

  const actionsBar = el('div', { class: 'form-actions', style: 'grid-column: 1 / -1; display: flex; gap: 10px; margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--border, #e2e8f0); align-items: center;' },
    submit,
    cancel,
    ...(deleteStudentBtn ? [deleteStudentBtn] : [])
  );
  const form = el('form', { class: 'form-grid', novalidate: 'novalidate' }, ...fields, actionsBar);
  const dialog = modal(editing ? 'تعديل بيانات الطالب' : 'إضافة طالب جديد', form);
  cancel.addEventListener('click', dialog.close);
  cancel.addEventListener('click', () => { if (photoState.objectUrl) URL.revokeObjectURL(photoState.objectUrl); });

  if (deleteStudentBtn) {
    deleteStudentBtn.addEventListener('click', () => {
      openDeleteStudentModal(api, student, () => {
        dialog.close();
        reload(api);
      });
    });
  }

  const updatePlanSummary = () => {
    if (editing) return;
    const fee = Number(feeInput.value);
    const paid = paidInput.value === '' ? 0 : Number(paidInput.value);
    if (!fee || fee <= 0) {
      planContainer.style.display = 'none';
      planSummary.style.display = 'none';
      submit.disabled = false;
      return;
    }
    planSummary.style.display = '';
    if (paid > fee) {
      planContainer.style.display = 'none';
      planSummary.textContent = '⚠️ المبلغ المسدد أكبر من رسوم التسجيل المقررة';
      planSummary.style.color = '#b91c1c';
      submit.disabled = true;
      return;
    }
    submit.disabled = false;
    if (paid === fee) {
      planContainer.style.display = 'none';
      planSummary.textContent = '✅ سيتم تسجيل الرسوم مسددة بالكامل';
      planSummary.style.color = '#166534';
      return;
    }
    planContainer.style.display = '';
    const count = Number(countInput && countInput.value) || 1;
    const each = ((fee - paid) / count).toFixed(2);
    planSummary.style.color = '#1e40af';
    planSummary.textContent = `📅 المتبقي ${(fee - paid).toFixed(2)} ر.س على ${count} دفعات، قيمة الدفعة تقريبًا ${each} ر.س، أولها بتاريخ ⁦${(dateInput && dateInput.value) || '—'}⁩`; // LTR isolate keeps YYYY-MM-DD in order
  };

  if (!editing) {
    [feeInput, paidInput, countInput, dateInput].forEach(node => {
      node.addEventListener('input', updatePlanSummary);
      node.addEventListener('change', updatePlanSummary);
    });
    intervalSelect.addEventListener('change', updatePlanSummary);
  }
  updatePlanSummary();

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

    payload.has_allergies = payload.has_allergies === 'true';
    if (payload.has_allergies) {
      payload.allergy_notes = payload.allergy_notes ? payload.allergy_notes.trim() : '';
    } else {
      payload.allergy_notes = null;
    }

    payload.has_bus_subscription = payload.has_bus_subscription === 'true';
    if (payload.has_bus_subscription) {
      payload.bus_fee = payload.bus_fee === '' ? 0 : Number(payload.bus_fee);
      payload.bus_paid = payload.bus_paid === '' ? 0 : Number(payload.bus_paid);
    } else {
      payload.bus_fee = 0;
      payload.bus_paid = 0;
    }

    // PostgreSQL nullable & check constraint guarantees
    ['birth_date', 'group_name', 'guardian_relation', 'national_id', 'father_phone', 'mother_phone', 'pickup_phone', 'registration_fee', 'initial_payment_amount'].forEach(k => {
      if (payload[k] === '') payload[k] = null;
    });

    // حقول خطة الأقساط تُرسل فقط عند الإنشاء مع رسوم متبقية غير مسددة
    const planFee = Number(feeInput.value);
    const planPaid = paidInput.value === '' ? 0 : Number(paidInput.value);
    if (!editing && planFee > 0 && planPaid < planFee) {
      payload.installments_count = Number(payload.installments_count) || 1;
      payload.interval_days = Number(payload.interval_days) || 30;
    } else {
      delete payload.installments_count;
      delete payload.next_due_date;
      delete payload.interval_days;
    }

    submit.disabled = true;
    submit.textContent = 'جاري الحفظ...';

    try {
      let createdStudent = null;
      if (editing) {
        await api.patch(`students/${student.id}`, payload);
      } else {
        createdStudent = await api.post('students', payload);
      }
      const savedId = editing ? student.id : createdStudent?.id;
      let photoFailed = false;
      if (savedId && (photoState.blob || (editing && photoState.remove))) {
        try {
          if (photoState.blob) await api.upload(`students/${savedId}/photo`, photoState.blob);
          else await api.del(`students/${savedId}/photo`);
        } catch (photoError) {
          console.error('Student photo failed:', photoError);
          photoFailed = true;
        }
      }
      if (photoState.objectUrl) URL.revokeObjectURL(photoState.objectUrl);
      dialog.close();
      if (photoFailed) toast('تم حفظ الطالب، لكن تعذر رفع الصورة. أعد المحاولة من نافذة التعديل', true);
      else toast(editing ? 'تم تحديث بيانات الطالب بنجاح' : 'تمت إضافة الطالب بنجاح');
      await reload(api);

      // Automated redirect to Receipt Print Page if an initial payment was recorded
      if (!editing && createdStudent) {
        const paymentId = createdStudent.initial_payment?.id || createdStudent.initial_receipt?.payment_id;
        const initialAmount = parseFloat(payload.initial_payment_amount || payload.registration_fee || '0');
        const busAmount = parseFloat(payload.bus_paid || '0');
        if (paymentId && (initialAmount > 0 || busAmount > 0)) {
          toast('تم تسجيل الطالب وسند القبض، جاري التحويل لطباعة السند...');
          setTimeout(() => {
            window.location.href = `../print/templates/receipt.html?payment=${encodeURIComponent(paymentId)}`;
          }, 600);
        }
      }
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
