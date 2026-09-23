import { el, toast, modal } from './ui.js';

export function generateSecureTempPassword(length = 10) {
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

export async function safeCopyToClipboard(text) {
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
    textarea.remove();
  }
}

export function showCredentialsModal(username, password, roleText, targetName, extraText = '') {
  const msg = `السلام عليكم ورحمة الله،\nالأستاذ/ة أو الطالب/ولي الأمر: ${targetName}\n\nبيانات الدخول لتطبيق مركز غراس (EduTrack Pro):\n- الدور: ${roleText}\n- اسم الدخول: ${username}\n- كلمة المرور: ${password}\n${extraText ? extraText + '\n' : ''}\nنرجو تسجيل الدخول في التطبيق برقم الجوال أو اسم الدخول وكلمة المرور المعتمدة.`;
  const content = el('div', { style: 'display:flex; flex-direction:column; gap:12px;' },
    el('div', { class: 'card', style: 'background:#f0fdf4; border:1px solid #86efac; padding:14px; border-radius:8px;' },
      el('h3', { style: 'margin:0 0 8px 0; color:#166534; font-size:16px;' }, '✓ تم إنشاء وتفعيل الحساب بنجاح'),
      el('p', { style: 'margin:4px 0;' }, el('strong', {}, 'الاسم / الكيان: '), targetName),
      el('p', { style: 'margin:4px 0;' }, el('strong', {}, 'الدور في التطبيق: '), el('span', { class: 'badge' }, roleText)),
      el('p', { style: 'margin:4px 0;' }, el('strong', {}, 'اسم الدخول: '), el('code', { style: 'font-size:14px; color:#1e40af; font-weight:bold;' }, username)),
      el('p', { style: 'margin:4px 0;' }, el('strong', {}, 'كلمة المرور: '), el('code', { style: 'font-size:14px; color:#b91c1c; font-family:Consolas,monospace; font-weight:bold;' }, password)),
      extraText ? el('p', { style: 'margin:6px 0 0 0; font-size:13px; color:#15803d; line-height:1.5;' }, extraText) : null
    ),
    el('div', { class: 'form-actions', style: 'display:flex; justify-content:flex-end; gap:8px;' },
      el('button', { class: 'button', type: 'button', id: 'btn-copy-wa', style: 'background:#25d366; border-color:#22c55e;' }, '📋 نسخ رسالة الواتساب للعميل')
    )
  );
  const { close } = modal(`بيانات الحساب: ${targetName}`, content);
  content.querySelector('#btn-copy-wa').onclick = async () => {
    await safeCopyToClipboard(msg);
  };
}

export function openPermissionsModal(api, user, onUpdated) {
  const perms = user.permissions || {};
  const permStudents = el('input', { type: 'checkbox', checked: Boolean(perms.students) });
  const permAttendance = el('input', { type: 'checkbox', checked: Boolean(perms.attendance) });
  const permDailyEval = el('input', { type: 'checkbox', checked: Boolean(perms.daily_evaluation) });
  const permMonthlyEval = el('input', { type: 'checkbox', checked: Boolean(perms.monthly_evaluation) });
  const permFinance = el('input', { type: 'checkbox', checked: Boolean(perms.finance) });

  const modalSubmit = el('button', { class: 'button', type: 'submit' }, 'حفظ الصلاحيات');
  const modalForm = el('form', { class: 'form-grid' },
    el('p', { style: 'grid-column: 1 / -1;' }, `تعديل صلاحيات المشرف: `, el('strong', {}, user.username)),
    el('div', { style: 'grid-column: 1 / -1;' },
      el('div', { style: 'display: grid; gap: 8px;' },
        el('label', { style: 'display: flex; gap: 8px; align-items: center; font-weight: normal;' }, permStudents, '♟ إدارة وسجلات الطلاب'),
        el('label', { style: 'display: flex; gap: 8px; align-items: center; font-weight: normal;' }, permAttendance, '☑ تسجيل الحضور والغياب (طلاب وموظفين)'),
        el('label', { style: 'display: flex; gap: 8px; align-items: center; font-weight: normal;' }, permDailyEval, '★ إدخال التقييم اليومي للطلاب'),
        el('label', { style: 'display: flex; gap: 8px; align-items: center; font-weight: normal;' }, permMonthlyEval, '▥ التقييم الشهري وتقارير الإنجاز'),
        el('label', { style: 'display: flex; gap: 8px; align-items: center; font-weight: normal;' }, permFinance, '▤ العمليات المالية، الرسوم، المصروفات والحسابات')
      )
    ),
    el('div', { class: 'form-actions', style: 'grid-column: 1 / -1; margin-top: 14px;' }, modalSubmit)
  );

  const { close } = modal(`تعديل صلاحيات المشرف (${user.username})`, modalForm);

  modalForm.onsubmit = async ev => {
    ev.preventDefault();
    modalSubmit.disabled = true;
    try {
      await api.patch(`users/${user.id}`, {
        permissions: {
          students: permStudents.checked,
          attendance: permAttendance.checked,
          daily_evaluation: permDailyEval.checked,
          monthly_evaluation: permMonthlyEval.checked,
          finance: permFinance.checked
        }
      });
      toast('تم تحديث صلاحيات المشرف بنجاح');
      close();
      if (onUpdated) onUpdated();
    } catch (err) {
      toast(err.message || 'فشل تحديث الصلاحيات', true);
      modalSubmit.disabled = false;
    }
  };
}

export function openCreateManagerModal(api, onCreated) {
  const usernameInput = el('input', { type: 'text', required: 'required', autocomplete: 'off', placeholder: 'اسم المستخدم للمدير (مثال: admin_ahmed)' });
  const pwdInput = el('input', { type: 'password', required: 'required', minlength: '8', placeholder: 'كلمة المرور (8 أحرف فأكثر)' });
  const pwdConfirmInput = el('input', { type: 'password', required: 'required', minlength: '8', placeholder: 'تأكيد كلمة المرور' });

  const modalSubmit = el('button', {
    class: 'button',
    type: 'submit',
    style: 'background: #7c3aed; border-color: #6d28d9; color: #fff;'
  }, 'تأكيد إنشاء حساب المدير');

  const modalForm = el('form', { class: 'form-grid' },
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', {}, 'اسم المستخدم للمدير العام (أدمن)'), usernameInput),
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', {}, 'كلمة المرور المؤقتة'), pwdInput),
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', {}, 'تأكيد كلمة المرور'), pwdConfirmInput),
    el('div', {
      class: 'card',
      style: 'grid-column: 1 / -1; background: #faf5ff; border: 1px solid #e9d5ff; padding: 12px; margin-top: 6px; border-radius: 8px;'
    },
      el('div', { style: 'color: #6b21a8; font-weight: 600; margin-bottom: 4px;' }, '👑 تنبيه أمني عالي الحساسية:'),
      el('div', { style: 'color: #581c87; font-size: 13px; line-height: 1.6;' },
        'حساب المدير العام (الأدمن) يمتلك وصولاً كاملاً وغير مقيد لكافة بيانات الطلاب، المعلمين، الحلقات، التقارير والعمليات المالية والرواتب، بالإضافة لصلاحية تعديل الإعدادات وإدارة الحسابات. يرجى منح هذا الدور فقط للأشخاص المخولين رسمياً.'
      )
    ),
    el('div', { class: 'form-actions', style: 'grid-column: 1 / -1; margin-top: 14px;' }, modalSubmit)
  );

  const { close } = modal('👑 إنشاء حساب مدير عام (أدمن جديد)', modalForm);

  modalForm.onsubmit = async ev => {
    ev.preventDefault();
    const username = usernameInput.value.trim();
    const password = pwdInput.value;
    const confirmPassword = pwdConfirmInput.value;

    if (username.length < 3) {
      toast('اسم المستخدم يجب ألا يقل عن 3 أحرف', true);
      return;
    }
    if (password.length < 8) {
      toast('كلمة المرور يجب ألا تقل عن 8 أحرف', true);
      return;
    }
    if (password !== confirmPassword) {
      toast('كلمتا المرور غير متطابقتين', true);
      return;
    }

    modalSubmit.disabled = true;
    try {
      const payload = {
        username,
        password,
        role: 'manager',
        permissions: {
          students: true,
          attendance: true,
          daily_evaluation: true,
          monthly_evaluation: true,
          finance: true
        }
      };
      await api.post('users', payload);
      toast('تم إنشاء حساب المدير العام (الأدمن) بنجاح');
      close();
      showCredentialsModal(username, password, 'مدير عام (أدمن)', username);
      if (onCreated) onCreated();
    } catch (err) {
      toast(err.message || 'فشلت عملية إنشاء حساب المدير', true);
      modalSubmit.disabled = false;
    }
  };
}

export function openCreateSupervisorModal(api, onCreated) {
  const usernameInput = el('input', { type: 'text', required: 'required', autocomplete: 'off', placeholder: 'اسم المستخدم (مثال: supervisor_hoda)' });
  const pwdInput = el('input', { type: 'text', required: 'required', minlength: '8', value: generateSecureTempPassword(), style: 'font-family:Consolas,monospace;' });

  const permStudents = el('input', { type: 'checkbox', checked: true });
  const permAttendance = el('input', { type: 'checkbox', checked: true });
  const permDailyEval = el('input', { type: 'checkbox', checked: true });
  const permMonthlyEval = el('input', { type: 'checkbox', checked: false });
  const permFinance = el('input', { type: 'checkbox', checked: false });

  const modalSubmit = el('button', { class: 'button', type: 'submit' }, 'تأكيد إنشاء الحساب');
  const modalForm = el('form', { class: 'form-grid' },
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', {}, 'اسم المستخدم للمشرف'), usernameInput),
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', {}, 'كلمة المرور المؤقتة'), pwdInput),
    el('div', { style: 'grid-column: 1 / -1; margin-top: 6px;' },
      el('h4', { style: 'margin: 0 0 8px 0; font-size:14px;' }, 'تحديد صلاحيات المشرف:'),
      el('div', { style: 'display: grid; gap: 8px;' },
        el('label', { style: 'display: flex; gap: 8px; align-items: center; font-weight: normal;' }, permStudents, '♟ إدارة وسجلات الطلاب'),
        el('label', { style: 'display: flex; gap: 8px; align-items: center; font-weight: normal;' }, permAttendance, '☑ تسجيل الحضور والغياب (طلاب وموظفين)'),
        el('label', { style: 'display: flex; gap: 8px; align-items: center; font-weight: normal;' }, permDailyEval, '★ إدخال التقييم اليومي للطلاب'),
        el('label', { style: 'display: flex; gap: 8px; align-items: center; font-weight: normal;' }, permMonthlyEval, '▥ التقييم الشهري وتقارير الإنجاز'),
        el('label', { style: 'display: flex; gap: 8px; align-items: center; font-weight: normal;' }, permFinance, '▤ العمليات المالية، الرسوم، المصروفات والحسابات')
      )
    ),
    el('div', { class: 'form-actions', style: 'grid-column: 1 / -1; margin-top: 14px;' }, modalSubmit)
  );

  const { close } = modal('➕ إنشاء حساب مشرف بصلاحيات محددة', modalForm);

  modalForm.onsubmit = async ev => {
    ev.preventDefault();
    if (pwdInput.value.length < 8) {
      toast('كلمة المرور يجب ألا تقل عن 8 أحرف', true);
      return;
    }
    modalSubmit.disabled = true;
    try {
      const payload = {
        username: usernameInput.value.trim(),
        password: pwdInput.value,
        role: 'supervisor',
        permissions: {
          students: permStudents.checked,
          attendance: permAttendance.checked,
          daily_evaluation: permDailyEval.checked,
          monthly_evaluation: permMonthlyEval.checked,
          finance: permFinance.checked
        }
      };
      await api.post('users', payload);
      toast('تم إنشاء حساب المشرف وتعيين الصلاحيات بنجاح');
      close();
      showCredentialsModal(payload.username, payload.password, 'مشرف', payload.username);
      if (onCreated) onCreated();
    } catch (err) {
      toast(err.message || 'فشلت عملية إنشاء الحساب', true);
      modalSubmit.disabled = false;
    }
  };
}

export async function openCreateTeacherModal(api, onCreated, defaultStaffId = null) {
  toast('جاري تحميل قائمة الموظفين والحلقات...');
  let staffList = [];
  let roomsList = [];
  try {
    const [st, rm] = await Promise.all([api.fetchAll('staff'), api.fetchAll('rooms')]);
    staffList = Array.isArray(st) ? st : st.items || [];
    roomsList = Array.isArray(rm) ? rm : rm.items || [];
  } catch (err) {
    toast('تعذر جلب بيانات الكادر أو القاعات', true);
  }

  const activeStaff = staffList.filter(s => (s.status || 'active') === 'active');
  const staffSelect = el('select', { required: 'required' },
    el('option', { value: '' }, '-- اختر الموظف / المعلم --'),
    ...activeStaff.map(s => el('option', { value: s.id }, `${s.name} (${s.role_title || 'معلم'}) - جوال: ${s.phone || 'بدون'}`))
  );

  const roomSelect = el('select', {},
    el('option', { value: '' }, '-- بدون حلقة محددة (أو اختر حلقة) --'),
    ...roomsList.map(r => el('option', { value: r.id }, `${r.name} (${r.group_name || ''})`))
  );

  const usernameInput = el('input', { type: 'text', required: 'required', placeholder: 'رقم الجوال أو اسم المستخدم' });
  const pwdInput = el('input', { type: 'text', required: 'required', value: generateSecureTempPassword(), style: 'font-family:Consolas,monospace;' });
  const refreshPwdBtn = el('button', { class: 'button button-outline', type: 'button', style: 'padding:4px 8px; font-size:12px;' }, 'توليد كلمة جديدة');
  refreshPwdBtn.onclick = () => { pwdInput.value = generateSecureTempPassword(); };

  staffSelect.onchange = () => {
    const member = activeStaff.find(s => s.id === staffSelect.value);
    if (member) {
      if (member.phone) usernameInput.value = member.phone;
    }
  };

  if (defaultStaffId) {
    staffSelect.value = defaultStaffId;
    staffSelect.onchange();
  }

  const modalSubmit = el('button', { class: 'button', type: 'submit', style: 'background:#1d4ed8; color:#fff;' }, 'تأكيد إنشاء حساب المعلم');
  const modalForm = el('form', { class: 'form-grid' },
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', { style: 'font-weight:600;' }, 'اختر الموظف من قائمة الكادر *'), staffSelect),
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', { style: 'font-weight:600;' }, 'الحلقة المكلف بها في التطبيق'), roomSelect),
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', { style: 'font-weight:600;' }, 'اسم الدخول (يفضل رقم الجوال لتسجيل الدخول السلس) *'), usernameInput),
    el('label', { style: 'grid-column: 1 / -1;' },
      el('span', { style: 'font-weight:600;' }, 'كلمة المرور المؤقتة *'),
      el('div', { style: 'display:flex; gap:8px; align-items:center; margin-top:4px;' }, pwdInput, refreshPwdBtn)
    ),
    el('p', { class: 'muted', style: 'grid-column: 1 / -1; font-size:12px; margin:4px 0 0 0;' }, '💡 سيتمكن المعلم من تسجيل الدخول لتطبيق الهاتف برقم جواله وكلمة المرور المحددة، وسيكون نطاق وصوله محصوراً بالحلقة والطلاب المكلف بهم فقط.'),
    el('div', { class: 'form-actions', style: 'grid-column: 1 / -1; margin-top: 14px;' }, modalSubmit)
  );

  const { close } = modal('👨‍🏫 إنشاء حساب معلم لتطبيق الهاتف', modalForm);

  modalForm.onsubmit = async ev => {
    ev.preventDefault();
    const staffId = staffSelect.value;
    const username = usernameInput.value.trim();
    const password = pwdInput.value;
    const roomId = roomSelect.value || null;

    if (!staffId) {
      toast('يرجى اختيار الموظف', true);
      return;
    }
    if (!username) {
      toast('يرجى إدخال اسم الدخول أو رقم الجوال', true);
      return;
    }

    modalSubmit.disabled = true;
    try {
      const member = activeStaff.find(s => s.id === staffId);
      const payload = {
        username,
        password,
        role: 'teacher',
        staff_id: staffId,
        room_id: roomId,
        phone: member?.phone || username,
        permissions: {
          attendance: true,
          daily_evaluation: true,
          monthly_evaluation: true,
          students: true,
          finance: false
        }
      };
      await api.post('users', payload);
      toast(`تم إنشاء حساب المعلم بنجاح: ${username}`);
      close();
      showCredentialsModal(username, password, 'معلم', member?.name || username, `الحلقة المسندة: ${roomSelect.options[roomSelect.selectedIndex]?.text || 'بدون'}`);
      if (onCreated) onCreated();
    } catch (err) {
      toast(err.message || 'فشلت عملية إنشاء حساب المعلم', true);
      modalSubmit.disabled = false;
    }
  };
}

export async function openCreateGuardianModal(api, onCreated, defaultStudentId = null) {
  toast('جاري تحميل قائمة الطلاب...');
  let studentsList = [];
  try {
    const st = await api.fetchAll('students');
    studentsList = Array.isArray(st) ? st : st.items || [];
  } catch (err) {
    toast('تعذر جلب بيانات الطلاب', true);
  }

  const guardianNameInput = el('input', { type: 'text', required: 'required', placeholder: 'الاسم الكامل لولي الأمر (مثال: محمد بن عبدالله)' });
  const guardianPhoneInput = el('input', { type: 'text', required: 'required', placeholder: 'رقم الجوال (مثال: 05xxxxxxxx)' });
  const relationSelect = el('select', {},
    el('option', { value: 'الأب' }, 'الأب'),
    el('option', { value: 'الأم' }, 'الأم'),
    el('option', { value: 'ولي الأمر' }, 'ولي الأمر'),
    el('option', { value: 'شخص آخر' }, 'شخص آخر')
  );
  const pwdInput = el('input', { type: 'text', required: 'required', value: generateSecureTempPassword(), style: 'font-family:Consolas,monospace;' });
  const refreshPwdBtn = el('button', { class: 'button button-outline', type: 'button', style: 'padding:4px 8px; font-size:12px;' }, 'توليد');
  refreshPwdBtn.onclick = () => { pwdInput.value = generateSecureTempPassword(); };

  // Search & select students
  const studentSearchInput = el('input', { type: 'search', placeholder: 'بحث في أسماء الطلاب لاختيار الأبناء...', style: 'margin-bottom:8px;' });
  const studentCheckboxesContainer = el('div', {
    style: 'max-height: 180px; overflow-y: auto; border: 1px solid var(--border, #e2e8f0); border-radius: 8px; padding: 8px; display: flex; flex-direction: column; gap: 6px; background: #fff;'
  });

  function renderStudentCheckboxes(q = '') {
    studentCheckboxesContainer.replaceChildren();
    const filtered = studentsList.filter(s => {
      if (!q) return true;
      return (s.name || '').includes(q) || (s.national_id || '').includes(q) || (s.guardian_phone || '').includes(q);
    });
    if (!filtered.length) {
      studentCheckboxesContainer.append(el('div', { class: 'muted', style: 'padding:6px; font-size:13px;' }, 'لا توجد نتائج مطابقة'));
      return;
    }
    filtered.forEach(s => {
      const chk = el('input', { type: 'checkbox', value: s.id, name: 'chk_student' });
      if (defaultStudentId && s.id === defaultStudentId) {
        chk.checked = true;
      }
      const item = el('label', { style: 'display:flex; align-items:center; gap:8px; cursor:pointer; font-weight:normal; padding:3px 4px; border-radius:4px;' },
        chk,
        el('span', {}, `${s.name} (${s.group_name || 'طالب'})`),
        s.guardian_phone ? el('span', { class: 'muted', style: 'font-size:11px;' }, `[هاتف: ${s.guardian_phone}]`) : null
      );
      item.onmouseover = () => { item.style.background = '#f8fafc'; };
      item.onmouseout = () => { item.style.background = ''; };
      studentCheckboxesContainer.append(item);
    });
  }
  renderStudentCheckboxes();
  studentSearchInput.oninput = () => renderStudentCheckboxes(studentSearchInput.value.trim());

  // Auto-check students when guardian phone matches
  guardianPhoneInput.oninput = () => {
    const phone = guardianPhoneInput.value.trim();
    if (phone.length >= 7) {
      const matchingStudents = studentsList.filter(s =>
        (s.guardian_phone && s.guardian_phone.includes(phone)) ||
        (s.father_phone && s.father_phone.includes(phone)) ||
        (s.mother_phone && s.mother_phone.includes(phone))
      );
      if (matchingStudents.length) {
        const set = new Set(matchingStudents.map(s => s.id));
        studentCheckboxesContainer.querySelectorAll('input[name="chk_student"]').forEach(chk => {
          if (set.has(chk.value)) chk.checked = true;
        });
        // Also suggest guardian name if available
        const first = matchingStudents[0];
        if (!guardianNameInput.value) {
          guardianNameInput.value = first.father_name || first.mother_name || '';
        }
      }
    }
  };

  if (defaultStudentId) {
    const defaultStudent = studentsList.find(s => s.id === defaultStudentId);
    if (defaultStudent) {
      guardianPhoneInput.value = defaultStudent.guardian_phone || defaultStudent.father_phone || '';
      guardianNameInput.value = defaultStudent.father_name || defaultStudent.mother_name || `ولي أمر ${defaultStudent.name}`;
      relationSelect.value = defaultStudent.guardian_relation || 'ولي الأمر';
    }
  }

  const modalSubmit = el('button', { class: 'button', type: 'submit', style: 'background:#059669; color:#fff;' }, 'تأكيد إنشاء حساب ولي الأمر');
  const modalForm = el('form', { class: 'form-grid' },
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', { style: 'font-weight:600;' }, 'اسم ولي الأمر *'), guardianNameInput),
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', { style: 'font-weight:600;' }, 'رقم جوال ولي الأمر (اسم الدخول) *'), guardianPhoneInput),
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', { style: 'font-weight:600;' }, 'صلة القرابة'), relationSelect),
    el('div', { style: 'grid-column: 1 / -1; margin-top:4px;' },
      el('span', { style: 'font-weight:600; display:block; margin-bottom:4px;' }, 'حدد الأبناء المكفولين في المركز التابعين لولي الأمر:'),
      studentSearchInput,
      studentCheckboxesContainer
    ),
    el('label', { style: 'grid-column: 1 / -1;' },
      el('span', { style: 'font-weight:600;' }, 'كلمة المرور المؤقتة *'),
      el('div', { style: 'display:flex; gap:8px; align-items:center; margin-top:4px;' }, pwdInput, refreshPwdBtn)
    ),
    el('div', { class: 'card', style: 'grid-column: 1 / -1; background:#ecfdf5; border:1px solid #a7f3d0; padding:10px; border-radius:8px;' },
      el('div', { style: 'color:#065f46; font-size:12px; line-height:1.5;' }, '🔒 ضمان الصلاحيات: ولي الأمر يرى حصراً في تطبيق الجوال بيانات الطلاب المحددين أعلاه (الحضور، التقييمات، التقارير والرسوم) دون أي إمكانية للاطلاع على أي طالب آخر.')
    ),
    el('div', { class: 'form-actions', style: 'grid-column: 1 / -1; margin-top: 14px;' }, modalSubmit)
  );

  const { close } = modal('👨‍👩‍👧 إنشاء حساب ولي أمر جديد وربط الأبناء', modalForm);

  modalForm.onsubmit = async ev => {
    ev.preventDefault();
    const name = guardianNameInput.value.trim();
    const phone = guardianPhoneInput.value.trim();
    const relation = relationSelect.value;
    const password = pwdInput.value;
    const selectedStudentIds = Array.from(studentCheckboxesContainer.querySelectorAll('input[name="chk_student"]:checked')).map(c => c.value);

    if (!name || !phone) {
      toast('يرجى إدخال اسم ولي الأمر ورقم جواله', true);
      return;
    }

    modalSubmit.disabled = true;
    try {
      const payload = {
        username: phone,
        password,
        role: 'guardian',
        phone,
        guardian_name: name,
        guardian_relation: relation,
        child_student_ids: selectedStudentIds
      };
      await api.post('users', payload);
      toast(`تم إنشاء حساب ولي الأمر بنجاح: ${phone}`);
      close();
      const selectedStudentNames = studentsList.filter(s => selectedStudentIds.includes(s.id)).map(s => s.name).join('، ');
      showCredentialsModal(phone, password, 'ولي أمر', name, selectedStudentNames ? `الأبناء المرتبطون: ${selectedStudentNames}` : 'لم يتم ربط طلاب بعد');
      if (onCreated) onCreated();
    } catch (err) {
      toast(err.message || 'فشلت عملية إنشاء حساب ولي الأمر', true);
      modalSubmit.disabled = false;
    }
  };
}

export async function openCreateStudentModal(api, onCreated, defaultStudentId = null) {
  toast('جاري تحميل بيانات الطلاب والمستخدمين...');
  let studentsList = [];
  let usersList = [];
  try {
    const [st, u] = await Promise.all([api.fetchAll('students'), api.fetchAll('users')]);
    studentsList = Array.isArray(st) ? st : st.items || [];
    usersList = Array.isArray(u) ? u : u.items || [];
  } catch (err) {
    toast('تعذر جلب بيانات الطلاب أو المستخدمين', true);
  }

  const studentSelect = el('select', { required: 'required' },
    el('option', { value: '' }, '-- اختر الطالب من المسجلين بالمركز --'),
    ...studentsList.map(s => el('option', { value: s.id }, `${s.name} (${s.group_name || 'عام'}) - هوية: ${s.national_id || '—'}`))
  );

  const infoBox = el('div', { class: 'card', style: 'grid-column: 1 / -1; background:#f8fafc; border:1px solid #e2e8f0; padding:12px; border-radius:8px; display:none;' });
  const usernameInput = el('input', { type: 'text', required: 'required', placeholder: 'رقم الهوية الوطنية أو اسم الدخول' });
  const pwdInput = el('input', { type: 'text', required: 'required', value: generateSecureTempPassword(), style: 'font-family:Consolas,monospace;' });
  const refreshPwdBtn = el('button', { class: 'button button-outline', type: 'button', style: 'padding:4px 8px; font-size:12px;' }, 'توليد');
  refreshPwdBtn.onclick = () => { pwdInput.value = generateSecureTempPassword(); };

  // Guardian parallel account checkbox
  const chkCreateGuardian = el('input', { type: 'checkbox', checked: true });
  const guardianPasswordInput = el('input', { type: 'text', value: generateSecureTempPassword(), style: 'font-family:Consolas,monospace;' });
  const guardianAccountSection = el('div', { style: 'grid-column: 1 / -1; margin-top:8px; padding:10px; background:#f0fdf4; border:1px solid #bbf7d0; border-radius:8px; display:none;' },
    el('label', { style: 'display:flex; align-items:center; gap:8px; font-weight:bold; cursor:pointer; color:#166534;' },
      chkCreateGuardian,
      el('span', {}, 'إنشاء حساب دخول لولي الأمر أيضاً بالتوازي')
    ),
    el('div', { id: 'guardian-pwd-row', style: 'margin-top:8px; display:grid; gap:4px;' },
      el('span', { style: 'font-size:13px;' }, 'كلمة المرور المؤقتة لحساب ولي الأمر:'),
      guardianPasswordInput
    )
  );

  chkCreateGuardian.onchange = () => {
    guardianAccountSection.querySelector('#guardian-pwd-row').style.display = chkCreateGuardian.checked ? 'grid' : 'none';
  };

  studentSelect.onchange = () => {
    const student = studentsList.find(s => s.id === studentSelect.value);
    if (!student) {
      infoBox.style.display = 'none';
      guardianAccountSection.style.display = 'none';
      return;
    }
    infoBox.style.display = 'block';
    usernameInput.value = student.national_id || student.guardian_phone || ('st_' + student.id.slice(0, 8));

    // Check if guardian account already exists
    const guardianPhone = student.guardian_phone || student.father_phone || student.mother_phone;
    const existingGuardianUser = usersList.find(u => u.role === 'guardian' && (u.username === guardianPhone || u.phone === guardianPhone));

    infoBox.replaceChildren(
      el('div', { style: 'display:grid; grid-template-columns: 1fr 1fr; gap:8px; font-size:13px;' },
        el('p', { style: 'margin:2px 0;' }, el('strong', {}, 'اسم الطالب: '), student.name),
        el('p', { style: 'margin:2px 0;' }, el('strong', {}, 'المجموعة / الفصل: '), `${student.group_name || '—'} / ${student.room_name || '—'}`),
        el('p', { style: 'margin:2px 0;' }, el('strong', {}, 'رقم الهوية: '), student.national_id || '—'),
        el('p', { style: 'margin:2px 0;' }, el('strong', {}, 'هاتف ولي الأمر: '), guardianPhone || '—')
      ),
      el('div', { style: 'margin-top:8px; padding-top:8px; border-top:1px solid #cbd5e1;' },
        existingGuardianUser
          ? el('span', { class: 'badge', style: 'background:#dcfce7; color:#15803d; font-weight:bold; font-size:12px;' }, `✓ ولي الأمر لديه حساب دخول نشط (اسم الدخول: ${existingGuardianUser.username})`)
          : el('span', { class: 'badge', style: 'background:#fef3c7; color:#92400e; font-size:12px;' }, `⚠️ ولي الأمر ليس لديه حساب دخول بعد`)
      )
    );

    if (!existingGuardianUser && guardianPhone) {
      guardianAccountSection.style.display = 'block';
    } else {
      guardianAccountSection.style.display = 'none';
    }
  };

  if (defaultStudentId) {
    studentSelect.value = defaultStudentId;
    studentSelect.onchange();
  }

  const modalSubmit = el('button', { class: 'button', type: 'submit', style: 'background:#0284c7; color:#fff;' }, 'تأكيد إنشاء حساب الطالب');
  const modalForm = el('form', { class: 'form-grid' },
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', { style: 'font-weight:600;' }, 'اختر الطالب *'), studentSelect),
    infoBox,
    el('label', { style: 'grid-column: 1 / -1;' }, el('span', { style: 'font-weight:600;' }, 'اسم الدخول للطالب (افتراضياً رقم الهوية) *'), usernameInput),
    el('label', { style: 'grid-column: 1 / -1;' },
      el('span', { style: 'font-weight:600;' }, 'كلمة المرور المؤقتة للطالب *'),
      el('div', { style: 'display:flex; gap:8px; align-items:center; margin-top:4px;' }, pwdInput, refreshPwdBtn)
    ),
    guardianAccountSection,
    el('div', { class: 'form-actions', style: 'grid-column: 1 / -1; margin-top: 14px;' }, modalSubmit)
  );

  const { close } = modal('🎓 إنشاء حساب طالب في التطبيق مع الربط بولي الأمر', modalForm);

  modalForm.onsubmit = async ev => {
    ev.preventDefault();
    const studentId = studentSelect.value;
    const studentUsername = usernameInput.value.trim();
    const studentPassword = pwdInput.value;
    const student = studentsList.find(s => s.id === studentId);

    if (!studentId || !student) {
      toast('يرجى اختيار الطالب', true);
      return;
    }
    if (!studentUsername) {
      toast('يرجى إدخال اسم الدخول للطالب', true);
      return;
    }

    modalSubmit.disabled = true;
    try {
      const studentPayload = {
        username: studentUsername,
        password: studentPassword,
        role: 'student',
        student_id: studentId,
        national_id: student.national_id || null,
        phone: student.guardian_phone || student.father_phone || null
      };
      await api.post('users', studentPayload);

      let extraNotes = '';
      const guardianPhone = student.guardian_phone || student.father_phone || student.mother_phone;
      if (chkCreateGuardian.checked && guardianAccountSection.style.display !== 'none' && guardianPhone) {
        try {
          const guardianPayload = {
            username: guardianPhone,
            password: guardianPasswordInput.value,
            role: 'guardian',
            phone: guardianPhone,
            guardian_name: student.father_name || student.mother_name || `ولي أمر ${student.name}`,
            guardian_relation: student.guardian_relation || 'ولي الأمر',
            child_student_ids: [studentId]
          };
          await api.post('users', guardianPayload);
          extraNotes = `\nكما تم إنشاء حساب ولي الأمر أيضاً بنجاح:\n- اسم دخول ولي الأمر: ${guardianPhone}\n- كلمة مرور ولي الأمر: ${guardianPasswordInput.value}`;
        } catch (gErr) {
          toast(`تم إنشاء حساب الطالب، ولكن تعذر إنشاء حساب ولي الأمر: ${gErr.message}`, true);
        }
      }

      toast(`تم إنشاء حساب الطالب بنجاح: ${student.name}`);
      close();
      showCredentialsModal(studentUsername, studentPassword, 'طالب', student.name, extraNotes);
      if (onCreated) onCreated();
    } catch (err) {
      toast(err.message || 'فشلت عملية إنشاء حساب الطالب', true);
      modalSubmit.disabled = false;
    }
  };
}
