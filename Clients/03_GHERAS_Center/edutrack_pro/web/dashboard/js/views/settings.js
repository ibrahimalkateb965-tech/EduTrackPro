import { el, toast, modal } from '../ui.js';
import {
  generateSecureTempPassword,
  showCredentialsModal,
  openPermissionsModal,
  openCreateManagerModal,
  openCreateSupervisorModal,
  openCreateTeacherModal,
  openCreateGuardianModal,
  openCreateStudentModal
} from '../account_modals.js';

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

    const addManagerBtn = el('button', {
      class: 'button',
      type: 'button',
      style: 'background: #7c3aed; border-color: #6d28d9; color: #fff;'
    }, '👑 إضافة حساب مدير عام (أدمن)');

    const addSupervisorBtn = el('button', {
      class: 'button button-outline',
      type: 'button'
    }, '➕ إضافة حساب مشرف');

    const addTeacherBtn = el('button', {
      class: 'button',
      type: 'button',
      style: 'background: #1d4ed8; border-color: #1e40af; color: #fff;'
    }, '👨‍🏫 إضافة حساب معلم');

    const addGuardianBtn = el('button', {
      class: 'button',
      type: 'button',
      style: 'background: #059669; border-color: #047857; color: #fff;'
    }, '👨‍👩‍👧 إضافة حساب ولي أمر');

    const addStudentBtn = el('button', {
      class: 'button',
      type: 'button',
      style: 'background: #0284c7; border-color: #0369a1; color: #fff;'
    }, '🎓 إضافة حساب طالب');






    let activeUsersTab = 'all';
    const userTabsBar = el('div', { class: 'toolbar', style: 'margin-bottom:12px; gap:6px; flex-wrap:wrap;' });
    const USER_TABS = [
      ['all', 'كل الحسابات'],
      ['teachers', '👨‍🏫 المعلمون'],
      ['guardians', '👨‍👩‍👧 أولياء الأمور'],
      ['students', '🎓 الطلاب'],
      ['admins', '👑 الإدارة والمشرفون']
    ];

    userMgmtCard = el('div', { class: 'card', style: 'margin-top:20px;' },
      el('div', { style: 'display:flex; justify-content:space-between; align-items:center; margin-bottom:14px; flex-wrap:wrap; gap:10px;' },
        el('div', {},
          el('h2', { style: 'margin:0; font-size:18px;' }, '👥 إدارة حسابات النظام والمستخدمين'),
          el('p', { class: 'muted', style: 'margin:4px 0 0 0;' }, 'إنشاء وإدارة حسابات المعلمين، أولياء الأمور، الطلاب، والمدراء وتحديد الصلاحيات والربط العلائقي')
        ),
        el('div', { style: 'display:flex; gap:8px; flex-wrap:wrap;' },
          addTeacherBtn,
          addGuardianBtn,
          addStudentBtn,
          addSupervisorBtn,
          addManagerBtn
        )
      ),
      userTabsBar,
      el('div', { class: 'table-wrap' },
        el('table', {},
          el('thead', {},
            el('tr', {},
              el('th', {}, 'اسم المستخدم والجوال'),
              el('th', {}, 'الدور والكيان المرتبط'),
              el('th', {}, 'الصلاحيات والنطاق التعليمي'),
              el('th', {}, 'الحالة'),
              el('th', {}, 'الإجراءات')
            )
          ),
          usersTableBody
        )
      )
    );

    let allLoadedUsers = [];

    function renderUsersRows(tabId = activeUsersTab) {
      activeUsersTab = tabId;
      userTabsBar.replaceChildren(
        ...USER_TABS.map(([id, label]) => {
          const btn = el('button', {
            class: activeUsersTab === id ? 'button' : 'button button-outline',
            type: 'button',
            style: 'padding:4px 12px; font-size:13px;'
          }, label);
          btn.onclick = () => renderUsersRows(id);
          return btn;
        })
      );

      const filtered = allLoadedUsers.filter(u => {
        if (activeUsersTab === 'teachers') return u.role === 'teacher';
        if (activeUsersTab === 'guardians') return u.role === 'guardian';
        if (activeUsersTab === 'students') return u.role === 'student';
        if (activeUsersTab === 'admins') return u.role === 'manager' || u.role === 'supervisor';
        return true;
      });

      usersTableBody.replaceChildren();

      if (!filtered.length) {
        usersTableBody.append(el('tr', {}, el('td', { colspan: '5', class: 'muted', style: 'text-align:center; padding:18px;' }, 'لا توجد حسابات مطابقة في هذا القسم')));
        return;
      }

      filtered.forEach(u => {
        const isMe = me && u.id === me.id;
        const resetBtn = el('button', { class: 'button button-outline', type: 'button', style: 'padding:4px 8px; font-size:12px;' }, 'كلمة المرور');

        resetBtn.onclick = () => {
          const adminNewPwdInput = el('input', { type: 'text', required: 'required', minlength: '6', value: generateSecureTempPassword(), style: 'font-family:Consolas,monospace;' });
          const modalSubmit = el('button', { class: 'button', type: 'submit' }, 'تأكيد التغيير');
          const modalForm = el('form', { class: 'form-grid' },
            el('p', { style: 'grid-column:1/-1;' }, `إعادة تعيين كلمة المرور للمستخدم: `, el('strong', {}, u.username)),
            el('label', { style: 'grid-column:1/-1;' }, el('span', {}, 'كلمة المرور الجديدة'), adminNewPwdInput),
            el('div', { class: 'form-actions', style: 'grid-column:1/-1; margin-top:14px;' }, modalSubmit)
          );

          const { close } = modal(`إعادة تعيين كلمة المرور (${u.username})`, modalForm);

          modalForm.onsubmit = async ev => {
            ev.preventDefault();
            if (adminNewPwdInput.value.length < 6) {
              toast('كلمة المرور يجب ألا تقل عن 6 أحرف', true);
              return;
            }
            modalSubmit.disabled = true;
            try {
              await api.patch(`users/${u.id}`, { password: adminNewPwdInput.value });
              toast('تم تحديث كلمة المرور بنجاح');
              close();
              showCredentialsModal(u.username, adminNewPwdInput.value, u.role, u.username);
            } catch (err) {
              toast(err.message || 'فشلت عملية إعادة التعيين', true);
              modalSubmit.disabled = false;
            }
          };
        };

        // Linked entity description
        const roleCell = el('td', {});
        let linkedDesc = '';
        if (u.role === 'manager') {
          roleCell.append(el('span', { class: 'badge', style: 'background:#f3e8ff; color:#6b21a8; font-weight:600;' }, '👑 مدير عام (أدمن)'));
        } else if (u.role === 'supervisor') {
          roleCell.append(el('span', { class: 'badge' }, 'مشرف'));
        } else if (u.role === 'teacher') {
          roleCell.append(
            el('div', {},
              el('span', { class: 'badge', style: 'background:#dbeafe; color:#1e40af; font-weight:600;' }, '👨‍🏫 معلم'),
              el('div', { style: 'font-size:12px; margin-top:3px;' }, el('strong', {}, u.staff_name || '—')),
              u.room_name ? el('div', { class: 'muted', style: 'font-size:11px;' }, `حلقة: ${u.room_name}`) : null
            )
          );
        } else if (u.role === 'guardian') {
          const childrenList = u.children && u.children.length ? u.children.map(c => c.name).join('، ') : 'بدون أبناء مربوطين';
          roleCell.append(
            el('div', {},
              el('span', { class: 'badge', style: 'background:#d1fae5; color:#065f46; font-weight:600;' }, '👨‍👩‍👧 ولي أمر'),
              el('div', { style: 'font-size:12px; margin-top:3px;' }, el('strong', {}, u.guardian_name || '—')),
              el('div', { style: 'font-size:11px; color:#047857;' }, `الأبناء: ${childrenList}`)
            )
          );
        } else if (u.role === 'student') {
          roleCell.append(
            el('div', {},
              el('span', { class: 'badge', style: 'background:#e0f2fe; color:#0369a1; font-weight:600;' }, '🎓 طالب'),
              el('div', { style: 'font-size:12px; margin-top:3px;' }, el('strong', {}, u.student_name || '—')),
              u.linked_guardian ? el('div', { style: 'font-size:11px; color:#0284c7;' }, `ولي الأمر: ${u.linked_guardian.name}`) : null
            )
          );
        } else {
          roleCell.append(el('span', { class: 'badge' }, u.role || '—'));
        }

        // Permissions and Scope cell
        const permsCell = el('td');
        if (u.role === 'manager') {
          permsCell.append(el('span', { class: 'badge', style: 'background:#dcfce7; color:#15803d; font-weight:600;' }, '★ كامل الصلاحيات الإدارية والمالية'));
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
        } else if (u.role === 'teacher') {
          permsCell.append(el('span', { style: 'font-size:12px; color:#1e40af;' }, `نطاق: تدريس وتقييم حلقة ${u.room_name || 'المسندة'}`));
        } else if (u.role === 'guardian') {
          permsCell.append(el('span', { style: 'font-size:12px; color:#065f46;' }, `نطاق: متابعة سجلات أبنائه (${u.children?.length || 0})`));
        } else if (u.role === 'student') {
          permsCell.append(el('span', { style: 'font-size:12px; color:#0284c7;' }, 'نطاق: استعراض سجله الشخصي والواجبات'));
        } else {
          permsCell.append(el('span', { class: 'muted' }, '—'));
        }

        const actionsCell = el('td', { style: 'display:flex; gap:6px; flex-wrap:wrap;' });
        if (u.role === 'supervisor') {
          const editPermsBtn = el('button', { class: 'button button-outline', type: 'button', style: 'padding:4px 8px; font-size:12px;' }, 'الصلاحيات');
          editPermsBtn.onclick = () => openPermissionsModal(api, u, loadUsersList);
          actionsCell.append(editPermsBtn);
        }
        actionsCell.append(resetBtn);

        if (u.id !== me?.id) {
          const isManagerAccount = u.role === 'manager';
          const delBtn = el('button', {
            class: 'button button-outline',
            type: 'button',
            style: isManagerAccount
              ? 'color:#991b1b; border-color:#f87171; background:#fee2e2; font-weight:600; padding:4px 8px; font-size:12px;'
              : 'color:#dc2626; border-color:#fca5a5; background:#fef2f2; padding:4px 8px; font-size:12px;'
          }, '🗑 حذف');

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

        const usernameCell = el('td', {},
          el('div', { style: 'font-weight:bold; font-size:13px;' }, u.username || '—'),
          u.phone && u.phone !== u.username ? el('div', { class: 'muted', style: 'font-size:11px;' }, `جوال: ${u.phone}`) : null,
          isMe ? el('span', { class: 'badge', style: 'margin-top:2px; background:#e0e7ff; color:#3730a3; font-size:10px;' }, 'حسابك الحالي') : null
        );

        usersTableBody.append(
          el('tr', {},
            usernameCell,
            roleCell,
            permsCell,
            el('td', {}, u.is_active ? el('span', { class: 'badge' }, 'نشط') : el('span', { class: 'badge red' }, 'معطل')),
            actionsCell
          )
        );
      });
    }

    // Load users function
    async function loadUsersList() {
      usersTableBody.replaceChildren(
        el('tr', {}, el('td', { colspan: '5', class: 'muted', style: 'text-align:center;' }, 'جاري تحديث المستخدمين...'))
      );
      try {
        const users = await api.fetchAll('users');
        allLoadedUsers = Array.isArray(users) ? users : users.items || [];
        renderUsersRows(activeUsersTab);
      } catch (err) {
        usersTableBody.replaceChildren(
          el('tr', {}, el('td', { colspan: '5', class: 'muted', style: 'text-align:center;' }, 'تعذر جلب قائمة المستخدمين'))
        );
      }
    }

    refreshUsersList = loadUsersList;
    addManagerBtn.onclick = () => openCreateManagerModal(api, loadUsersList);
    addSupervisorBtn.onclick = () => openCreateSupervisorModal(api, loadUsersList);
    addTeacherBtn.onclick = () => openCreateTeacherModal(api, loadUsersList);
    addGuardianBtn.onclick = () => openCreateGuardianModal(api, loadUsersList);
    addStudentBtn.onclick = () => openCreateStudentModal(api, loadUsersList);
    loadUsersList();
  }

  // 3. System Info & Backup Card
  const backupBtn = el('button', { class: 'button button-outline', type: 'button' }, '📥 تصدير نسخة احتياطية كاملة (JSON)');
  backupBtn.onclick = async () => {
    backupBtn.disabled = true;
    toast('جاري تجهيز النسخة الاحتياطية...');
    try {
      const [students, rooms, payments, expenses, staff] = await Promise.all([
        api.fetchAll('students').catch(() => []),
        api.fetchAll('rooms').catch(() => []),
        api.fetchAll('payments').catch(() => []),
        api.fetchAll('expenses').catch(() => []),
        api.fetchAll('staff').catch(() => []),
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

  const SETTING_FIELDS = [
    { key: 'academic_year',  label: 'العام الدراسي (يُطبع على الكروت والتقارير)', placeholder: '1447-1448 هـ' },
    { key: 'center_name',    label: 'اسم المركز الرسمي',                          placeholder: 'مركز غراس للرعاية النهارية والتعليم الذكي' },
    { key: 'center_phone',   label: 'هاتف المركز',                                placeholder: '05xxxxxxxx' },
    { key: 'center_address', label: 'عنوان المركز',                               placeholder: 'حوطة بني تميم' },
    { key: 'manager_title',  label: 'المسمى الوظيفي للمدير في المطبوعات',         placeholder: 'مدير عام المركز' },
    { key: 'manager_name',   label: 'اسم المدير في المطبوعات',                    placeholder: 'إدارة المركز' },
  ];

  const settingInputs = {};
  SETTING_FIELDS.forEach(field => {
    settingInputs[field.key] = el('input', field.key === 'center_phone'
      ? { type: 'text', name: field.key, maxlength: '200', placeholder: field.placeholder, required: 'required', inputmode: 'tel', dir: 'ltr' }
      : { type: 'text', name: field.key, maxlength: '200', placeholder: field.placeholder, required: 'required' });
  });

  if (me?.role === 'manager') {
    try {
      const res = await api.get('settings');
      const current = res?.settings || {};
      SETTING_FIELDS.forEach(field => { settingInputs[field.key].value = current[field.key] || ''; });
    } catch (error) {
      toast(error.message || 'تعذر تحميل إعدادات المركز', true);
    }
  }

  const saveSettingsBtn = el('button', { class: 'button', type: 'submit' }, 'حفظ إعدادات المركز');

  const centerSettingsCard = el('form', { class: 'card', style: 'margin-top:20px;' },
    el('h2', { style: 'margin-top:0; font-size:18px;' }, '🏛️ إعدادات المركز والعام الدراسي والمطبوعات'),
    el('p', { class: 'muted', style: 'margin-bottom:14px;' }, 'تظهر هذه البيانات في كرت ولي الأمر والتقارير المطبوعة. التعديل يسري فوراً على المطبوعات الجديدة.'),
    el('div', { class: 'form-grid' },
      ...SETTING_FIELDS.map(field => el('label', {}, field.label, settingInputs[field.key]))
    ),
    el('div', { class: 'form-actions' }, saveSettingsBtn)
  );

  centerSettingsCard.addEventListener('submit', async event => {
    event.preventDefault();
    const payload = {};
    SETTING_FIELDS.forEach(field => { payload[field.key] = settingInputs[field.key].value.trim(); });
    if (SETTING_FIELDS.some(field => !payload[field.key])) {
      toast('جميع حقول إعدادات المركز مطلوبة', true);
      return;
    }
    saveSettingsBtn.disabled = true;
    try {
      const res = await api.put('settings', payload);
      const saved = res?.settings || payload;
      SETTING_FIELDS.forEach(field => { settingInputs[field.key].value = saved[field.key] || ''; });
      toast('تم حفظ إعدادات المركز بنجاح');
    } catch (error) {
      toast(error.message || 'تعذر حفظ إعدادات المركز', true);
    } finally {
      saveSettingsBtn.disabled = false;
    }
  });

  const centerInfoCard = el('div', { class: 'card', style: 'margin-top:20px;' },
    el('h2', { style: 'margin-top:0; font-size:18px;' }, '🏛️ بيانات المنظومة والنسخ الاحتياطي'),
    el('div', { style: 'line-height:1.8; margin-bottom:14px;' },
      el('div', {}, el('strong', {}, 'المنشأة: '), settingInputs.center_name.value || 'مركز غراس للرعاية النهارية والتعليم الذكي'),
      el('div', {}, el('strong', {}, 'الإصدار البرمجي: '), 'EduTrack Pro v2.5 (Clean VPS Architecture)'),
      el('div', {}, el('strong', {}, 'المستخدم الحالي: '), `${me?.name || me?.username || 'الإدارة'} (${me?.role || 'manager'})`)
    ),
    el('div', { class: 'form-actions', style: 'display:flex; gap:10px; flex-wrap:wrap;' }, backupBtn, importBtn, fileInput)
  );

  const cards = [header, pwdForm, userMgmtCard, me?.role === 'manager' ? centerSettingsCard : null, me?.role === 'manager' ? centerInfoCard : null].filter(Boolean);
  container.append(...cards);
}
