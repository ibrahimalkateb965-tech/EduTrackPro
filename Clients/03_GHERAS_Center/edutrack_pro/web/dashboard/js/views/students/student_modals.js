import { el, toast, modal } from '../../ui.js';

export const DEFAULT_GROUPS = ['الصباح', 'المساء', 'الإنجليزي', 'القدرات'];

export const MALE_NAMES = new Set([
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

export const FEMALE_NAMES = new Set([
  'مريم', 'فاطمة', 'فاطمه', 'عائشة', 'عائشه', 'نورة', 'نوره', 'نور', 'سارة', 'ساره',
  'ريم', 'هدى', 'أمل', 'منى', 'شهد', 'رهف', 'جنى', 'خلود', 'ليلى', 'زينب', 'لمى',
  'أسماء', 'ريناد', 'دانة', 'دانه', 'تسنيم', 'عبير', 'روان', 'شروق', 'حنين', 'يارا',
  'ريما', 'هند', 'بشاير', 'أروى', 'غيداء', 'أفنان', 'جود', 'بيان', 'خلود', 'وسن'
]);

export function isGirl(student) {
  const g = String(student?.gender || '').trim();
  if (g === 'بنات' || g === 'female' || g === 'أنثى') return true;
  if (g === 'بنين' || g === 'male' || g === 'ذكر' || g === 'أولاد') return false;

  const text = `${student?.child_notes || ''} ${student?.education_notes || ''} ${student?.room_name || ''}`;
  if (text.includes('بنات') || text.includes('أنثى')) return true;
  if (text.includes('بنين') || text.includes('أولاد') || text.includes('ذكر')) return false;

  const firstName = (student?.name || '').trim().split(/\s+/)[0] || '';
  if (MALE_NAMES.has(firstName)) return false;
  if (FEMALE_NAMES.has(firstName)) return true;

  if (firstName.endsWith('ة')) return true;

  return false;
}

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

export function getAllGroups(students = []) {
  const fromStudents = (students || []).map(s => s.group_name).filter(Boolean);
  return Array.from(new Set([...DEFAULT_GROUPS, ...getCustomGroups(), ...fromStudents]));
}

export function openAddGroupModal(api, onAdded) {
  const nameInput = el('input', {
    type: 'text',
    placeholder: 'مثال: مسار الموهوبين، حفظ المتون...',
    required: 'required',
    style: 'width:100%; padding:8px; border-radius:8px; border:1px solid #cbd5e1;'
  });
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
      }
    } catch (err) {
      toast(err.message || 'تعذر حذف الطالب', true);
      confirmBtn.disabled = false;
      confirmBtn.textContent = '🗑️ تأكيد الحذف';
    }
  };
}

export async function openBehaviorLogModal(api, student) {
  toast('جاري تحميل السجل السلوكي...');
  let logs = [];
  try {
    const res = await api.fetchAll(`student-behavior-logs?student_id=${student.id}`);
    logs = Array.isArray(res) ? res : (Array.isArray(res?.items) ? res.items : []);
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

  modal(`السجل السلوكي — ${student.name}`, content);

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
