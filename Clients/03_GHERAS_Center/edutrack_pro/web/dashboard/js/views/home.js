import { el, toast, fmtSAR } from '../ui.js';

const KPIS = [
  { key: 'students_count', label: 'إجمالي الطلاب', money: false },
  { key: 'present_today', label: 'حضور اليوم', money: false },
  { key: 'absent_today', label: 'غياب اليوم', money: false },
  { key: 'collected_today', label: 'تحصيل اليوم', money: true },
  { key: 'outstanding_total', label: 'المستحقات المتبقية', money: true },
  { key: 'expenses_month', label: 'مصروفات الشهر', money: true }
];

function whatsappButton(phone) {
  const digits = String(phone || '').replace(/\D/g, '');
  if (!digits) return el('span', { class: 'muted' }, 'لا يوجد رقم');
  return el('a', { class: 'button button-outline', href: `https://wa.me/${digits}`, target: '_blank', rel: 'noopener' }, 'متابعة واتساب');
}

export async function render(container, api) {
  container.replaceChildren();
  container.append(el('div', { class: 'view-header' }, el('h1', {}, 'الرئيسية')));
  let report = {};
  try { report = await api.get('reports/daily') || {}; } catch (error) { toast(error.message, true); }
  container.append(el('div', { class: 'kpis' }, KPIS.map(kpi => el('div', { class: 'kpi' },
    el('strong', {}, kpi.money ? fmtSAR(report[kpi.key]) : String(report[kpi.key] ?? 0)),
    el('span', { class: 'muted' }, kpi.label)
  ))));
  const absences = Array.isArray(report.absences) ? report.absences : Array.isArray(report?.absent_students) ? report.absent_students : [];
  const rows = absences.map(item => el('tr', {},
    el('td', {}, item.student_name || item.name || '—'),
    el('td', {}, item.group_name || '—'),
    el('td', {}, item.guardian_phone || item.phone || '—'),
    el('td', {}, whatsappButton(item.guardian_phone || item.phone))
  ));
  container.append(el('div', { class: 'card' },
    el('h2', {}, 'غياب اليوم'),
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {},
        el('th', {}, 'الطالب'),
        el('th', {}, 'المجموعة'),
        el('th', {}, 'هاتف ولي الأمر'),
        el('th', {}, 'المتابعة')
      )),
      el('tbody', {}, rows.length ? rows : el('tr', {}, el('td', { colspan: '4', class: 'muted' }, 'لا يوجد غياب اليوم')))
    ))
  ));
}
