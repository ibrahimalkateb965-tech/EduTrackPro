import { el, toast, modal, fmtSAR } from '../ui.js';

const ALL_SHORTCUTS = [
  // --- الإدارة المالية ---
  { id: 'finance_overview', category: 'الإدارة المالية', label: 'لوحة الإدارة المالية', desc: 'مؤشرات الدخل والمصروفات والأرباح', icon: '💰', route: '#/finance?tab=overview', perm: 'finance' },
  { id: 'payments', category: 'الإدارة المالية', label: 'الرسوم والمدفوعات', desc: 'تسجيل الدفعات وخطط الأقساط والسندات', icon: '💳', route: '#/finance?tab=payments', perm: 'finance' },
  { id: 'expenses', category: 'الإدارة المالية', label: 'سجل المصروفات', desc: 'إدارة وتصنيف نفقات المركز', icon: '🔻', route: '#/finance?tab=expenses', perm: 'finance' },
  { id: 'accounts', category: 'الإدارة المالية', label: 'البنك والخزائن', desc: 'أرصدة الصناديق والتحويلات المالية', icon: '🏦', route: '#/finance?tab=accounts', perm: 'finance' },
  { id: 'receivables', category: 'الإدارة المالية', label: 'الذمم والمتأخرات', desc: 'متابعة المبالغ المعلقة وتذكير أولياء الأمور', icon: '📌', route: '#/finance?tab=receivables', perm: 'finance' },
  { id: 'payroll', category: 'الإدارة المالية', label: 'مسير الرواتب', desc: 'رواتب الموظفين والبدلات والخصومات', icon: '💼', route: '#/finance?tab=payroll', perm: 'manager_only' },
  { id: 'financial_reports', category: 'الإدارة المالية', label: 'التقارير والمطابقة', desc: 'إقفال الشهر ومطابقة الحسابات', icon: '📑', route: '#/reports', perm: 'finance' },

  // --- شؤون الطلاب والتعليم ---
  { id: 'students', category: 'شؤون الطلاب والتعليم', label: 'قائمة الطلاب', desc: 'إدارة بيانات وتسجيل الطلاب', icon: '♟', route: '#/students', perm: 'students' },
  { id: 'english_boys', category: 'شؤون الطلاب والتعليم', label: 'طلاب الإنجليزي (أولاد)', desc: 'فئة الإنجليزي للبنين', icon: '👦', route: '#/students?tab=english_boys', perm: 'students' },
  { id: 'english_girls', category: 'شؤون الطلاب والتعليم', label: 'طلاب الإنجليزي (بنات)', desc: 'فئة الإنجليزي للبنات', icon: '👧', route: '#/students?tab=english_girls', perm: 'students' },
  { id: 'attendance', category: 'شؤون الطلاب والتعليم', label: 'حضور الطلاب', desc: 'تسجيل الحضور والغياب اليومي', icon: '☑', route: '#/attendance', perm: 'attendance' },
  { id: 'daily_eval', category: 'شؤون الطلاب والتعليم', label: 'التقييم اليومي', desc: 'تقييم الحفظ والمستوى اليومي', icon: '★', route: '#/attendance?tab=evaluation', perm: 'daily_evaluation' },
  { id: 'monthly_eval', category: 'شؤون الطلاب والتعليم', label: 'التقييم الشهري', desc: 'سجلات التقييم الشهري والنهائي', icon: '▥', route: '#/reports', perm: 'monthly_evaluation' },
  { id: 'rooms', category: 'شؤون الطلاب والتعليم', label: 'القاعات والفصول', desc: 'إدارة القاعات والمجموعات', icon: '▦', route: '#/rooms', perm: 'students' },
  { id: 'schedule', category: 'شؤون الطلاب والتعليم', label: 'الجدول الدراسي', desc: 'توزيع الحصص والفترات', icon: '📅', route: '#/rooms', perm: 'students' },

  // --- الشؤون الإدارية والموظفون ---
  { id: 'staff', category: 'الشؤون الإدارية والموظفون', label: 'سجل الموظفين', desc: 'إدارة ملفات الكادر التعليمي والإداري', icon: '▣', route: '#/staff', perm: 'manager_only' },
  { id: 'teachers_supervisors', category: 'الشؤون الإدارية والموظفون', label: 'المعلمون والمشرفون', desc: 'الكادر التعليمي وحسابات الدخول', icon: '♙', route: '#/staff?tab=teachers_supervisors', perm: 'manager_only' },
  { id: 'staff_absences', category: 'الشؤون الإدارية والموظفون', label: 'غياب الموظفين والخصم', desc: 'تسجيل غياب الكادر واحتساب الخصومات', icon: '📋', route: '#/staff?tab=absences', perm: 'manager_only' },
  { id: 'reports', category: 'الشؤون الإدارية والموظفون', label: 'التقارير الإدارية', desc: 'تقارير الحضور والطلاب والمستوى', icon: '📊', route: '#/reports', perm: 'manager_only' },
  { id: 'settings', category: 'الشؤون الإدارية والموظفون', label: 'الإعدادات وكلمة المرور', desc: 'تخصيص النظام وإدارة الحساب', icon: '⚙', route: '#/settings' },

  // --- أدوات وإجراءات سريعة ---
  { id: 'contact_whatsapp', category: 'أدوات وإجراءات سريعة', label: 'مركز التواصل (واتساب)', desc: 'إرسال التنبيهات وسجل تواصل أولياء الأمور', icon: '💬', route: '#/communication', perm: 'students' },
  { id: 'notifications', category: 'أدوات وإجراءات سريعة', label: 'التنبيهات الذكية', desc: 'ملخص التنبيهات والغياب اليوم', icon: '🔔', action: 'notifications', perm: 'manager_only' },
  { id: 'backup', category: 'أدوات وإجراءات سريعة', label: 'تنزيل نسخة احتياطية', desc: 'تصدير نسخة كاملة من البيانات محلياً', icon: '🔐', action: 'backup', perm: 'manager_only' }
];

const DEFAULT_SHORTCUTS = [
  'students',
  'attendance',
  'finance_overview',
  'payments',
  'expenses',
  'accounts',
  'receivables',
  'payroll',
  'staff',
  'rooms',
  'contact_whatsapp',
  'backup'
];

let isEditMode = false;

function getSavedShortcuts() {
  try {
    const raw = localStorage.getItem('gheras_quick_shortcuts_v1');
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed) && parsed.length) return parsed;
    }
  } catch (e) {}
  return [...DEFAULT_SHORTCUTS];
}

function saveShortcuts(ids) {
  try {
    localStorage.setItem('gheras_quick_shortcuts_v1', JSON.stringify(ids));
  } catch (e) {}
}

function triggerBackup(api) {
  toast('جاري تجهيز النسخة الاحتياطية...');
  Promise.all([
    api.get('students').catch(() => []),
    api.get('rooms').catch(() => []),
    api.get('payments').catch(() => []),
    api.get('expenses').catch(() => []),
    api.get('staff').catch(() => []),
    api.get('ledger-accounts').catch(() => [])
  ]).then(([students, rooms, payments, expenses, staff, accounts]) => {
    const backupData = {
      exported_at: new Date().toISOString(),
      center: 'مركز غراس التعليمي',
      system: 'EduTrack Pro',
      data: { students, rooms, payments, expenses, staff, accounts }
    };
    const blob = new Blob([JSON.stringify(backupData, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = el('a', { href: url, download: `gheras_backup_${new Date().toISOString().slice(0,10)}.json` });
    document.body.append(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    toast('تم تنزيل النسخة الاحتياطية بنجاح');
  }).catch(() => {
    toast('تعذر تنزيل النسخة الاحتياطية', true);
  });
}

function showNotificationsModal(report) {
  const content = el('div', {},
    el('p', { style: 'font-size:15px; margin-bottom:12px;' }, 'ملخص التنبيهات المباشرة لليوم:'),
    el('ul', { style: 'padding-right:20px; line-height:2;' },
      el('li', {}, `عدد الطلاب المتغيبين اليوم: `, el('strong', { style: 'color:var(--g-red);' }, String(report.absent_today || 0))),
      el('li', {}, `المستحقات المالية المتبقية: `, el('strong', { style: 'color:var(--g-teal-dark);' }, fmtSAR(report.outstanding_total || 0))),
      el('li', {}, `نسبة الحضور المسجلة: `, el('strong', { style: 'color:var(--g-teal);' }, `${report.students_count ? Math.round(((report.present_today || 0) / report.students_count) * 100) : 0}%`))
    ),
    el('div', { class: 'form-actions', style: 'margin-top:16px;' },
      el('a', { href: '#/attendance', class: 'button', style: 'text-decoration:none;' }, 'متابعة الحضور'),
      el('a', { href: '#/finance?tab=receivables', class: 'button button-outline', style: 'text-decoration:none;' }, 'متابعة المتأخرات')
    )
  );
  modal('🔔 مركز التنبيهات الذكية', content);
}

function showContactModal() {
  const phoneInput = el('input', { type: 'tel', placeholder: 'رقم هاتف ولي الأمر (مثال: 0501234567)' });
  const msgInput = el('textarea', { placeholder: 'نص الرسالة المطلوب إرسالها...' });
  const sendBtn = el('button', { class: 'button', type: 'button' }, 'فتح واتساب المباشر');

  sendBtn.onclick = () => {
    const raw = phoneInput.value.replace(/\D/g, '');
    if (!raw) {
      toast('يرجى كتابة رقم الهاتف', true);
      return;
    }
    const intl = raw.startsWith('0') ? '966' + raw.slice(1) : raw.startsWith('966') ? raw : '966' + raw;
    const text = encodeURIComponent(msgInput.value.trim());
    window.open(`https://wa.me/${intl}?text=${text}`, '_blank', 'noopener');
  };

  const content = el('div', {},
    el('p', { class: 'muted', style: 'margin-bottom:12px;' }, 'إرسال رسائل ومتابعة سريعة لأولياء الأمور عبر واتساب'),
    el('div', { style: 'display:grid; gap:10px;' },
      el('label', {}, 'رقم الهاتف', phoneInput),
      el('label', {}, 'نص الرسالة', msgInput),
      el('div', { class: 'form-actions' }, sendBtn)
    )
  );
  modal('💬 التواصل السريع مع أولياء الأمور', content);
}

function openShortcutsCustomizerModal(api, onSaved) {
  const currentIds = new Set(getSavedShortcuts());
  const categories = {};

  ALL_SHORTCUTS.forEach(s => {
    if (!categories[s.category]) categories[s.category] = [];
    categories[s.category].push(s);
  });

  const categoryBlocks = Object.entries(categories).map(([catName, items]) => {
    const itemElements = items.map(item => {
      const checkbox = el('input', { type: 'checkbox', value: item.id });
      if (currentIds.has(item.id)) checkbox.checked = true;

      const itemWrap = el('label', { class: `shortcut-picker-item ${checkbox.checked ? 'selected' : ''}` },
        el('div', { class: 'shortcut-item-info' },
          el('span', { class: 'shortcut-item-icon' }, item.icon),
          el('div', {},
            el('div', { class: 'shortcut-item-label' }, item.label),
            el('div', { class: 'shortcut-item-desc' }, item.desc)
          )
        ),
        checkbox
      );

      checkbox.addEventListener('change', () => {
        itemWrap.classList.toggle('selected', checkbox.checked);
        if (checkbox.checked) currentIds.add(item.id);
        else currentIds.delete(item.id);
      });

      return itemWrap;
    });

    return el('div', { class: 'shortcuts-cat' },
      el('div', { class: 'shortcuts-cat-title' }, catName),
      el('div', { class: 'shortcuts-picker-grid' }, ...itemElements)
    );
  });

  const saveBtn = el('button', { class: 'button', type: 'button' }, 'حفظ التخصيص');
  const resetBtn = el('button', { class: 'button button-outline', type: 'button' }, 'استعادة الافتراضي');
  const cancelBtn = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');

  const content = el('div', {},
    el('p', { class: 'muted', style: 'margin-bottom:14px; font-size:13px;' },
      'اختر الصفحات والأدوات التي تريد تثبيتها في منطقة الدخول السريع في لوحة التحكم الرئيسية:'
    ),
    el('div', { style: 'max-height:60vh; overflow-y:auto; padding-left:6px;' }, ...categoryBlocks),
    el('div', { class: 'form-actions', style: 'margin-top:16px;' }, saveBtn, resetBtn, cancelBtn)
  );

  const dialog = modal('⚙ تخصيص منطقة الدخول السريع', content);
  cancelBtn.onclick = dialog.close;

  resetBtn.onclick = () => {
    saveShortcuts(DEFAULT_SHORTCUTS);
    dialog.close();
    toast('تمت استعادة الاختصارات الافتراضية');
    if (onSaved) onSaved();
  };

  saveBtn.onclick = () => {
    const selected = Array.from(currentIds);
    if (!selected.length) {
      toast('يرجى اختيار اختصار واحد على الأقل', true);
      return;
    }
    saveShortcuts(selected);
    dialog.close();
    toast('تم حفظ تخصيص الاختصارات بنجاح');
    if (onSaved) onSaved();
  };
}

function createShortcutCard(item, api, report, onRemove) {
  let innerCard;
  if (item.action === 'backup') {
    innerCard = el('a', { role: 'button', tabindex: '0' },
      el('span', {}, item.icon),
      el('b', {}, item.label)
    );
    innerCard.onclick = (e) => {
      if (isEditMode) return;
      e.preventDefault();
      triggerBackup(api);
    };
  } else if (item.action === 'notifications') {
    innerCard = el('a', { role: 'button', tabindex: '0' },
      el('span', {}, item.icon),
      el('b', {}, item.label)
    );
    innerCard.onclick = (e) => {
      if (isEditMode) return;
      e.preventDefault();
      showNotificationsModal(report);
    };
  } else if (item.action === 'contact') {
    innerCard = el('a', { role: 'button', tabindex: '0' },
      el('span', {}, item.icon),
      el('b', {}, item.label)
    );
    innerCard.onclick = (e) => {
      if (isEditMode) return;
      e.preventDefault();
      showContactModal();
    };
  } else {
    innerCard = el('a', { href: isEditMode ? 'javascript:void(0)' : item.route },
      el('span', {}, item.icon),
      el('b', {}, item.label)
    );
  }

  const removeBtn = el('button', {
    class: 'tool-remove-btn',
    type: 'button',
    title: 'إزالة هذا الاختصار',
    onclick: (e) => {
      e.stopPropagation();
      e.preventDefault();
      if (onRemove) onRemove(item.id);
    }
  }, '✕');

  return el('div', { class: 'tool-card-wrap' }, innerCard, removeBtn);
}

function whatsappButton(phone) {
  const digits = String(phone || '').replace(/\D/g, '');
  if (!digits) return el('span', { class: 'muted' }, 'لا يوجد رقم');
  return el('a', { class: 'button button-outline', href: `https://wa.me/${digits}`, target: '_blank', rel: 'noopener' }, 'متابعة واتساب');
}

export async function render(container, api) {
  container.replaceChildren();

  let me = api.currentUser;
  if (!me) {
    try { me = await api.get('me') || {}; } catch (error) { me = {}; }
  }

  const isSupervisor = me && me.role === 'supervisor';
  const perms = me?.permissions || {};
  const showFinance = !isSupervisor || Boolean(perms.finance);
  const showStudents = !isSupervisor || Boolean(perms.students);
  const showAttendance = !isSupervisor || Boolean(perms.attendance || perms.daily_evaluation);

  let report = {};
  try { report = await api.get('reports/daily') || {}; } catch (error) { toast(error.message, true); }

  const src = report.date ? new Date(report.date + 'T00:00:00') : new Date();
  const months = ["يناير","فبراير","مارس","أبريل","مايو","يونيو","يوليو","أغسطس","سبتمبر","أكتوبر","نوفمبر","ديسمبر"];
  const dateStr = `اليوم هو ${src.getDate()} ${months[src.getMonth()]} ${src.getFullYear()}`;

  let userName = me?.name || me?.username || 'الإدارة';

  // Welcome Section
  const welcome = el('div', { class: 'gdash-welcome' },
    el('h1', {}, `مرحباً بعودتك، ${userName}`),
    el('div', { class: 'gdash-date' }, dateStr)
  );

  // Stats Grid
  const statCards = [];
  if (showStudents) {
    statCards.push(el('div', { class: 'gstat orange' },
      el('div', { class: 'icon' }, '👥'),
      el('div', { class: 'label' }, 'إجمالي الطلاب'),
      el('div', { class: 'value' }, String(report.students_count || 0))
    ));
  }
  if (showAttendance) {
    statCards.push(
      el('div', { class: 'gstat teal' },
        el('div', { class: 'icon' }, '📝'),
        el('div', { class: 'label' }, 'حضور اليوم'),
        el('div', { class: 'value' }, String(report.present_today || 0))
      ),
      el('div', { class: 'gstat blue' },
        el('div', { class: 'icon' }, '🕋'),
        el('div', { class: 'label' }, 'الغياب'),
        el('div', { class: 'value' }, String(report.absent_today || 0))
      )
    );
  }
  if (showFinance) {
    statCards.push(
      el('div', { class: 'gstat purple' },
        el('div', { class: 'icon' }, '💸'),
        el('div', { class: 'label' }, 'مصروفات الشهر'),
        el('div', { class: 'value' }, fmtSAR(report.expenses_month || 0))
      ),
      el('div', { class: 'gstat gold' },
        el('div', { class: 'icon' }, '💰'),
        el('div', { class: 'label' }, 'تحصيل اليوم'),
        el('div', { class: 'value' }, fmtSAR(report.collected_today || 0))
      ),
      el('div', { class: 'gstat red' },
        el('div', { class: 'icon' }, '⚠️'),
        el('div', { class: 'label' }, 'المستحقات المتبقية'),
        el('div', { class: 'value' }, fmtSAR(report.outstanding_total || 0))
      )
    );
  }

  const stats = el('div', { class: 'gdash-stats' }, ...statCards);

  // Quick Access Section (Customizable)
  const toolsHeaderWrap = el('div', { class: 'tools-header' });
  const toolsPanel = el('div', { class: `gdash-tools-panel ${isEditMode ? 'editing-shortcuts' : ''}` });

  const renderToolsPanel = () => {
    const savedIds = getSavedShortcuts();
    const activeItems = savedIds
      .map(id => ALL_SHORTCUTS.find(s => s.id === id))
      .filter(Boolean)
      .filter(tool => {
        if (tool.perm === 'manager_only') return !isSupervisor;
        if (!tool.perm) return true;
        if (isSupervisor) return Boolean(perms[tool.perm]);
        return true;
      });

    toolsPanel.className = `gdash-tools-panel ${isEditMode ? 'editing-shortcuts' : ''}`;
    toolsPanel.replaceChildren();

    activeItems.forEach(item => {
      const card = createShortcutCard(item, api, report, (removeId) => {
        const next = savedIds.filter(id => id !== removeId);
        saveShortcuts(next);
        toast(`تمت إزالة اختصار: ${item.label}`);
        renderToolsPanel();
      });
      toolsPanel.append(card);
    });

    if (isEditMode) {
      const addCard = el('a', {
        class: 'tool-card-add',
        href: 'javascript:void(0)',
        onclick: () => openShortcutsCustomizerModal(api, renderToolsPanel)
      },
        el('span', { style: 'font-size:24px; color:var(--g-teal);' }, '＋'),
        el('b', {}, 'إضافة اختصارات')
      );
      toolsPanel.append(addCard);
    }
  };

  const editToggleBtn = el('button', {
    class: `button ${isEditMode ? '' : 'button-outline'}`,
    style: 'font-size:12px; padding:6px 12px;',
    type: 'button',
    onclick: () => {
      isEditMode = !isEditMode;
      editToggleBtn.className = `button ${isEditMode ? '' : 'button-outline'}`;
      editToggleBtn.textContent = isEditMode ? '✓ تم وإنهاء التعديل' : '✏ تعديل سريع';
      renderToolsPanel();
    }
  }, isEditMode ? '✓ تم وإنهاء التعديل' : '✏ تعديل سريع');

  const customizeBtn = el('button', {
    class: 'button button-outline',
    style: 'font-size:12px; padding:6px 12px;',
    type: 'button',
    onclick: () => openShortcutsCustomizerModal(api, renderToolsPanel)
  }, '⚙ تخصيص الدخول السريع');

  toolsHeaderWrap.replaceChildren(
    el('h3', {},
      el('span', {}, '⚡'),
      el('span', {}, 'منطقة الدخول السريع')
    ),
    el('div', { class: 'tools-actions' }, editToggleBtn, customizeBtn)
  );

  renderToolsPanel();

  // Absences Table
  const absences = Array.isArray(report.absences) ? report.absences : Array.isArray(report?.absent_students) ? report.absent_students : [];
  const rows = absences.map(item => el('tr', {},
    el('td', {}, item.student_name || item.name || '—'),
    el('td', {}, item.room_name || item.group_name || '—'),
    el('td', {}, item.guardian_phone || item.phone || '—'),
    el('td', {}, whatsappButton(item.guardian_phone || item.phone))
  ));
  
  const bottom = el('div', { style: 'margin-top: 2rem;' },
    el('div', { class: 'gdash-card' },
      el('h3', {}, 'غياب اليوم'),
      el('div', { class: 'table-wrap' }, el('table', {},
        el('thead', {}, el('tr', {},
          el('th', {}, 'الطالب'),
          el('th', {}, 'المجموعة'),
          el('th', {}, 'هاتف ولي الأمر'),
          el('th', {}, 'المتابعة')
        )),
        el('tbody', {}, rows.length ? rows : el('tr', {}, el('td', { colspan: '4', class: 'muted', style: 'text-align:center;' }, 'لا يوجد غياب اليوم')))
      ))
    )
  );

  container.append(welcome, stats, toolsHeaderWrap, toolsPanel);
  if (showAttendance) {
    container.append(bottom);
  }
}
