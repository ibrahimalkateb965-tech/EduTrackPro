import { el, toast, fmtSAR } from '../ui.js';

const TOOLS = [
  // 1. القائمة الجانبية (18)
  { label: 'الرئيسية', icon: '⌂', route: '#/home' },
  { label: 'الطلاب', icon: '♟', route: '#/students' },
  { label: 'طلاب الإنجليزي — أولاد', icon: '👦', route: null },
  { label: 'طلاب الإنجليزي — بنات', icon: '👧', route: null },
  { label: 'القاعات', icon: '▦', route: '#/rooms' },
  { label: 'المعلمون والمشرفون', icon: '♙', route: null },
  { label: 'الموظفون', icon: '▣', route: '#/staff' },
  { label: 'غياب الموظفين والخصم', icon: '☑', route: null },
  { label: 'الحضور والغياب (الطلاب)', icon: '☑', route: '#/attendance' },
  { label: 'التقييم اليومي', icon: '★', route: null },
  { label: 'التقييم الشهري', icon: '▥', route: null },
  { label: 'الرسوم والمدفوعات', icon: '▤', route: '#/payments' },
  { label: 'المصروفات', icon: '◔', route: '#/expenses' },
  { label: 'البنك والصندوق', icon: '♜', route: '#/accounts' },
  { label: 'التقارير (قائمة)', icon: '▥', route: null },
  { label: 'متابعة التقييم', icon: '📋', route: null },
  { label: 'الإعدادات', icon: '⚙', route: null },
  { label: 'تسجيل الخروج', icon: '⇥', route: 'logout' },
  // 2. اختصارات المهام العلوية (6)
  { label: 'المهام', icon: '📋', route: null },
  { label: 'التواصل', icon: '💬', route: null },
  { label: 'التقارير الشاملة', icon: '📊', route: '#/reports' },
  { label: 'النسخ الاحتياطي', icon: '🔐', route: null },
  { label: 'الجدول الدراسي', icon: '📅', route: null },
  { label: 'الإشعارات', icon: '🔔', route: null },
];

function createToolButton(tool) {
  if (tool.route === 'logout') {
    const btn = el('a', { role: 'button', tabindex: '0' },
      el('span', {}, tool.icon),
      el('b', {}, tool.label)
    );
    const action = (e) => { e.preventDefault(); window.dispatchEvent(new Event('gheras:logout')); };
    btn.onclick = action;
    btn.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') action(e); };
    return btn;
  } else if (tool.route) {
    return el('a', { href: tool.route },
      el('span', {}, tool.icon),
      el('b', {}, tool.label)
    );
  } else {
    const btn = el('a', { class: 'disabled', role: 'button', tabindex: '0', 'aria-disabled': 'true' },
      el('span', {}, tool.icon),
      el('b', {}, tool.label)
    );
    const action = (e) => { e.preventDefault(); toast('قريباً...', false); };
    btn.onclick = action;
    btn.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') action(e); };
    return btn;
  }
}

function whatsappButton(phone) {
  const digits = String(phone || '').replace(/\D/g, '');
  if (!digits) return el('span', { class: 'muted' }, 'لا يوجد رقم');
  return el('a', { class: 'button button-outline', href: `https://wa.me/${digits}`, target: '_blank', rel: 'noopener' }, 'متابعة واتساب');
}

export async function render(container, api) {
  container.replaceChildren();

  let report = {};
  try { report = await api.get('reports/daily') || {}; } catch (error) { toast(error.message, true); }

  const src = report.date ? new Date(report.date + 'T00:00:00') : new Date();
  const months = ["يناير","فبراير","مارس","أبريل","مايو","يونيو","يوليو","أغسطس","سبتمبر","أكتوبر","نوفمبر","ديسمبر"];
  const dateStr = `اليوم هو ${src.getDate()} ${months[src.getMonth()]} ${src.getFullYear()}`;

  let userName = 'الإدارة';
  const nameEl = document.getElementById('user-name');
  if (nameEl && nameEl.textContent) {
    userName = nameEl.textContent;
  }

  // Welcome Section
  const welcome = el('div', { class: 'gdash-welcome' },
    el('h1', {}, `مرحباً بعودتك، ${userName}`),
    el('div', { class: 'gdash-date' }, dateStr)
  );

  // Stats Grid
  const stats = el('div', { class: 'gdash-stats' },
    el('div', { class: 'gstat orange' },
      el('div', { class: 'icon' }, '👥'),
      el('div', { class: 'label' }, 'إجمالي الطلاب'),
      el('div', { class: 'value' }, String(report.students_count || 0))
    ),
    el('div', { class: 'gstat teal' },
      el('div', { class: 'icon' }, '📝'),
      el('div', { class: 'label' }, 'حضور اليوم'),
      el('div', { class: 'value' }, String(report.present_today || 0))
    ),
    el('div', { class: 'gstat blue' },
      el('div', { class: 'icon' }, '🕋'),
      el('div', { class: 'label' }, 'الغياب'),
      el('div', { class: 'value' }, String(report.absent_today || 0))
    ),
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

  // Tools Panel
  const toolsPanel = el('div', { class: 'gdash-tools-panel' });
  TOOLS.forEach(tool => toolsPanel.append(createToolButton(tool)));

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

