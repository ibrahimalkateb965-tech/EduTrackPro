import { el, toast, modal, fmtSAR } from '../ui.js';

const TOOLS = [
  { label: 'الطلاب', icon: '♟', route: '#/students', perm: 'students' },
  { label: 'طلاب الإنجليزي — أولاد', icon: '👦', route: '#/students?group=الإنجليزي&gender=boys', perm: 'students' },
  { label: 'طلاب الإنجليزي — بنات', icon: '👧', route: '#/students?group=الإنجليزي&gender=girls', perm: 'students' },
  { label: 'القاعات', icon: '▦', route: '#/rooms' },
  { label: 'الجدول الدراسي', icon: '📅', route: '#/rooms' },
  { label: 'المعلمون والمشرفون', icon: '♙', route: '#/staff?tab=teachers_supervisors' },
  { label: 'الموظفون', icon: '▣', route: '#/staff' },
  { label: 'غياب الموظفين والخصم', icon: '☑', route: '#/staff?tab=absences' },
  { label: 'الحضور والغياب (الطلاب)', icon: '☑', route: '#/attendance', perm: 'attendance' },
  { label: 'التقييم اليومي', icon: '★', route: '#/attendance?tab=evaluation', perm: 'daily_evaluation' },
  { label: 'التقييم الشهري', icon: '▥', route: '#/reports', perm: 'monthly_evaluation' },
  { label: 'الرسوم والمدفوعات', icon: '▤', route: '#/payments', perm: 'finance' },
  { label: 'المصروفات', icon: '◔', route: '#/expenses', perm: 'finance' },
  { label: 'البنك والصندوق', icon: '♜', route: '#/accounts', perm: 'finance' },
  { label: 'التقارير الشاملة', icon: '📊', route: '#/reports' },
  { label: 'متابعة التقييم', icon: '📋', route: '#/reports' },
  { label: 'التواصل مع أولياء الأمور', icon: '💬', action: 'contact' },
  { label: 'النسخ الاحتياطي', icon: '🔐', action: 'backup', perm: 'finance' },
  { label: 'الإشعارات والتنبيهات', icon: '🔔', action: 'notifications' },
  { label: 'الإعدادات وتغيير كلمة المرور', icon: '⚙', route: '#/settings' },
];

function triggerBackup(api) {
  toast('جاري تجهيز النسخة الاحتياطية...');
  Promise.all([
    api.get('students').catch(() => []),
    api.get('rooms').catch(() => []),
    api.get('payments').catch(() => []),
    api.get('expenses').catch(() => []),
    api.get('staff').catch(() => []),
  ]).then(([students, rooms, payments, expenses, staff]) => {
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
      el('a', { href: '#/payments', class: 'button button-outline', style: 'text-decoration:none;' }, 'متابعة التحصيل')
    )
  );
  modal('🔔 مركز التنبيهات الذكية', content);
}

function showContactModal(api) {
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

function createToolButton(tool, api, report) {
  if (tool.action === 'backup') {
    const btn = el('a', { role: 'button', tabindex: '0' },
      el('span', {}, tool.icon),
      el('b', {}, tool.label)
    );
    btn.onclick = (e) => { e.preventDefault(); triggerBackup(api); };
    btn.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); triggerBackup(api); } };
    return btn;
  } else if (tool.action === 'notifications') {
    const btn = el('a', { role: 'button', tabindex: '0' },
      el('span', {}, tool.icon),
      el('b', {}, tool.label)
    );
    btn.onclick = (e) => { e.preventDefault(); showNotificationsModal(report); };
    btn.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); showNotificationsModal(report); } };
    return btn;
  } else if (tool.action === 'contact') {
    const btn = el('a', { role: 'button', tabindex: '0' },
      el('span', {}, tool.icon),
      el('b', {}, tool.label)
    );
    btn.onclick = (e) => { e.preventDefault(); showContactModal(api); };
    btn.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); showContactModal(api); } };
    return btn;
  } else if (tool.route) {
    return el('a', { href: tool.route },
      el('span', {}, tool.icon),
      el('b', {}, tool.label)
    );
  }
}

function whatsappButton(phone) {
  const digits = String(phone || '').replace(/\D/g, '');
  if (!digits) return el('span', { class: 'muted' }, 'لا يوجد رقم');
  return el('a', { class: 'button button-outline', href: `https://wa.me/${digits}`, target: '_blank', rel: 'noopener' }, 'متابعة واتساب');
}

export async function render(container, api) {
  container.replaceChildren();

  let me = null;
  try { me = await api.get('me') || {}; } catch (error) {}

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

  // Tools Panel
  const visibleTools = TOOLS.filter(tool => {
    if (!tool.perm) return true;
    if (isSupervisor) return Boolean(perms[tool.perm]);
    return true;
  });
  const toolsPanel = el('div', { class: 'gdash-tools-panel' });
  visibleTools.forEach(tool => toolsPanel.append(createToolButton(tool, api, report)));

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

  container.append(welcome, stats, toolsPanel, bottom);
}

