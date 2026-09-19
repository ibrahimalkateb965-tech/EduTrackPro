export function el(tag, attributes = {}, ...children) { const node = document.createElement(tag); Object.entries(attributes).forEach(([key, value]) => { if (key === 'class') node.className = value; else if (key.startsWith('on')) node.addEventListener(key.slice(2), value); else node.setAttribute(key, value); }); node.append(...children.flat().filter(Boolean).map(value => typeof value === 'string' ? document.createTextNode(value) : value)); return node; }
export function toast(message, isError = false) { const node = el('div', { class: `toast${isError ? ' error' : ''}` }, message); document.body.append(node); setTimeout(() => node.remove(), 3500); }
export function modal(title, content) { const backdrop = el('div', { class: 'modal-backdrop', role: 'dialog', 'aria-modal': 'true' }); const close = () => backdrop.remove(); const panel = el('section', { class: 'modal-panel' }, el('div', { class: 'modal-head' }, el('h2', {}, title), el('button', { class: 'button modal-close', type: 'button', onclick: close, 'aria-label': 'إغلاق' }, '×')), content); backdrop.append(panel); backdrop.addEventListener('click', event => { if (event.target === backdrop) close(); }); document.body.append(backdrop); return { close, panel }; }
export function fmtSAR(value) { return `${Number(value || 0).toFixed(2)} ر.س`; }
export function fmtDate(value) { if (!value) return '—'; return new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value)); }

export const ARABIC_MONTHS = [
  'يناير', 'فبراير', 'مارس', 'أبريل', 'مايو', 'يونيو',
  'يوليو', 'أغسطس', 'سبتمبر', 'أكتوبر', 'نوفمبر', 'ديسمبر'
];
export const ARABIC_WEEKDAYS = ['أحد', 'إثنين', 'ثلاثاء', 'أربعاء', 'خميس', 'جمعة', 'سبت'];

export function calculateAge(birthDateStr) {
  if (!birthDateStr) return null;
  const parts = String(birthDateStr).split('-').map(Number);
  if (parts.length !== 3 || isNaN(parts[0])) return null;
  const [bYear, bMonth, bDay] = parts;
  const now = new Date();
  let years = now.getFullYear() - bYear;
  let months = (now.getMonth() + 1) - bMonth;
  let days = now.getDate() - bDay;
  if (days < 0) {
    months -= 1;
  }
  if (months < 0) {
    years -= 1;
    months += 12;
  }
  return { years: Math.max(0, years), months: Math.max(0, months) };
}

export function createDatePicker({ name = 'birth_date', value = '', label = 'تاريخ الميلاد', minYear = 1970, maxYear = new Date().getFullYear(), showAge = true } = {}) {
  const wrap = el('div', { class: 'g-datepicker-wrap' });
  const hiddenInput = el('input', { type: 'hidden', name, value: value || '' });
  const displayInput = el('input', {
    type: 'text',
    class: 'g-datepicker-input',
    readonly: 'readonly',
    placeholder: 'اختر التاريخ من التقويم...',
    style: 'cursor: pointer; background: #fff;'
  });

  const calIcon = el('span', { class: 'g-datepicker-icon' }, '📅');
  const clearBtn = el('button', {
    type: 'button',
    class: 'g-datepicker-clear',
    style: value ? 'display: inline-flex;' : 'display: none;',
    title: 'مسح التاريخ'
  }, '✕');

  const ageBadge = el('span', { class: 'g-datepicker-age', style: 'display: none;' });

  function updateView(isoStr) {
    hiddenInput.value = isoStr || '';
    if (!isoStr) {
      displayInput.value = '';
      clearBtn.style.display = 'none';
      ageBadge.style.display = 'none';
      ageBadge.textContent = '';
      return;
    }
    const [y, m, d] = isoStr.split('-').map(Number);
    if (y && m && d) {
      const monthName = ARABIC_MONTHS[m - 1] || '';
      displayInput.value = `${d} ${monthName} ${y}`;
      clearBtn.style.display = 'inline-flex';
      if (showAge) {
        const age = calculateAge(isoStr);
        if (age) {
          ageBadge.textContent = age.years > 0
            ? `🎂 العمر: ${age.years} سنة${age.months > 0 ? ` و ${age.months} شهر` : ''}`
            : `🎂 العمر: ${age.months} شهر`;
          ageBadge.style.display = 'inline-block';
        }
      }
    }
  }

  updateView(value);

  const popover = el('div', { class: 'g-cal-popover', style: 'display: none;' });

  let viewYear = maxYear;
  let viewMonth = new Date().getMonth();
  if (value) {
    const parts = value.split('-').map(Number);
    if (parts.length === 3 && parts[0] >= minYear && parts[0] <= maxYear) {
      viewYear = parts[0];
      viewMonth = parts[1] - 1;
    }
  }

  const prevBtn = el('button', { type: 'button', class: 'g-cal-nav-btn', title: 'الشهر التالي' }, '‹');
  const nextBtn = el('button', { type: 'button', class: 'g-cal-nav-btn', title: 'الشهر السابق' }, '›');

  const monthSelect = el('select', { class: 'g-cal-select' });
  ARABIC_MONTHS.forEach((mName, idx) => {
    monthSelect.append(el('option', { value: String(idx) }, `${idx + 1} - ${mName}`));
  });

  const yearSelect = el('select', { class: 'g-cal-select' });
  for (let y = maxYear; y >= minYear; y--) {
    yearSelect.append(el('option', { value: String(y) }, String(y)));
  }

  const header = el('div', { class: 'g-cal-header' },
    nextBtn,
    el('div', { class: 'g-cal-selectors' }, monthSelect, yearSelect),
    prevBtn
  );

  const weekdaysRow = el('div', { class: 'g-cal-weekdays' },
    ...ARABIC_WEEKDAYS.map(w => el('div', { class: 'g-cal-weekday' }, w))
  );

  const daysGrid = el('div', { class: 'g-cal-grid' });

  const todayBtn = el('button', { type: 'button', class: 'button button-outline g-cal-action-btn' }, 'اليوم');
  const clearActionBtn = el('button', { type: 'button', class: 'button button-outline g-cal-action-btn' }, 'مسح');
  const closeActionBtn = el('button', { type: 'button', class: 'button g-cal-action-btn' }, 'إغلاق');
  const footer = el('div', { class: 'g-cal-footer' }, todayBtn, clearActionBtn, closeActionBtn);

  popover.append(header, weekdaysRow, daysGrid, footer);

  function renderGrid() {
    monthSelect.value = String(viewMonth);
    yearSelect.value = String(viewYear);
    daysGrid.replaceChildren();

    const firstDayIndex = new Date(viewYear, viewMonth, 1).getDay();
    const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate();
    const prevMonthDays = new Date(viewYear, viewMonth, 0).getDate();

    for (let i = 0; i < firstDayIndex; i++) {
      const dNum = prevMonthDays - firstDayIndex + 1 + i;
      daysGrid.append(el('div', { class: 'g-cal-day muted' }, String(dNum)));
    }

    const today = new Date();
    const todayStr = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
    const selectedIso = hiddenInput.value;

    for (let d = 1; d <= daysInMonth; d++) {
      const thisIso = `${viewYear}-${String(viewMonth + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
      const isSelected = selectedIso === thisIso;
      const isToday = todayStr === thisIso;

      let dayClass = 'g-cal-day';
      if (isSelected) dayClass += ' selected';
      if (isToday) dayClass += ' today';

      const dayCell = el('button', { type: 'button', class: dayClass }, String(d));
      dayCell.addEventListener('click', e => {
        e.stopPropagation();
        e.preventDefault();
        updateView(thisIso);
        closePopover();
      });
      daysGrid.append(dayCell);
    }
  }

  function openPopover() {
    if (hiddenInput.value) {
      const [y, m] = hiddenInput.value.split('-').map(Number);
      if (y && m) {
        viewYear = y;
        viewMonth = m - 1;
      }
    }
    renderGrid();
    popover.style.display = 'block';
  }

  function closePopover() {
    popover.style.display = 'none';
  }

  nextBtn.addEventListener('click', e => {
    e.stopPropagation();
    e.preventDefault();
    viewMonth--;
    if (viewMonth < 0) {
      viewMonth = 11;
      viewYear--;
    }
    renderGrid();
  });

  prevBtn.addEventListener('click', e => {
    e.stopPropagation();
    e.preventDefault();
    viewMonth++;
    if (viewMonth > 11) {
      viewMonth = 0;
      viewYear++;
    }
    renderGrid();
  });

  monthSelect.addEventListener('change', e => {
    e.stopPropagation();
    viewMonth = parseInt(monthSelect.value, 10);
    renderGrid();
  });

  yearSelect.addEventListener('change', e => {
    e.stopPropagation();
    viewYear = parseInt(yearSelect.value, 10);
    renderGrid();
  });

  todayBtn.addEventListener('click', e => {
    e.stopPropagation();
    e.preventDefault();
    const now = new Date();
    const iso = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    updateView(iso);
    closePopover();
  });

  clearActionBtn.addEventListener('click', e => {
    e.stopPropagation();
    e.preventDefault();
    updateView('');
    closePopover();
  });

  closeActionBtn.addEventListener('click', e => {
    e.stopPropagation();
    e.preventDefault();
    closePopover();
  });

  clearBtn.addEventListener('click', e => {
    e.stopPropagation();
    e.preventDefault();
    updateView('');
  });

  displayInput.addEventListener('click', e => {
    e.stopPropagation();
    e.preventDefault();
    if (popover.style.display === 'block') closePopover();
    else openPopover();
  });

  calIcon.addEventListener('click', e => {
    e.stopPropagation();
    e.preventDefault();
    if (popover.style.display === 'block') closePopover();
    else openPopover();
  });

  document.addEventListener('click', e => {
    if (!wrap.contains(e.target)) {
      closePopover();
    }
  });

  const inputRow = el('div', { class: 'g-datepicker-input-wrap' },
    calIcon,
    displayInput,
    clearBtn
  );

  const labelRow = el('div', { style: 'display: flex; justify-content: space-between; align-items: center;' },
    el('span', {}, label),
    ageBadge
  );

  const labelNode = el('label', { class: 'g-datepicker-label' },
    labelRow,
    inputRow,
    hiddenInput,
    popover
  );

  wrap.append(labelNode);
  return wrap;
}
