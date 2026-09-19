import { el, toast, modal } from '../ui.js';

export async function render(container, api) {
  container.replaceChildren();

  // Fetch current user info
  let me = null;
  try {
    me = await api.get('me');
  } catch (error) {
    toast(error.message || 'تعذر تحميل بيانات المستخدم', true);
  }

  const header = el('div', { class: 'view-header' },
    el('div', {},
      el('h1', {}, 'الإعدادات وإدارة النظام'),
      el('p', { class: 'muted' }, 'إدارة الأمان، تغيير كلمة المرور، وصلاحيات الحساب')
    )
  );

  // 1. Change Password Card
  const currentPwdInput = el('input', { type: 'password', name: 'current_password', required: 'required', autocomplete: 'current-password' });
  const newPwdInput = el('input', { type: 'password', name: 'new_password', required: 'required', minlength: '8', autocomplete: 'new-password' });
  const confirmPwdInput = el('input', { type: 'password', name: 'confirm_password', required: 'required', minlength: '8', autocomplete: 'new-password' });
  const savePwdBtn = el('button', { class: 'button', type: 'submit' }, 'حفظ كلمة المرور الجديدة');

  const pwdForm = el('form', { class: 'card' },
    el('h2', { style: 'margin-top:0; font-size:18px;' }, '🔐 تغيير كلمة المرور'),
    el('p', { class: 'muted', style: 'margin-bottom:14px;' }, 'يجب ألا تقل كلمة المرور الجديدة عن 8 خانات'),
    el('div', { class: 'form-grid' },
      el('label', {}, 'كلمة المرور الحالية', currentPwdInput),
      el('div', {}),
      el('label', {}, 'كلمة المرور الجديدة', newPwdInput),
      el('label', {}, 'تأكيد كلمة المرور الجديدة', confirmPwdInput)
    ),
    el('div', { class: 'form-actions' }, savePwdBtn)
  );

  pwdForm.addEventListener('submit', async event => {
    event.preventDefault();
    const currentPassword = currentPwdInput.value;
    const newPassword = newPwdInput.value;
    const confirmPassword = confirmPwdInput.value;

    if (newPassword.length < 8) {
      toast('كلمة المرور يجب ألا تقل عن 8 أحرف', true);
      return;
    }
    if (newPassword !== confirmPassword) {
      toast('كلمة المرور الجديدة وتأكيدها غير متطابقين', true);
      return;
    }

    savePwdBtn.disabled = true;
    try {
      const res = await api.post('auth/change-password', {
        current_password: currentPassword,
        new_password: newPassword,
        confirm_password: confirmPassword,
      });
      toast(res.message || 'تم تحديث كلمة المرور بنجاح');
      pwdForm.reset();
    } catch (error) {
      toast(error.message || 'تعذر تغيير كلمة المرور', true);
    } finally {
      savePwdBtn.disabled = false;
    }
  });

  // 2. Admin User Management & Password Reset (if manager)
  let userMgmtCard = null;
  let refreshUsersList = null;
  if (me && me.role === 'manager') {
    const usersTableBody = el('tbody', {},
      el('tr', {}, el('td', { colspan: '5', class: 'muted', style: 'text-align:center;' }, 'جاري تحميل المستخدمين...'))
    );

    const addSupervisorBtn = el('button', { class: 'button', type: 'button', style: 'margin-bottom:14px;' }, '➕ إضافة حساب مشرف جديد');

    function openPermissionsModal(user, onUpdated) {
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

    function openCreateSupervisorModal(onCreated) {
      const usernameInput = el('input', { type: 'text', required: 'required', autocomplete: 'off', placeholder: 'اسم المستخدم (مثال: supervisor_hoda)' });
      const pwdInput = el('input', { type: 'password', required: 'required', minlength: '8', placeholder: 'كلمة المرور (8 أحرف فأكثر)' });

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
          if (onCreated) onCreated();
        } catch (err) {
          toast(err.message || 'فشلت عملية إنشاء الحساب', true);
          modalSubmit.disabled = false;
        }
      };
    }

    userMgmtCard = el('div', { class: 'card', style: 'margin-top:20px;' },
      el('div', { style: 'display:flex; justify-content:space-between; align-items:center; margin-bottom:14px; flex-wrap:wrap; gap:10px;' },
        el('div', {},
          el('h2', { style: 'margin:0; font-size:18px;' }, '👥 إدارة حسابات النظام والمشرفين'),
          el('p', { class: 'muted', style: 'margin:4px 0 0 0;' }, 'إنشاء حسابات المشرفين، تحديد الصلاحيات بدقة، وإعادة تعيين كلمات المرور')
        ),
        addSupervisorBtn
      ),
      el('div', { class: 'table-wrap' },
        el('table', {},
          el('thead', {},
            el('tr', {},
              el('th', {}, 'اسم المستخدم'),
              el('th', {}, 'الدور'),
              el('th', {}, 'الصلاحيات الممنوحة'),
              el('th', {}, 'الحالة'),
              el('th', {}, 'الإجراءات')
            )
          ),
          usersTableBody
        )
      )
    );

    // Load users function
    async function loadUsersList() {
      usersTableBody.replaceChildren(
        el('tr', {}, el('td', { colspan: '5', class: 'muted', style: 'text-align:center;' }, 'جاري تحديث المستخدمين...'))
      );
      try {
        const users = await api.get('users');
        const list = Array.isArray(users) ? users : users.items || [];
        usersTableBody.replaceChildren();

        if (!list.length) {
          usersTableBody.append(el('tr', {}, el('td', { colspan: '5', class: 'muted', style: 'text-align:center;' }, 'لا يوجد مستخدمين')));
          return;
        }

        list.forEach(u => {
          const roleLabels = { manager: 'مدير عام', supervisor: 'مشرف', teacher: 'معلم' };
          const resetBtn = el('button', { class: 'button button-outline', type: 'button' }, 'كلمة المرور');

          resetBtn.onclick = () => {
            const adminNewPwdInput = el('input', { type: 'password', required: 'required', minlength: '8', placeholder: 'كلمة مرور جديدة (8 أحرف فأكثر)' });
            const modalSubmit = el('button', { class: 'button', type: 'submit' }, 'تأكيد التغيير');
            const modalForm = el('form', {},
              el('p', {}, `إعادة تعيين كلمة المرور للمستخدم: `, el('strong', {}, u.username)),
              el('label', {}, 'كلمة المرور الجديدة', adminNewPwdInput),
              el('div', { class: 'form-actions', style: 'margin-top:14px;' }, modalSubmit)
            );

            const { close } = modal(`إعادة تعيين كلمة المرور`, modalForm);

            modalForm.onsubmit = async ev => {
              ev.preventDefault();
              if (adminNewPwdInput.value.length < 8) {
                toast('كلمة المرور يجب ألا تقل عن 8 أحرف', true);
                return;
              }
              modalSubmit.disabled = true;
              try {
                const res = await api.post(`auth/reset-password/${u.id}`, { new_password: adminNewPwdInput.value });
                toast(res.message || 'تم تحديث كلمة المرور');
                close();
              } catch (err) {
                toast(err.message || 'فشلت عملية إعادة التعيين', true);
                modalSubmit.disabled = false;
              }
            };
          };

          // Permissions cell
          const permsCell = el('td');
          if (u.role === 'manager') {
            permsCell.append(el('span', { class: 'badge', style: 'background:#dcfce7; color:#15803d;' }, 'كامل الصلاحيات'));
          } else if (u.role === 'supervisor') {
            const p = u.permissions || {};
            const activeTags = [];
            if (p.students) activeTags.push('الطلاب');
            if (p.attendance) activeTags.push('الحضور');
            if (p.daily_evaluation) activeTags.push('تقييم يومي');
            if (p.monthly_evaluation) activeTags.push('تقييم شهري');
            if (p.finance) activeTags.push('المالية');

            if (activeTags.length) {
              activeTags.forEach(tag => {
                permsCell.append(el('span', { class: 'badge', style: 'margin-left:4px; font-size:11px;' }, tag));
              });
            } else {
              permsCell.append(el('span', { class: 'muted', style: 'font-size:12px;' }, 'بدون صلاحيات'));
            }
          } else {
            permsCell.append(el('span', { class: 'muted' }, '—'));
          }

          const actionsCell = el('td', { style: 'display:flex; gap:6px; flex-wrap:wrap;' });
          if (u.role === 'supervisor') {
            const editPermsBtn = el('button', { class: 'button button-outline', type: 'button' }, 'تعديل الصلاحيات');
            editPermsBtn.onclick = () => openPermissionsModal(u, loadUsersList);
            actionsCell.append(editPermsBtn);
          }
          actionsCell.append(resetBtn);

          if ((u.role === 'supervisor' || u.role === 'teacher') && u.id !== me?.id) {
            const delBtn = el('button', {
              class: 'button button-outline',
              type: 'button',
              style: 'color:#dc2626; border-color:#fca5a5; background:#fef2f2;'
            }, '🗑 حذف الحساب');

            delBtn.onclick = () => {
              const confirmSubmit = el('button', {
                class: 'button',
                type: 'submit',
                style: 'background:#dc2626; border-color:#dc2626; color:#fff;'
              }, 'نعم، حذف الحساب نهائياً');
              const cancelBtn = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');

              const modalForm = el('form', {},
                el('p', { style: 'line-height:1.7; font-size:15px; margin-top:0;' },
                  'هل أنت متأكد من رغبتك في حذف حساب ',
                  el('strong', { style: 'color:#dc2626;' }, u.username || 'المستخدم'),
                  '؟'
                ),
                el('div', { class: 'card', style: 'background:#fff1f2; border:1px solid #fecdd3; padding:12px; margin-bottom:16px; border-radius:8px;' },
                  el('div', { style: 'color:#9f1239; font-weight:600; margin-bottom:4px;' }, '⚠️ تنبيه هام:'),
                  el('div', { style: 'color:#881337; font-size:13px; line-height:1.6;' },
                    'سيتم تعطيل وإلغاء وصول هذا المستخدم فوراً إلى المنظومة، مع الحفاظ على سلامة السجلات المرتبطة به في النظام.'
                  )
                ),
                el('div', { class: 'form-actions', style: 'display:flex; justify-content:flex-end; gap:8px;' },
                  cancelBtn,
                  confirmSubmit
                )
              );

              const { close } = modal(`حذف حساب المستخدم (${u.username})`, modalForm);
              cancelBtn.onclick = () => close();

              modalForm.onsubmit = async ev => {
                ev.preventDefault();
                confirmSubmit.disabled = true;
                try {
                  await api.del(`users/${u.id}`);
                  toast('تم حذف حساب المستخدم بنجاح');
                  close();
                  loadUsersList();
                } catch (err) {
                  toast(err.message || 'فشلت عملية حذف الحساب', true);
                  confirmSubmit.disabled = false;
                }
              };
            };

            actionsCell.append(delBtn);
          }

          usersTableBody.append(
            el('tr', {},
              el('td', {}, el('strong', {}, u.username || '—')),
              el('td', {}, roleLabels[u.role] || u.role || '—'),
              permsCell,
              el('td', {}, u.is_active ? el('span', { class: 'badge' }, 'نشط') : el('span', { class: 'badge red' }, 'معطل')),
              actionsCell
            )
          );
        });
      } catch (err) {
        usersTableBody.replaceChildren(
          el('tr', {}, el('td', { colspan: '5', class: 'muted', style: 'text-align:center;' }, 'تعذر جلب قائمة المستخدمين'))
        );
      }
    }

    refreshUsersList = loadUsersList;
    addSupervisorBtn.onclick = () => openCreateSupervisorModal(loadUsersList);
    loadUsersList();
  }

  // 3. System Info & Backup Card
  const backupBtn = el('button', { class: 'button button-outline', type: 'button' }, '📥 تصدير نسخة احتياطية كاملة (JSON)');
  backupBtn.onclick = async () => {
    backupBtn.disabled = true;
    toast('جاري تجهيز النسخة الاحتياطية...');
    try {
      const [students, rooms, payments, expenses, staff] = await Promise.all([
        api.get('students').catch(() => []),
        api.get('rooms').catch(() => []),
        api.get('payments').catch(() => []),
        api.get('expenses').catch(() => []),
        api.get('staff').catch(() => []),
      ]);
      const backupData = {
        exported_at: new Date().toISOString(),
        center: 'مركز غراس التعليمي',
        system: 'EduTrack Pro',
        data: { students, rooms, payments, expenses, staff }
      };
      const blob = new Blob([JSON.stringify(backupData, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = el('a', { href: url, download: `gheras_backup_${new Date().toISOString().slice(0,10)}.json` });
      document.body.append(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      toast('تم تنزيل النسخة الاحتياطية بنجاح');
    } catch (err) {
      toast('تعذر تنزيل النسخة الاحتياطية', true);
    } finally {
      backupBtn.disabled = false;
    }
  };

  const fileInput = el('input', { type: 'file', accept: '.json,application/json', style: 'display:none;' });
  const importBtn = el('button', {
    class: 'button button-outline',
    type: 'button',
    style: 'background:#f0fdf4; border-color:#86efac; color:#166534;'
  }, '📤 استيراد بيانات (JSON)');

  fileInput.addEventListener('change', async event => {
    const file = event.target.files?.[0];
    if (!file) return;
    fileInput.value = '';

    try {
      const text = await file.text();
      let payload;
      try {
        payload = JSON.parse(text);
      } catch (parseErr) {
        toast('الملف المحدد ليس بصيغة JSON صالحة', true);
        return;
      }

      if (!payload || typeof payload !== 'object') {
        toast('محتوى ملف JSON غير صالح', true);
        return;
      }

      const rootData = (payload.data && typeof payload.data === 'object') ? payload.data : payload;
      const getLen = val => Array.isArray(val) ? val.length : (val?.items?.length || 0);

      const counts = {
        students: getLen(rootData.students),
        rooms: getLen(rootData.rooms),
        staff: getLen(rootData.staff),
        users: getLen(rootData.users),
        payments: getLen(rootData.payments),
        expenses: getLen(rootData.expenses),
        attendance: getLen(rootData.attendance),
        evaluations: getLen(rootData.evaluations),
      };

      const totalFound = Object.values(counts).reduce((a, b) => a + b, 0);
      if (totalFound === 0) {
        toast('لم يتم العثور على سجلات معروفة داخل ملف JSON', true);
        return;
      }

      const previewGrid = el('div', { style: 'display:grid; grid-template-columns:repeat(auto-fit, minmax(110px, 1fr)); gap:10px; margin:14px 0;' },
        counts.students ? el('div', { class: 'card', style: 'padding:10px; text-align:center; background:#f0fdfa;' }, el('div', { style: 'font-size:20px; font-weight:bold; color:#0f766e;' }, String(counts.students)), el('div', { class: 'muted', style: 'font-size:12px;' }, 'طلاب')) : null,
        counts.rooms ? el('div', { class: 'card', style: 'padding:10px; text-align:center; background:#f0fdfa;' }, el('div', { style: 'font-size:20px; font-weight:bold; color:#0f766e;' }, String(counts.rooms)), el('div', { class: 'muted', style: 'font-size:12px;' }, 'قاعات')) : null,
        counts.staff ? el('div', { class: 'card', style: 'padding:10px; text-align:center; background:#f0fdfa;' }, el('div', { style: 'font-size:20px; font-weight:bold; color:#0f766e;' }, String(counts.staff)), el('div', { class: 'muted', style: 'font-size:12px;' }, 'موظفون')) : null,
        counts.users ? el('div', { class: 'card', style: 'padding:10px; text-align:center; background:#f0fdfa;' }, el('div', { style: 'font-size:20px; font-weight:bold; color:#0f766e;' }, String(counts.users)), el('div', { class: 'muted', style: 'font-size:12px;' }, 'مستخدمون')) : null,
        counts.payments ? el('div', { class: 'card', style: 'padding:10px; text-align:center; background:#f0fdfa;' }, el('div', { style: 'font-size:20px; font-weight:bold; color:#0f766e;' }, String(counts.payments)), el('div', { class: 'muted', style: 'font-size:12px;' }, 'مدفوعات')) : null,
        counts.expenses ? el('div', { class: 'card', style: 'padding:10px; text-align:center; background:#f0fdfa;' }, el('div', { style: 'font-size:20px; font-weight:bold; color:#0f766e;' }, String(counts.expenses)), el('div', { class: 'muted', style: 'font-size:12px;' }, 'مصروفات')) : null,
        counts.attendance ? el('div', { class: 'card', style: 'padding:10px; text-align:center; background:#f0fdfa;' }, el('div', { style: 'font-size:20px; font-weight:bold; color:#0f766e;' }, String(counts.attendance)), el('div', { class: 'muted', style: 'font-size:12px;' }, 'حضور')) : null,
        counts.evaluations ? el('div', { class: 'card', style: 'padding:10px; text-align:center; background:#f0fdfa;' }, el('div', { style: 'font-size:20px; font-weight:bold; color:#0f766e;' }, String(counts.evaluations)), el('div', { class: 'muted', style: 'font-size:12px;' }, 'تقييمات')) : null,
      );

      const confirmImportBtn = el('button', { class: 'button', type: 'submit' }, 'بدء الاستيراد الآن');
      const cancelBtn = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');

      const modalForm = el('form', {},
        el('p', { style: 'margin-top:0; font-size:15px; line-height:1.7;' },
          'تم فحص ملف ', el('strong', { style: 'color:#0f766e;' }, file.name), ' بنجاح. وفيما يلي إحصائيات السجلات الجاهزة للاستيراد:'
        ),
        previewGrid,
        el('div', { class: 'card', style: 'background:#eff6ff; border:1px solid #bfdbfe; padding:12px; margin-bottom:16px; border-radius:8px;' },
          el('div', { style: 'color:#1d4ed8; font-weight:600; margin-bottom:4px;' }, 'ℹ️ ملاحظة الاستيراد الآمن:'),
          el('div', { style: 'color:#1e40af; font-size:13px; line-height:1.6;' },
            'سيتم دمج السجلات بأمان داخل قاعدة البيانات مع تجنب تكرار المعرفات المتطابقة، والحفاظ على سلامة البيانات الحالية.'
          )
        ),
        el('div', { class: 'form-actions', style: 'display:flex; justify-content:flex-end; gap:8px;' },
          cancelBtn,
          confirmImportBtn
        )
      );

      const { close } = modal('معاينة وتأكيد استيراد البيانات (JSON)', modalForm);
      cancelBtn.onclick = () => close();

      modalForm.onsubmit = async ev => {
        ev.preventDefault();
        confirmImportBtn.disabled = true;
        confirmImportBtn.textContent = 'جاري معالجة الاستيراد...';
        toast('جاري معالجة واستيراد البيانات...');
        try {
          const res = await api.post('import', payload);
          toast(res.message || 'تم استيراد البيانات بنجاح');
          close();
          if (refreshUsersList) refreshUsersList();
        } catch (err) {
          toast(err.message || 'فشلت عملية استيراد البيانات', true);
          confirmImportBtn.disabled = false;
          confirmImportBtn.textContent = 'بدء الاستيراد الآن';
        }
      };
    } catch (err) {
      toast('تعذر قراءة ملف النسخة الاحتياطية', true);
    }
  });

  importBtn.onclick = () => fileInput.click();

  const centerInfoCard = el('div', { class: 'card', style: 'margin-top:20px;' },
    el('h2', { style: 'margin-top:0; font-size:18px;' }, '🏛️ بيانات المنظومة والنسخ الاحتياطي'),
    el('div', { style: 'line-height:1.8; margin-bottom:14px;' },
      el('div', {}, el('strong', {}, 'المنشأة: '), 'مركز غراس للرعاية النهارية والتعليم الذكي'),
      el('div', {}, el('strong', {}, 'الإصدار البرمجي: '), 'EduTrack Pro v2.5 (Clean VPS Architecture)'),
      el('div', {}, el('strong', {}, 'المستخدم الحالي: '), `${me?.name || me?.username || 'الإدارة'} (${me?.role || 'manager'})`)
    ),
    el('div', { class: 'form-actions', style: 'display:flex; gap:10px; flex-wrap:wrap;' }, backupBtn, importBtn, fileInput)
  );

  const cards = [header, pwdForm, userMgmtCard, me?.role === 'manager' ? centerInfoCard : null].filter(Boolean);
  container.append(...cards);
}
