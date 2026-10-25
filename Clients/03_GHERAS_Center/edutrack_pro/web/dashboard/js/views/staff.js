import { el, toast, modal, fmtSAR, fmtDate } from '../ui.js';
import { openCreateTeacherModal } from '../account_modals.js';

const STATUSES = { active: 'نشط', on_leave: 'إجازة', terminated: 'منتهي' };
const ROLES = [['manager', 'مدير'], ['supervisor', 'مشرف'], ['teacher', 'معلم']];
const PERMISSIONS = [
  ['attendance', 'الحضور'],
  ['daily_evaluation', 'التقييم اليومي'],
  ['monthly_evaluation', 'التقييم الشهري'],
  ['students', 'الطلاب'],
  ['finance', 'المالية (خاصة بالمدير فقط — غير متاحة للمشرفة)']
];

let staff = [];
let users = [];
let rooms = [];
let absences = [];
let allTeacherEvaluations = [];
let activeTab = 'all';
let tbody = null;
let tabsContainer = null;

function checkHasFinance(api) {
  return !api.currentUser || api.currentUser.role === 'manager';
}

function checkIsManager(api) {
  return !api.currentUser || api.currentUser.role === 'manager';
}

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

function statusBadge(member) {
  return el('span', { class: `badge badge-${member.status || 'unknown'}` }, STATUSES[member.status] || member.status || '—');
}

function absenceStatusBadge(status) {
  if (status === 'غائب') return el('span', { class: 'badge red' }, 'غائب');
  if (status === 'متأخر') return el('span', { class: 'badge', style: 'background:#fef3c7; color:#92400e;' }, 'متأخر');
  if (status === 'مستأذن') return el('span', { class: 'badge', style: 'background:#e0f2fe; color:#0369a1;' }, 'مستأذن');
  return el('span', { class: 'badge' }, status || 'حاضر');
}

export function openDeleteStaffModal(api, member, onDeleted) {
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
        el('div', { style: 'font-weight: 700; font-size: 16px; color: #1e293b;' }, `حذف الموظف: ${member.name || '—'}`),
        el('div', { style: 'font-size: 13px; color: #64748b; margin-top: 2px;' }, member.role_title ? `المسمى الوظيفي: ${member.role_title}` : '')
      )
    ),
    el('p', { style: 'margin: 0; font-size: 14px; line-height: 1.6; color: #334155; background: #fef2f2; border: 1px solid #fee2e2; border-radius: 8px; padding: 12px 14px;' },
      `هل أنت متأكد من رغبتك في حذف الموظف "${member.name || ''}" نهائياً من النظام؟ سيتم حذف بياناته وإلغاء تفعيل حساب الدخول المرتبط به.`
    ),
    el('div', { class: 'form-actions', style: 'display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px;' },
      cancelBtn,
      confirmBtn
    )
  );

  const modalForm = el('form', {}, warningContent);
  const { close } = modal('تأكيد حذف الموظف', modalForm);
  cancelBtn.onclick = () => close();

  modalForm.onsubmit = async ev => {
    ev.preventDefault();
    confirmBtn.disabled = true;
    confirmBtn.textContent = 'جاري الحذف...';
    try {
      await api.del(`staff/${member.id}`);
      toast('تم حذف الموظف بنجاح');
      close();
      if (typeof onDeleted === 'function') {
        onDeleted();
      } else {
        await reload(api);
      }
    } catch (err) {
      toast(err.message || 'تعذر حذف الموظف', true);
      confirmBtn.disabled = false;
      confirmBtn.textContent = '🗑️ تأكيد الحذف';
    }
  };
}

function staffRow(member, api) {
  const hasFinance = checkHasFinance(api);
  const isManager = checkIsManager(api);
  const edit = el('button', { class: 'button button-outline', type: 'button' }, 'تعديل');
  edit.addEventListener('click', () => openStaffForm(api, member));
  const actions = [edit];
  
  const memberUser = users.find(u => u.staff_id === member.id);
  if (isManager) {
    if (memberUser) {
      const accountInfo = el('button', { class: 'button button-outline', type: 'button', style: 'color:#15803d; border-color:#86efac;' }, 'بيانات الدخول');
      accountInfo.addEventListener('click', () => showUserCredentials(api, member, memberUser));
      actions.push(' ', accountInfo);
    } else {
      const isTeacher = (member.role_title || '').includes('معلم') || (member.role_title || '').includes('تحفيظ') || (member.role_title || '').includes('تعليم');
      const account = el('button', {
        class: 'button button-outline',
        type: 'button',
        style: isTeacher ? 'color:#1d4ed8; border-color:#93c5fd;' : ''
      }, isTeacher ? '+ حساب معلم' : '+ حساب دخول');
      account.addEventListener('click', () => {
        if (isTeacher) {
          openCreateTeacherModal(api, () => reload(api), member.id);
        } else {
          openUserForm(api, member);
        }
      });
      actions.push(' ', account);
    }
  }
  if (hasFinance) {
    const payroll = el('button', { class: 'button button-outline', type: 'button' }, 'راتب');
    payroll.addEventListener('click', () => openPayrollForm(api, member));
    actions.push(' ', payroll);
  }

  const isTeacher = (member.role_title || '').includes('معلم') || (member.role_title || '').includes('تحفيظ') || (member.role_title || '').includes('تعليم');
  if (isTeacher || isManager || api.currentUser?.role === 'supervisor') {
    const evalBtn = el('button', {
      class: 'button button-outline',
      type: 'button',
      style: 'color:#7c3aed; border-color:#c4b5fd; background:#f5f3ff;',
      title: 'تقييم أداء المعلم وبطاقة مؤشرات الأداء (KPIs)'
    }, '📊 تقييم الأداء');
    evalBtn.addEventListener('click', () => openTeacherEvaluationModal(api, member));
    actions.push(' ', evalBtn);
  }

  if (isManager) {
    const deleteBtn = el('button', {
      class: 'button button-outline',
      type: 'button',
      style: 'color: #dc2626; border-color: #fca5a5; background: #fff5f5;',
      title: 'حذف الموظف'
    }, '🗑️ حذف');
    deleteBtn.addEventListener('click', () => openDeleteStaffModal(api, member));
    actions.push(' ', deleteBtn);
  }

  const salaryText = hasFinance && member.base_salary !== null && member.base_salary !== undefined
    ? fmtSAR(member.base_salary)
    : '—';

  const userBadge = memberUser 
    ? el('span', { class: 'badge', style: 'background:#dcfce7; color:#166534; margin-right:4px; font-size:11px;' }, 'حساب نشط')
    : null;

  return el('tr', {},
    el('td', {}, member.name || '—'),
    el('td', {}, member.role_title || '—'),
    el('td', {}, member.phone || '—'),
    el('td', {}, salaryText),
    el('td', {}, statusBadge(member), userBadge ? ' ' : '', userBadge || ''),
    el('td', {}, ...actions)
  );
}

function absenceRow(rec) {
  const member = staff.find(s => s.id === rec.staff_id) || {};
  return el('tr', {},
    el('td', {}, member.name || rec.staff_name || '—'),
    el('td', {}, member.role_title || '—'),
    el('td', {}, rec.date || '—'),
    el('td', {}, absenceStatusBadge(rec.status)),
    el('td', {}, rec.note || '—')
  );
}

function kpiRow(member, api) {
  const evals = allTeacherEvaluations.filter(e => e.staff_id === member.id || e.teacher_id === member.id);
  const latest = evals.length ? evals[0] : null;
  const roomName = member.room_name || rooms.find(r => r.id === member.room_id)?.name || '—';

  const scoreBadge = (score) => {
    if (score === undefined || score === null) return el('span', { class: 'muted' }, '—');
    const num = Number(score);
    const color = num >= 9 ? '#166534' : (num >= 8 ? '#0369a1' : (num >= 7 ? '#854d0e' : '#991b1b'));
    const bg = num >= 9 ? '#dcfce7' : (num >= 8 ? '#e0f2fe' : (num >= 7 ? '#fef9c3' : '#fee2e2'));
    return el('span', { class: 'badge', style: `background:${bg}; color:${color}; font-weight:600; font-family:Consolas,monospace; font-size:12px;` }, `${num.toFixed(1)}/10`);
  };

  const totalScoreBadge = (score, rating) => {
    if (!score) return el('span', { class: 'muted' }, 'لم يُقيَّم بعد');
    const num = Number(score);
    const ratingColor = rating === 'ممتاز' ? 'background:#dcfce7; color:#166534;' : (rating === 'جيد جداً' ? 'background:#e0f2fe; color:#0369a1;' : (rating === 'جيد' ? 'background:#fef3c7; color:#92400e;' : 'background:#fee2e2; color:#991b1b;'));
    return el('div', { style: 'display:flex; align-items:center; gap:6px;' },
      el('span', { class: 'badge', style: ratingColor }, rating || 'تقييم'),
      el('strong', { style: 'font-family:Consolas,monospace; font-size:13px;' }, `${num.toFixed(1)}/10`)
    );
  };

  const evalBtn = el('button', {
    class: 'button button-outline',
    type: 'button',
    style: 'color:#7c3aed; border-color:#c4b5fd; background:#f5f3ff;'
  }, '📊 تقييم / تعديل');
  evalBtn.addEventListener('click', () => openTeacherEvaluationModal(api, member));

  return el('tr', {},
    el('td', {}, el('strong', {}, member.name || '—')),
    el('td', {}, roomName),
    el('td', {}, scoreBadge(latest?.interaction_score)),
    el('td', {}, scoreBadge(latest?.guardian_comm_score)),
    el('td', {}, scoreBadge(latest?.ethics_score || latest?.discipline_score)),
    el('td', {}, scoreBadge(latest?.curriculum_score)),
    el('td', {}, totalScoreBadge(latest?.total_score, latest?.rating)),
    el('td', { style: 'max-width:200px; font-size:12px;' }, latest?.notes || latest?.supervisor_notes || el('span', { class: 'muted' }, 'لا توجد ملاحظات')),
    el('td', {}, evalBtn)
  );
}

function paint(api) {
  if (activeTab === 'absences') {
    const rows = absences.map(absenceRow);
    tbody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '5', class: 'muted', style: 'text-align:center;' }, 'لا توجد سجلات غياب للموظفين بعد'))]));
    return;
  }

  if (activeTab === 'kpi_dashboard') {
    const teachersList = staff.filter(member => {
      const title = (member.role_title || '').toLowerCase();
      return title.includes('معلم') || title.includes('تحفيظ') || title.includes('تعليم') || member.role_title === 'teacher';
    });
    const rows = teachersList.map(member => kpiRow(member, api));
    tbody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '9', class: 'muted', style: 'text-align:center;' }, 'لا يوجد معلمون مسجلون لعرض مؤشرات أدائهم'))]));
    return;
  }

  const list = staff.filter(member => {
    const title = (member.role_title || '').toLowerCase();
    if (activeTab === 'teachers_supervisors') {
      return title.includes('معلم') || title.includes('مشرف') || title.includes('إشراف') || title.includes('تحفيظ') || title.includes('تعليم');
    }
    if (activeTab === 'teachers') {
      return title.includes('معلم') || title.includes('تحفيظ') || title.includes('تعليم');
    }
    if (activeTab === 'supervisors') {
      return title.includes('مشرف') || title.includes('إشراف');
    }
    return true;
  });

  const rows = list.map(member => staffRow(member, api));
  tbody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '6', class: 'muted', style: 'text-align:center;' }, 'لا يوجد موظفون في هذا القسم'))]));
}

function generateSecureTempPassword(length = 10) {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%';
  const limit = 256 - (256 % chars.length);
  let result = '';
  while (result.length < length) {
    const buf = new Uint8Array(length - result.length + 4);
    window.crypto.getRandomValues(buf);
    for (let i = 0; i < buf.length && result.length < length; i++) {
      if (buf[i] < limit) {
        result += chars[buf[i] % chars.length];
      }
    }
  }
  return result;
}

async function safeCopyToClipboard(text) {
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text);
      toast('تم نسخ بيانات الدخول إلى الحافظة');
      return;
    }
  } catch (_) {}
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.select();
  try {
    document.execCommand('copy');
    toast('تم نسخ بيانات الدخول إلى الحافظة');
  } catch (e) {
    toast('تعذر النسخ التلقائي — يرجى نسخ النص يدوياً', true);
  } finally {
    document.body.removeChild(textarea);
  }
}

async function reload(api) {
  try {
    staff = toList(await api.fetchAll('staff'));
    if (checkIsManager(api)) {
      try {
        users = toList(await api.fetchAll('users'));
      } catch (err) {
        console.error('Failed to load users list:', err);
        toast('تنبيه: تعذر تحديث حالة حسابات المستخدمين', true);
      }
    }
  } catch (error) {
    toast(error.message, true);
  }
  if (activeTab === 'absences') {
    try {
      absences = toList(await api.fetchAll('staff-attendance'));
    } catch (error) {
      toast('تعذر تحميل سجلات غياب الموظفين', true);
    }
  } else if (activeTab === 'kpi_dashboard') {
    try {
      allTeacherEvaluations = toList(await api.fetchAll('teacher-evaluations'));
    } catch (error) {
      allTeacherEvaluations = [];
    }
  }
  paint(api);
}

function showUserCredentials(api, member, user) {
  const content = el('div', { style: 'display:flex; flex-direction:column; gap:12px;' },
    el('p', {}, `حساب الدخول المرتبط بالموظف: `, el('strong', {}, member.name || '—')),
    el('div', { class: 'card', style: 'background:#f8fafc; padding:14px; border-radius:8px; border:1px solid #e2e8f0;' },
      el('p', { style: 'margin:4px 0;' }, el('strong', {}, 'اسم الدخول / الجوال: '), el('code', { style: 'font-size:14px; color:#1e40af;' }, member.phone || user.username)),
      el('p', { style: 'margin:4px 0;' }, el('strong', {}, 'اسم المستخدم النظامي: '), el('code', {}, user.username)),
      el('p', { style: 'margin:4px 0;' }, el('strong', {}, 'الدور في التطبيق: '), el('span', { class: 'badge' }, user.role || '—')),
      el('p', { style: 'margin:4px 0;' }, el('strong', {}, 'الحالة: '), user.is_active ? el('span', { style: 'color:#16a34a; font-weight:bold;' }, 'نشط') : el('span', { style: 'color:#dc2626;' }, 'معطل'))
    ),
    el('p', { class: 'muted', style: 'font-size:12px; line-height:1.5;' }, '💡 ملاحظة: يمكن للمعلم تسجيل الدخول في تطبيق الأندرويد مباشرة برقم جواله المسجل مع كلمة المرور الخاصة به دون الحاجة لحفظ اسم مستخدم إنجليزي.'),
    el('div', { class: 'form-actions', style: 'margin-top:10px;' },
      el('button', { class: 'button button-outline', type: 'button', id: 'btn-reset-pwd' }, 'إعادة تعيين كلمة المرور'),
      el('button', { class: 'button', type: 'button', id: 'btn-copy-info' }, 'نسخ بيانات الدخول للمعلم')
    )
  );
  const dialog = modal(`بيانات دخول: ${member.name || ''}`, content);
  content.querySelector('#btn-copy-info').addEventListener('click', async () => {
    const text = `السلام عليكم ورحمة الله،\nالأستاذ/ة: ${member.name}\n\nبيانات الدخول لتطبيق غراس سنتر (EduTrack Pro):\n- رقم الدخول: ${member.phone || user.username}\n- الدور: ${user.role === 'teacher' ? 'معلم' : (user.role === 'supervisor' ? 'مشرف' : user.role)}\n\nنرجو تحميل التطبيق والدخول برقم الجوال وكلمة المرور المعتمدة.`;
    await safeCopyToClipboard(text);
  });
  content.querySelector('#btn-reset-pwd').addEventListener('click', () => {
    dialog.close();
    openResetPasswordModal(api, user, member);
  });
}

function openResetPasswordModal(api, user, member) {
  const initialPwd = generateSecureTempPassword();
  const pwdInput = el('input', { name: 'new_password', type: 'text', value: initialPwd, required: 'required', style: 'font-family:Consolas,monospace;' });
  const refreshBtn = el('button', { class: 'button button-outline', type: 'button', style: 'padding:4px 10px; font-size:12px;' }, 'توليد عشوائي');
  refreshBtn.addEventListener('click', () => { pwdInput.value = generateSecureTempPassword(); });
  const submit = el('button', { class: 'button', type: 'submit' }, 'حفظ كلمة المرور');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' },
    el('label', {}, 
      el('span', {}, `تعيين كلمة مرور جديدة للموظف: ${member.name || user.username}`),
      el('div', { style: 'display:flex; gap:8px; align-items:center; margin-top:4px;' }, pwdInput, refreshBtn)
    ),
    el('p', { class: 'muted', style: 'font-size:12px; margin-top:4px;' }, 'تم توليد كلمة مرور مؤقتة آمنة تلقائياً. تأكد من تزويد الموظف بها بعد الحفظ.'),
    el('div', { class: 'form-actions' }, submit, cancel)
  );
  const dialog = modal('إعادة تعيين كلمة المرور', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const newPwd = pwdInput.value.trim();
    try {
      await api.patch(`users/${user.id}`, { password: newPwd });
      await safeCopyToClipboard(`كلمة المرور الجديدة لحساب ${member.name || user.username}: ${newPwd}`);
      form.replaceChildren(
        el('div', { class: 'card', style: 'background:#f0fdf4; border:1px solid #bbf7d0; padding:14px; border-radius:8px;' },
          el('h4', { style: 'color:#15803d; margin:0 0 8px 0;' }, '✅ تم تحديث كلمة المرور بنجاح'),
          el('p', { style: 'margin:4px 0;' }, 'كلمة المرور الجديدة: ', el('code', { style: 'font-size:16px; font-weight:bold; color:#1e40af;' }, newPwd)),
          el('p', { class: 'muted', style: 'font-size:12px; margin-top:6px;' }, 'تم نسخ البيانات للحافظة، ويمكنك نسخها يدوياً من المربع أعلاه.')
        ),
        el('div', { class: 'form-actions', style: 'margin-top:12px;' },
          el('button', { class: 'button', type: 'button', id: 'btn-close-pwd-success' }, 'إغلاق')
        )
      );
      form.querySelector('#btn-close-pwd-success').addEventListener('click', dialog.close);
      toast('تم تحديث كلمة المرور بنجاح');
    } catch (e) {
      toast(e.message, true);
      submit.disabled = false;
    }
  });
}

function openStaffForm(api, member) {
  const editing = Boolean(member && member.id);
  const hasFinance = checkHasFinance(api);
  const isManager = checkIsManager(api);
  const value = key => (member && member[key] !== undefined && member[key] !== null) ? String(member[key]) : '';
  const statusOptions = [['active', 'نشط'], ['on_leave', 'إجازة'], ['terminated', 'منتهي']];
  const fields = [
    el('label', {}, el('span', {}, 'الاسم'), el('input', { name: 'name', type: 'text', required: 'required', value: value('name') })),
    el('label', {}, el('span', {}, 'المسمى الوظيفي'), el('input', { name: 'role_title', type: 'text', required: 'required', value: value('role_title') || 'معلم' })),
    el('label', {}, el('span', {}, 'الجوال'), el('input', { name: 'phone', type: 'tel', value: value('phone'), id: 'input-staff-phone' })),
    el('label', {}, el('span', {}, 'تاريخ التعيين'), el('input', { name: 'hire_date', type: 'date', value: value('hire_date') })),
  ];
  if (hasFinance) {
    fields.push(
      el('label', {}, el('span', {}, 'الراتب الأساسي'), el('input', { name: 'base_salary', type: 'number', min: '0', step: '0.01', required: 'required', value: value('base_salary') }))
    );
  }
  fields.push(
    el('label', {}, el('span', {}, 'الحالة'), select('status', statusOptions, value('status') || 'active', true))
  );

  let createAccountBox = null;
  if (!editing) {
    const roomOpts = [['', 'بدون حلقة مسندة']].concat(rooms.map(r => [r.id, r.name]));
    const initialTempPwd = generateSecureTempPassword();
    const isSupervisorInitial = (value('role_title') || '').includes('مشرف');
    const defaultRole = isSupervisorInitial ? 'supervisor' : 'teacher';

    createAccountBox = el('div', { class: 'card', style: 'grid-column: 1 / -1; background: #f0fdf4; border: 1px solid #bbf7d0; padding: 12px; border-radius: 8px;' },
      el('label', { style: 'display:flex; align-items:center; gap:8px; font-weight:bold; cursor:pointer;' },
        el('input', { type: 'checkbox', name: 'create_user_account', checked: 'checked', id: 'chk-create-account' }),
        el('span', {}, 'إنشاء حساب دخول لتطبيق الهاتف تلقائياً')
      ),
      el('div', { id: 'account-fields-panel', style: 'margin-top:10px; display:grid; grid-template-columns: 1fr 1fr; gap:8px;' },
        el('label', {}, el('span', {}, 'الدور في التطبيق'), select('auto_role', [['teacher', 'معلم'], ['supervisor', 'مشرف']], defaultRole, true)),
        el('label', {}, el('span', {}, 'اسم الدخول (افتراضياً رقم الجوال)'), el('input', { name: 'auto_username', type: 'text', placeholder: 'اختياري: اتركه لاستخدام الجوال' })),
        el('label', {}, el('span', {}, 'كلمة المرور المؤقتة'), el('input', { name: 'auto_password', type: 'text', value: initialTempPwd, style: 'font-family:Consolas,monospace;' })),
        el('label', {}, el('span', {}, 'الحلقة المسندة في التطبيق'), select('auto_room_id', roomOpts, ''))
      )
    );
    fields.push(createAccountBox);
  }

  const submit = el('button', { class: 'button', type: 'submit' }, editing ? 'حفظ التعديلات' : 'إضافة');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const formActions = [submit, cancel];
  if (editing && isManager) {
    const deleteBtn = el('button', {
      class: 'button button-outline',
      type: 'button',
      style: 'color: #dc2626; border-color: #fca5a5; background: #fff5f5; margin-right: auto;',
      title: 'حذف الموظف نهائياً'
    }, '🗑️ حذف الموظف');
    deleteBtn.addEventListener('click', () => {
      dialog.close();
      openDeleteStaffModal(api, member);
    });
    formActions.unshift(deleteBtn);
  }
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions', style: 'display: flex; gap: 8px; align-items: center;' }, ...formActions));
  const dialog = modal(editing ? 'تعديل موظف' : 'إضافة موظف', form);
  cancel.addEventListener('click', dialog.close);

  if (!editing && createAccountBox) {
    const chk = createAccountBox.querySelector('#chk-create-account');
    const pnl = createAccountBox.querySelector('#account-fields-panel');
    chk.addEventListener('change', () => {
      pnl.style.display = chk.checked ? 'grid' : 'none';
    });
  }

  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    Object.keys(payload).forEach(key => { payload[key] = String(payload[key]).trim(); });
    if (hasFinance && payload.base_salary !== undefined && payload.base_salary !== '') {
      payload.base_salary = Number(payload.base_salary);
    } else {
      delete payload.base_salary;
    }
    payload.phone = payload.phone || null;
    payload.hire_date = payload.hire_date || null;
    try {
      if (editing) {
        await api.patch(`staff/${member.id}`, payload);
        toast('تم تحديث بيانات الموظف');
      } else {
        const createUser = Boolean(form.querySelector('#chk-create-account')?.checked);
        const autoRole = payload.auto_role || 'teacher';
        const autoUsername = String(payload.auto_username || '').trim();
        const autoPassword = String(payload.auto_password || '').trim() || generateSecureTempPassword();
        const autoRoomId = payload.auto_room_id || null;

        delete payload.create_user_account;
        delete payload.auto_role;
        delete payload.auto_username;
        delete payload.auto_password;
        delete payload.auto_room_id;

        const newStaff = await api.post('staff', payload);

        if (createUser && newStaff && newStaff.id) {
          const finalUsername = autoUsername || payload.phone || ('user_' + String(newStaff.id).slice(0, 8));

          try {
            await api.post('users', {
              username: finalUsername,
              password: autoPassword,
              role: autoRole,
              staff_id: newStaff.id,
              room_id: autoRoomId,
              phone: payload.phone || null,
              permissions: {
                attendance: true,
                daily_evaluation: true,
                monthly_evaluation: true,
                students: true,
                finance: false
              }
            });
            toast(`تمت إضافة الموظف وتفعيل حساب الدخول: ${finalUsername}`);
          } catch (userErr) {
            toast(`تمت إضافة الموظف ولكن تعذر إنشاء الحساب: ${userErr.message}`, true);
          }
        } else {
          toast('تمت إضافة الموظف');
        }
      }
      dialog.close();
      await reload(api);
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

function openUserForm(api, member) {
  const roomOptions = [['', 'بدون حلقة']].concat(rooms.map(room => [room.id, room.name]));
  const defaultUsername = member.phone || '';
  const initialTempPwd = generateSecureTempPassword();
  const fields = [
    el('label', {}, el('span', {}, 'اسم الدخول / اسم المستخدم'), el('input', { name: 'username', type: 'text', required: 'required', value: defaultUsername, autocomplete: 'off' })),
    el('label', {}, el('span', {}, 'كلمة المرور المؤقتة'), el('input', { name: 'password', type: 'text', required: 'required', value: initialTempPwd, autocomplete: 'new-password', style: 'font-family:Consolas,monospace;' })),
    el('label', {}, el('span', {}, 'الدور'), select('role', ROLES, 'teacher', true)),
    el('label', {}, el('span', {}, 'الحلقة'), select('room_id', roomOptions, '')),
    el('strong', {}, 'الصلاحيات'),
    ...PERMISSIONS.map(([key, label]) => el('label', {}, el('input', { type: 'checkbox', name: key, checked: (key !== 'finance' ? 'checked' : undefined) }), el('span', {}, label)))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, 'إنشاء الحساب');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions' }, submit, cancel));
  const dialog = modal(`حساب دخول: ${member.name || ''}`, form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const data = Object.fromEntries(new FormData(form).entries());
    const permissions = {};
    PERMISSIONS.forEach(([key]) => { permissions[key] = Boolean(form.querySelector(`[name="${key}"]`)?.checked); });
    if (data.role === 'supervisor') {
      permissions.finance = false;
    }
    const payload = {
      username: String(data.username).trim(),
      password: String(data.password),
      role: data.role,
      staff_id: member.id,
      room_id: data.room_id || null,
      phone: member.phone || null,
      permissions
    };
    try {
      await api.post('users', payload);
      dialog.close();
      toast('تم إنشاء حساب الدخول بنجاح');
      await reload(api);
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

function openPayrollForm(api, member) {
  const fields = [
    el('label', {}, el('span', {}, 'الشهر'), el('input', { name: 'month', type: 'month', required: 'required', value: currentMonth() })),
    el('label', {}, el('span', {}, 'الراتب الأساسي'), el('input', { name: 'base', type: 'number', min: '0', step: '0.01', required: 'required', value: member.base_salary ?? '' })),
    el('label', {}, el('span', {}, 'البدلات'), el('input', { name: 'allowances', type: 'number', min: '0', step: '0.01', value: '0' })),
    el('label', {}, el('span', {}, 'المكافأة'), el('input', { name: 'bonus', type: 'number', min: '0', step: '0.01', value: '0' })),
    el('label', {}, el('span', {}, 'الخصومات'), el('input', { name: 'deductions', type: 'number', min: '0', step: '0.01', value: '0' })),
    el('label', {}, el('span', {}, 'سلفة مخصومة'), el('input', { name: 'advance_deducted', type: 'number', min: '0', step: '0.01', value: '0' })),
    el('label', {}, el('span', {}, 'ملاحظة'), el('textarea', { name: 'note' }))
  ];
  const submit = el('button', { class: 'button', type: 'submit' }, 'تسجيل الراتب');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions' }, submit, cancel));
  const dialog = modal(`راتب: ${member.name || ''}`, form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.staff_id = member.id;
    ['base', 'allowances', 'bonus', 'deductions', 'advance_deducted'].forEach(key => { payload[key] = Number(payload[key] || 0); });
    payload.note = String(payload.note || '').trim();
    try {
      await api.post('payroll-runs', payload);
      dialog.close();
      toast('تم تسجيل الراتب');
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

function openAbsenceForm(api) {
  const staffOptions = staff.filter(s => (s.status || 'active') === 'active').map(s => [s.id, `${s.name} (${s.role_title || 'موظف'})`]);
  if (!staffOptions.length) {
    toast('لا يوجد موظفون متاحون', true);
    return;
  }

  const today = fmtDate(new Date());
  const staffSelect = select('staff_id', staffOptions, staffOptions[0][0], true);
  const dateInput = el('input', { name: 'date', type: 'date', required: 'required', value: today });
  const statusSelect = select('status', [['غائب', 'غائب'], ['متأخر', 'متأخر'], ['مستأذن', 'مستأذن'], ['حاضر', 'حاضر']], 'غائب', true);
  const deductionInput = el('input', { name: 'deduction', type: 'number', min: '0', step: '0.01', placeholder: 'مبلغ الخصم (اختياري)' });
  const noteInput = el('textarea', { name: 'note', placeholder: 'سبب الغياب أو تفاصيل الخصم...' });

  function calcDeduction() {
    const member = staff.find(s => s.id === staffSelect.value);
    const salary = Number(member?.base_salary || 0);
    const dayRate = salary ? Math.round((salary / 30) * 100) / 100 : 0;
    if (statusSelect.value === 'غائب') {
      deductionInput.value = dayRate > 0 ? String(dayRate) : '';
    } else if (statusSelect.value === 'متأخر') {
      deductionInput.value = dayRate > 0 ? String(Math.round((dayRate / 4) * 100) / 100) : '';
    } else {
      deductionInput.value = '0';
    }
  }

  staffSelect.addEventListener('change', calcDeduction);
  statusSelect.addEventListener('change', calcDeduction);
  calcDeduction();

  const fields = [
    el('label', {}, el('span', {}, 'الموظف'), staffSelect),
    el('label', {}, el('span', {}, 'التاريخ'), dateInput),
    el('label', {}, el('span', {}, 'الحالة'), statusSelect),
    el('label', {}, el('span', {}, 'مبلغ الخصم المحسوب (ر.س)'), deductionInput),
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', {}, 'الملاحظات'), noteInput),
  ];

  const submit = el('button', { class: 'button', type: 'submit' }, 'حفظ الغياب والخصم');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' }, ...fields, el('div', { class: 'form-actions', style: 'grid-column:1/-1;' }, submit, cancel));
  const dialog = modal('تسجيل غياب / خصم موظف', form);
  cancel.addEventListener('click', dialog.close);

  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    const deduction = Number(deductionInput.value || 0);
    const userNote = String(noteInput.value || '').trim();
    const fullNote = deduction > 0 ? `خصم: ${deduction} ر.س | ${userNote}` : userNote;

    const payload = [{
      staff_id: staffSelect.value,
      date: dateInput.value,
      status: statusSelect.value,
      note: fullNote || null
    }];

    try {
      await api.post('attendance/staff', payload);
      dialog.close();
      toast('تم تسجيل غياب الموظف بنجاح');
      await reload(api);
    } catch (error) {
      toast(error.message, true);
      submit.disabled = false;
    }
  });
}

async function openTeacherEvaluationModal(api, member) {
  toast('جاري تحميل بطاقة تقييم المعلم...');
  let evals = [];
  try {
    const res = await api.fetchAll(`teacher-evaluations?teacher_id=${member.id}`);
    evals = toList(res);
  } catch (err) {
    evals = [];
  }

  const historyBox = el('div', { style: 'max-height:220px; overflow-y:auto; border:1px solid #e2e8f0; border-radius:10px; padding:8px; background:#f8fafc; margin-bottom:12px;' });

  function renderEvalHistory() {
    if (!evals.length) {
      historyBox.replaceChildren(el('div', { style: 'text-align:center; padding:16px; color:#64748b; font-size:13px;' }, 'لا توجد تقييمات سابقة مسجلة لهذا المعلم'));
      return;
    }
    const cards = evals.map(ev => {
      const ratingColor = ev.rating === 'ممتاز' ? 'background:#dcfce7; color:#166534;' : (ev.rating === 'جيد جداً' ? 'background:#e0f2fe; color:#0369a1;' : (ev.rating === 'جيد' ? 'background:#fef3c7; color:#92400e;' : 'background:#fee2e2; color:#991b1b;'));
      return el('div', { style: 'padding:10px; border:1px solid #e2e8f0; border-radius:8px; background:#fff; margin-bottom:8px;' },
        el('div', { style: 'display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;' },
          el('div', { style: 'display:flex; align-items:center; gap:8px;' },
            el('span', { class: 'badge', style: ratingColor }, ev.rating || 'تقييم'),
            el('strong', { style: 'font-size:14px; color:#1e293b;' }, `الدرجة العامة: ${Number(ev.total_score || 0).toFixed(1)} / 10`)
          ),
          el('span', { style: 'font-size:12px; color:#64748b;' }, String(ev.evaluation_date || ev.created_at || '').slice(0, 10))
        ),
        el('div', { style: 'display:grid; grid-template-columns:1fr 1fr; gap:6px; font-size:12px; color:#475569; margin-bottom:6px;' },
          el('div', {}, `🎯 التفاعل بالقاعة: <strong>${ev.interaction_score || 0}/10</strong>`),
          el('div', {}, `📞 تواصل أولياء الأمور: <strong>${ev.guardian_comm_score || 0}/10</strong>`),
          el('div', {}, `⚖️ الانضباط والأخلاقيات: <strong>${ev.discipline_score || 0}/10</strong>`),
          el('div', {}, `📖 الالتزام بالخطط: <strong>${ev.curriculum_score || 0}/10</strong>`)
        ),
        ev.supervisor_notes ? el('p', { style: 'margin:4px 0 2px; font-size:12px; color:#334155;' }, `📝 <strong>ملاحظات المشرفة:</strong> ${ev.supervisor_notes}`) : null,
        ev.improvement_plan ? el('p', { style: 'margin:2px 0 0; font-size:12px; color:#0369a1;' }, `💡 <strong>خطة التحسين:</strong> ${ev.improvement_plan}`) : null
      );
    });
    historyBox.replaceChildren(...cards);
  }

  renderEvalHistory();

  // New Evaluation Form
  const s1Input = el('input', { type: 'number', step: '0.5', min: '0', max: '10', value: '9', required: 'required', style: 'padding:6px; border-radius:6px; border:1px solid #cbd5e1;' });
  const s2Input = el('input', { type: 'number', step: '0.5', min: '0', max: '10', value: '9', required: 'required', style: 'padding:6px; border-radius:6px; border:1px solid #cbd5e1;' });
  const s3Input = el('input', { type: 'number', step: '0.5', min: '0', max: '10', value: '9.5', required: 'required', style: 'padding:6px; border-radius:6px; border:1px solid #cbd5e1;' });
  const s4Input = el('input', { type: 'number', step: '0.5', min: '0', max: '10', value: '9', required: 'required', style: 'padding:6px; border-radius:6px; border:1px solid #cbd5e1;' });

  const ratingSelect = el('select', { style: 'padding:6px; border-radius:6px; border:1px solid #cbd5e1;' },
    el('option', { value: 'ممتاز' }, 'ممتاز (9 - 10)'),
    el('option', { value: 'جيد جداً' }, 'جيد جداً (8 - 8.9)'),
    el('option', { value: 'جيد' }, 'جيد (7 - 7.9)'),
    el('option', { value: 'يحتاج تحسين' }, 'يحتاج تحسين (أقل من 7)')
  );

  const notesText = el('textarea', { placeholder: 'نقاط القوة لتعزيزها وملاحظات التوجيه الميداني...', style: 'width:100%; min-height:55px; padding:6px; border-radius:6px; border:1px solid #cbd5e1;' });
  const planText = el('textarea', { placeholder: 'خطة التحسين والتصويب المطلوبة للأسبوع القادم...', style: 'width:100%; min-height:55px; padding:6px; border-radius:6px; border:1px solid #cbd5e1;' });

  const saveBtn = el('button', { class: 'button', type: 'button', style: 'width:100%; padding:9px; font-weight:700; margin-top:8px;' }, 'حفظ واعتماد بطاقة التقييم');

  const formSection = el('div', { style: 'border-top:1.5px solid #e2e8f0; padding-top:10px;' },
    el('h3', { style: 'font-size:14px; margin:0 0 8px; color:#1e293b;' }, '📝 تسجيل تقييم أداء جديد للمعلم (الأسبوعي / الميداني)'),
    el('div', { style: 'display:grid; grid-template-columns:1fr 1fr; gap:8px; margin-bottom:8px;' },
      el('label', {}, el('span', { style: 'display:block; font-size:11px; font-weight:600;' }, '1. التفاعل بالقاعة والتعامل مع الطلاب (من 10)'), s1Input),
      el('label', {}, el('span', { style: 'display:block; font-size:11px; font-weight:600;' }, '2. التعامل والتواصل مع أولياء الأمور (من 10)'), s2Input),
      el('label', {}, el('span', { style: 'display:block; font-size:11px; font-weight:600;' }, '3. الأخلاقيات والانضباط الإداري (من 10)'), s3Input),
      el('label', {}, el('span', { style: 'display:block; font-size:11px; font-weight:600;' }, '4. الحضور والالتزام بالخطط (من 10)'), s4Input)
    ),
    el('label', { style: 'display:block; margin-bottom:8px;' }, el('span', { style: 'display:block; font-size:11px; font-weight:600;' }, 'التقدير العام'), ratingSelect),
    el('label', { style: 'display:block; margin-bottom:8px;' }, el('span', { style: 'display:block; font-size:11px; font-weight:600;' }, 'ملاحظات المشرفة ونقاط القوة والضعف'), notesText),
    el('label', { style: 'display:block; margin-bottom:8px;' }, el('span', { style: 'display:block; font-size:11px; font-weight:600;' }, 'خطة وتوجيهات التحسين للأسبوع القادم'), planText),
    saveBtn
  );

  const modalBody = el('div', {},
    el('p', { style: 'font-size:13px; color:#475569; margin-top:0;' }, `منظومة إدارة الأداء وتقييم المعلمين — المعلم: <strong>${member.name}</strong> (${member.role_title || 'معلم'})`),
    historyBox,
    formSection
  );

  const dialog = modal(`تقييم أداء المعلم — ${member.name}`, modalBody);

  saveBtn.onclick = async () => {
    saveBtn.disabled = true;
    saveBtn.textContent = 'جاري حفظ التقييم...';
    try {
      const payload = {
        teacher_id: member.id,
        supervisor_id: api.currentUser?.staff_id || member.id,
        period_type: 'weekly',
        interaction_score: Number(s1Input.value),
        guardian_comm_score: Number(s2Input.value),
        discipline_score: Number(s3Input.value),
        curriculum_score: Number(s4Input.value),
        rating: ratingSelect.value,
        supervisor_notes: notesText.value.trim() || null,
        improvement_plan: planText.value.trim() || null
      };
      const created = await api.post('teacher-evaluations', payload);
      evals.unshift(created);
      renderEvalHistory();
      toast(`تم اعتماد تقييم أداء المعلم ${member.name} بنجاح`);
    } catch (err) {
      toast(err.message || 'فشل حفظ التقييم', true);
    } finally {
      saveBtn.disabled = false;
      saveBtn.textContent = 'حفظ واعتماد بطاقة التقييم';
    }
  };
}

export async function render(container, api) {
  staff = [];
  rooms = [];
  absences = [];
  container.replaceChildren();

  // Parse initial tab from URL hash params
  const params = getParams();
  const tab = params.get('tab');
  if (tab === 'absences') activeTab = 'absences';
  else if (tab === 'kpi_dashboard') activeTab = 'kpi_dashboard';
  else if (tab === 'teachers_supervisors') activeTab = 'teachers_supervisors';
  else if (tab === 'teachers') activeTab = 'teachers';
  else if (tab === 'supervisors') activeTab = 'supervisors';
  else activeTab = 'all';

  const TABS = [
    ['all', 'كل الكادر'],
    ['teachers_supervisors', 'المعلمون والمشرفون'],
    ['teachers', 'المعلمون'],
    ['supervisors', 'المشرفون'],
    ['kpi_dashboard', '📊 مؤشرات تقييم الأداء'],
    ['absences', 'غياب الموظفين والخصومات']
  ];

  tabsContainer = el('div', { class: 'toolbar', style: 'margin-bottom:12px; gap:6px;' });
  const theadEl = el('thead');
  tbody = el('tbody', {}, el('tr', {}, el('td', { colspan: '9', class: 'muted' }, 'جارٍ التحميل...')));

  function updateTableHeaders() {
    if (activeTab === 'absences') {
      theadEl.replaceChildren(
        el('tr', {},
          el('th', {}, 'الموظف'),
          el('th', {}, 'المسمى الوظيفي'),
          el('th', {}, 'التاريخ'),
          el('th', {}, 'الحالة'),
          el('th', {}, 'الخصم والملاحظة')
        )
      );
    } else if (activeTab === 'kpi_dashboard') {
      theadEl.replaceChildren(
        el('tr', {},
          el('th', {}, 'المعلم'),
          el('th', {}, 'القاعة / الحلقة'),
          el('th', {}, 'التفاعل الصفي'),
          el('th', {}, 'تواصل الأولياء'),
          el('th', {}, 'الانضباط والأخلاقيات'),
          el('th', {}, 'إتقان المنهج'),
          el('th', {}, 'المعدل والتقدير'),
          el('th', {}, 'ملاحظات المشرفة'),
          el('th', {}, 'إجراءات')
        )
      );
    } else {
      theadEl.replaceChildren(
        el('tr', {},
          el('th', {}, 'الاسم'),
          el('th', {}, 'المسمى الوظيفي'),
          el('th', {}, 'الجوال'),
          el('th', {}, 'الراتب الأساسي'),
          el('th', {}, 'الحالة'),
          el('th', {}, 'إجراءات')
        )
      );
    }
  }

  const toolbar = el('div', { class: 'toolbar' });

  function updateToolbar() {
    toolbar.replaceChildren();
    if (activeTab === 'absences') {
      const recordBtn = el('button', { class: 'button', type: 'button' }, '➕ تسجيل غياب / خصم جديد');
      recordBtn.onclick = () => openAbsenceForm(api);
      toolbar.append(recordBtn);
    } else {
      const addStaffBtn = el('button', { class: 'button', type: 'button' }, '➕ إضافة موظف');
      addStaffBtn.onclick = () => openStaffForm(api, null);

      const addTeacherAccBtn = el('button', {
        class: 'button',
        type: 'button',
        style: 'background: #1d4ed8; border-color: #1e40af; color: #fff;'
      }, '👨‍🏫 إضافة حساب معلم');
      addTeacherAccBtn.onclick = () => openCreateTeacherModal(api, () => reload(api));

      toolbar.append(addStaffBtn, addTeacherAccBtn);
    }
  }

  function updateTabs() {
    tabsContainer.replaceChildren(
      ...TABS.map(([id, label]) => {
        const btn = el('button', {
          class: activeTab === id ? 'button' : 'button button-outline',
          type: 'button'
        }, label);
        btn.onclick = async () => {
          activeTab = id;
          updateTabs();
          updateTableHeaders();
          updateToolbar();
          await reload(api);
        };
        return btn;
      })
    );
  }

  updateTabs();
  updateTableHeaders();
  updateToolbar();

  container.append(
    el('div', { class: 'view-header' }, el('h1', {}, 'إدارة الموظفين والكادر')),
    tabsContainer,
    toolbar,
    el('div', { class: 'table-wrap' }, el('table', {}, theadEl, tbody))
  );

  try { rooms = toList(await api.fetchAll('rooms')); } catch (error) { toast('تعذر تحميل الحلقات', true); }
  await reload(api);
}
