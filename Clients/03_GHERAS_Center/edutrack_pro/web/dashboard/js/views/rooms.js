import { el, toast, modal } from '../ui.js';

const GROUP_OPTIONS = [['الصباح', 'الصباح'], ['المساء', 'المساء'], ['الإنجليزي', 'الإنجليزي'], ['القدرات', 'القدرات']];
const WORKING_DAYS = ['السبت', 'الأحد', 'الاثنين', 'الثلاثاء', 'الأربعاء', 'الخميس'];
const SUBJECT_OPTIONS = [['', 'اختر المادة'], ['القرآن', 'القرآن'], ['لغتي', 'لغتي'], ['الإنجليزي', 'الإنجليزي'], ['الرياضيات', 'الرياضيات']];
const DAY_ORDER = new Map([
  ['السبت', 0],
  ['الأحد', 1],
  ['الاثنين', 2],
  ['الثلاثاء', 3],
  ['الأربعاء', 4],
  ['الخميس', 5]
]);

let viewApi = null;
let rooms = [];
let teachers = [];
let staff = [];
let schedules = [];
let roomsBody = null;
let scheduleBody = null;
let roomSelect = null;
let printLink = null;
let currentRoomId = '';

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

function teacherName(teacher) {
  const member = staff.find(item => String(item.id) === String(teacher.staff_id));
  if (member && member.name) return member.role_title ? `${member.name} (${member.role_title})` : member.name;
  return teacher.name || teacher.full_name || teacher.username || `معلم ${teacher.id}`;
}

function teacherLabel(row) {
  if (row.teacher_name) return row.teacher_name;
  const teacher = teachers.find(item => String(item.id) === String(row.teacher_user_id));
  if (!teacher) return '—';
  const member = staff.find(item => String(item.id) === String(teacher.staff_id));
  return member?.name || teacher.name || teacher.username || '—';
}

// -------------------------------------------------------------
// Modern Time Picker (Digital & Analog Clock Face)
// -------------------------------------------------------------

function formatTime12(val24) {
  if (!val24) return '--:--';
  const parts = String(val24).split(':');
  let h = parseInt(parts[0], 10);
  const m = parseInt(parts[1] || '0', 10);
  if (isNaN(h)) return '--:--';
  const period = h >= 12 ? 'م' : 'ص';
  h = h % 12;
  if (h === 0) h = 12;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')} ${period}`;
}

function parseTime24(val24) {
  if (!val24) return { h12: 4, m: 0, period: 'PM' };
  const parts = String(val24).split(':');
  let h = parseInt(parts[0], 10);
  const m = parseInt(parts[1] || '0', 10);
  if (isNaN(h)) return { h12: 4, m: 0, period: 'PM' };
  const period = h >= 12 ? 'PM' : 'AM';
  h = h % 12;
  if (h === 0) h = 12;
  return { h12: h, m: isNaN(m) ? 0 : m, period };
}

function toTime24(h12, m, period) {
  let h = parseInt(h12, 10) % 12;
  if (period === 'PM') h += 12;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

function calcDurationMinutes(start24, end24) {
  if (!start24 || !end24) return null;
  const [sh, sm] = start24.split(':').map(Number);
  const [eh, em] = end24.split(':').map(Number);
  if (isNaN(sh) || isNaN(sm) || isNaN(eh) || isNaN(em)) return null;
  return (eh * 60 + em) - (sh * 60 + sm);
}

function addMinutesToTime24(start24, minutes) {
  if (!start24) return '16:45';
  const [sh, sm] = start24.split(':').map(Number);
  if (isNaN(sh) || isNaN(sm)) return '16:45';
  const total = sh * 60 + sm + minutes;
  const newH = Math.floor(total / 60) % 24;
  const newM = total % 60;
  return `${String(newH).padStart(2, '0')}:${String(newM).padStart(2, '0')}`;
}

function openModernTimePickerModal(title, initial24, onSelect) {
  let { h12, m, period } = parseTime24(initial24 || '16:00');

  const hourDisplay = el('div', {
    style: 'font-size:32px; font-weight:800; color:var(--g-teal-dark); background:#f0fdfa; border:2px solid #ccfbf1; border-radius:10px; padding:6px 14px; min-width:64px; text-align:center;'
  }, String(h12).padStart(2, '0'));

  const minDisplay = el('div', {
    style: 'font-size:32px; font-weight:800; color:var(--g-teal-dark); background:#f0fdfa; border:2px solid #ccfbf1; border-radius:10px; padding:6px 14px; min-width:64px; text-align:center;'
  }, String(m).padStart(2, '0'));

  const amBtn = el('button', {
    type: 'button',
    style: `border:0; padding:8px 14px; border-radius:8px; cursor:pointer; font-weight:700; font-size:13px; transition:all .15s; ${period === 'AM' ? 'background:var(--g-teal); color:#fff;' : 'background:#e2ebed; color:var(--g-muted);'}`
  }, '☀️ صباحاً');

  const pmBtn = el('button', {
    type: 'button',
    style: `border:0; padding:8px 14px; border-radius:8px; cursor:pointer; font-weight:700; font-size:13px; transition:all .15s; ${period === 'PM' ? 'background:var(--g-teal); color:#fff;' : 'background:#e2ebed; color:var(--g-muted);'}`
  }, '🌙 مساءً');

  amBtn.onclick = () => { period = 'AM'; updateState(); };
  pmBtn.onclick = () => { period = 'PM'; updateState(); };

  // SVG Analog Clock Face
  const svgClock = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svgClock.setAttribute('viewBox', '0 0 200 200');
  svgClock.setAttribute('style', 'width:190px; height:190px; display:block; margin:0 auto; filter:drop-shadow(0 2px 8px rgba(0,0,0,0.06));');

  const dialCircle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
  dialCircle.setAttribute('cx', '100');
  dialCircle.setAttribute('cy', '100');
  dialCircle.setAttribute('r', '92');
  dialCircle.setAttribute('fill', '#f8fafc');
  dialCircle.setAttribute('stroke', '#cbd5e1');
  dialCircle.setAttribute('stroke-width', '2');
  svgClock.appendChild(dialCircle);

  const centerDot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
  centerDot.setAttribute('cx', '100');
  centerDot.setAttribute('cy', '100');
  centerDot.setAttribute('r', '5');
  centerDot.setAttribute('fill', '#0f8b8d');

  const hourHand = document.createElementNS('http://www.w3.org/2000/svg', 'line');
  hourHand.setAttribute('x1', '100');
  hourHand.setAttribute('y1', '100');
  hourHand.setAttribute('stroke', '#075f62');
  hourHand.setAttribute('stroke-width', '5');
  hourHand.setAttribute('stroke-linecap', 'round');

  const minHand = document.createElementNS('http://www.w3.org/2000/svg', 'line');
  minHand.setAttribute('x1', '100');
  minHand.setAttribute('y1', '100');
  minHand.setAttribute('stroke', '#0f8b8d');
  minHand.setAttribute('stroke-width', '3');
  minHand.setAttribute('stroke-linecap', 'round');

  const hourNodes = [];
  for (let num = 1; num <= 12; num++) {
    const angleRad = (num * 30) * (Math.PI / 180);
    const tx = 100 + 70 * Math.sin(angleRad);
    const ty = 100 - 70 * Math.cos(angleRad) + 5;

    const textNode = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    textNode.setAttribute('x', String(tx));
    textNode.setAttribute('y', String(ty));
    textNode.setAttribute('text-anchor', 'middle');
    textNode.setAttribute('font-size', '14');
    textNode.setAttribute('font-weight', '700');
    textNode.setAttribute('cursor', 'pointer');
    textNode.textContent = String(num);

    textNode.onclick = () => {
      h12 = num;
      updateState();
    };
    hourNodes.push({ num, node: textNode });
    svgClock.appendChild(textNode);
  }

  svgClock.appendChild(hourHand);
  svgClock.appendChild(minHand);
  svgClock.appendChild(centerDot);

  function updateClockHands() {
    const hAngle = (h12 * 30 + m * 0.5) * (Math.PI / 180);
    const mAngle = (m * 6) * (Math.PI / 180);

    hourHand.setAttribute('x2', String(100 + 44 * Math.sin(hAngle)));
    hourHand.setAttribute('y2', String(100 - 44 * Math.cos(hAngle)));

    minHand.setAttribute('x2', String(100 + 66 * Math.sin(mAngle)));
    minHand.setAttribute('y2', String(100 - 66 * Math.cos(mAngle)));

    hourNodes.forEach(({ num, node }) => {
      if (num === h12) {
        node.setAttribute('fill', '#0f8b8d');
        node.setAttribute('font-size', '16');
      } else {
        node.setAttribute('fill', '#475569');
        node.setAttribute('font-size', '14');
      }
    });
  }

  const confirmBtn = el('button', { class: 'button', type: 'button', style: 'flex:1;' }, 'تأكيد الوقت');
  const cancelBtn = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');

  function updateState() {
    hourDisplay.textContent = String(h12).padStart(2, '0');
    minDisplay.textContent = String(m).padStart(2, '0');
    amBtn.style.background = period === 'AM' ? 'var(--g-teal)' : '#e2ebed';
    amBtn.style.color = period === 'AM' ? '#fff' : 'var(--g-muted)';
    pmBtn.style.background = period === 'PM' ? 'var(--g-teal)' : '#e2ebed';
    pmBtn.style.color = period === 'PM' ? '#fff' : 'var(--g-muted)';
    confirmBtn.textContent = `تأكيد الوقت (${formatTime12(toTime24(h12, m, period))})`;
    updateClockHands();
  }

  const minChipsWrap = el('div', { style: 'display:flex; justify-content:center; gap:6px; margin:12px 0 8px;' },
    ...[0, 15, 30, 45].map(minVal => {
      const b = el('button', {
        type: 'button',
        class: 'button button-outline',
        style: 'padding:5px 10px; font-size:12px; font-weight:700;'
      }, `:${String(minVal).padStart(2, '0')}`);
      b.onclick = () => { m = minVal; updateState(); };
      return b;
    }),
    (() => {
      const stepDown = el('button', { type: 'button', class: 'button button-outline', style: 'padding:5px 8px; font-size:12px;' }, '-5 د');
      stepDown.onclick = () => { m = (m - 5 + 60) % 60; updateState(); };
      return stepDown;
    })(),
    (() => {
      const stepUp = el('button', { type: 'button', class: 'button button-outline', style: 'padding:5px 8px; font-size:12px;' }, '+5 د');
      stepUp.onclick = () => { m = (m + 5) % 60; updateState(); };
      return stepUp;
    })()
  );

  const presets = [
    { label: '08:00 ص', val: '08:00' },
    { label: '09:00 ص', val: '09:00' },
    { label: '10:00 ص', val: '10:00' },
    { label: '03:30 م', val: '15:30' },
    { label: '04:15 م', val: '16:15' },
    { label: '05:00 م', val: '17:00' },
    { label: '06:00 م', val: '18:00' }
  ];

  const presetsWrap = el('div', { style: 'margin:12px 0; border-top:1px dashed var(--g-border); padding-top:10px;' },
    el('div', { style: 'font-size:12px; color:var(--g-muted); margin-bottom:6px;' }, '⚡ أوقات شائعة للحصص بالمركز:'),
    el('div', { style: 'display:flex; gap:6px; flex-wrap:wrap; justify-content:center;' },
      ...presets.map(p => {
        const btn = el('button', {
          type: 'button',
          style: 'border:1px solid #cbd5e1; background:#fff; border-radius:6px; padding:4px 8px; font-size:12px; cursor:pointer; color:#1e293b;'
        }, p.label);
        btn.onclick = () => {
          const parsed = parseTime24(p.val);
          h12 = parsed.h12;
          m = parsed.m;
          period = parsed.period;
          updateState();
        };
        return btn;
      })
    )
  );

  const content = el('div', { style: 'text-align:center; padding:4px;' },
    el('div', { style: 'display:flex; align-items:center; justify-content:center; gap:8px; margin-bottom:12px;' },
      hourDisplay,
      el('span', { style: 'font-size:26px; font-weight:800; color:var(--g-muted);' }, ':'),
      minDisplay,
      el('div', { style: 'display:flex; flex-direction:column; gap:4px; margin-right:6px;' }, amBtn, pmBtn)
    ),
    svgClock,
    minChipsWrap,
    presetsWrap,
    el('div', { class: 'form-actions', style: 'margin-top:14px; display:flex; gap:8px;' }, cancelBtn, confirmBtn)
  );

  const dialog = modal(`⏰ ${title || 'اختيار وقت الحصة'}`, content);
  cancelBtn.onclick = dialog.close;
  confirmBtn.onclick = () => {
    const final24 = toTime24(h12, m, period);
    dialog.close();
    if (onSelect) onSelect(final24);
  };

  updateState();
}

function createModernTimeField(name, labelText, initialVal, onChange) {
  let val24 = initialVal || '';
  const hiddenInput = el('input', { type: 'hidden', name, value: val24 });

  const trigger = el('button', {
    type: 'button',
    style: 'display:flex; justify-content:space-between; align-items:center; width:100%; border:1px solid var(--g-border); border-radius:8px; padding:9px 12px; background:#fff; cursor:pointer; font:inherit; font-size:14px; color:var(--g-text); transition:border-color .15s;'
  },
    el('span', { class: 'time-text', style: 'font-weight:700;' }, formatTime12(val24)),
    el('span', { style: 'font-size:16px; color:var(--g-teal);' }, '🕒')
  );

  trigger.onmouseover = () => { trigger.style.borderColor = 'var(--g-teal)'; };
  trigger.onmouseout = () => { trigger.style.borderColor = 'var(--g-border)'; };

  trigger.onclick = () => {
    openModernTimePickerModal(labelText, hiddenInput.value, newVal24 => {
      val24 = newVal24;
      hiddenInput.value = newVal24;
      trigger.querySelector('.time-text').textContent = formatTime12(newVal24);
      if (onChange) onChange(newVal24);
    });
  };

  const container = el('label', {},
    el('span', {}, labelText),
    hiddenInput,
    trigger
  );

  return {
    node: container,
    getValue: () => hiddenInput.value,
    setValue: newVal24 => {
      val24 = newVal24;
      hiddenInput.value = newVal24;
      trigger.querySelector('.time-text').textContent = formatTime12(newVal24);
      if (onChange) onChange(newVal24);
    }
  };
}

// -------------------------------------------------------------
// View Renderers
// -------------------------------------------------------------

function roomRow(room) {
  const edit = el('button', { class: 'button button-outline', type: 'button' }, 'تعديل');
  edit.addEventListener('click', () => openRoomForm(room));
  return el('tr', {},
    el('td', {}, room.name || '—'),
    el('td', {}, room.group_name || '—'),
    el('td', {}, room.students_count === undefined || room.students_count === null ? '—' : String(room.students_count)),
    el('td', {}, edit)
  );
}

function scheduleRow(row) {
  const delBtn = el('button', {
    class: 'button button-outline',
    type: 'button',
    style: 'padding:3px 8px; font-size:12px; color:#dc2626; border-color:#fca5a5; background:#fef2f2;'
  }, '🗑 حذف');

  delBtn.onclick = async () => {
    if (!confirm(`هل أنت متأكد من حذف حصة ${row.subject || ''} يوم ${row.day || ''}؟`)) return;
    try {
      await viewApi.del(`schedules/${row.id}`);
      toast('تم حذف الحصة بنجاح');
      await loadSchedules();
    } catch (err) {
      toast(err.message || 'فشل حذف الحصة', true);
    }
  };

  return el('tr', {},
    el('td', {}, el('span', { class: 'badge', style: 'font-weight:700;' }, row.day || '—')),
    el('td', {}, formatTime12(row.start_time)),
    el('td', {}, formatTime12(row.end_time)),
    el('td', {}, el('strong', {}, row.subject || '—')),
    el('td', {}, teacherLabel(row)),
    el('td', {}, delBtn)
  );
}

function paintRooms() {
  const rows = rooms.map(roomRow);
  roomsBody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '4', class: 'muted' }, 'لا توجد قاعات بعد'))]));
}

function paintSchedules() {
  const rows = schedules.map(scheduleRow);
  scheduleBody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '6', class: 'muted' }, currentRoomId ? 'لا توجد حصص بعد' : 'أضف قاعة أولا لعرض الجدول'))]));
}

async function loadSchedules() {
  schedules = [];
  if (currentRoomId) {
    try { schedules = toList(await viewApi.get(`schedules?room_id=${currentRoomId}`)); } catch (error) { toast(error.message, true); }
  }
  schedules.sort((a, b) => (DAY_ORDER.get(a.day) ?? 99) - (DAY_ORDER.get(b.day) ?? 99) || String(a.start_time).localeCompare(String(b.start_time)));
  printLink.setAttribute('href', currentRoomId ? `../print/templates/schedule.html?room=${currentRoomId}` : '#');
  paintSchedules();
}

async function reload() {
  try { rooms = toList(await viewApi.get('rooms')); } catch (error) { toast(error.message, true); }
  const [staffList, teacherList] = await Promise.all([
    viewApi.get('staff').then(toList).catch(error => { toast(error.message, true); return []; }),
    viewApi.get('users?role=teacher').then(toList).catch(error => { toast(error.message, true); return []; })
  ]);
  staff = staffList;
  teachers = teacherList;
  roomSelect.replaceChildren(...rooms.map(room => el('option', { value: room.id }, room.name || `قاعة ${room.id}`)));
  if (rooms.length) {
    if (!rooms.some(room => String(room.id) === currentRoomId)) currentRoomId = String(rooms[0].id);
    roomSelect.value = currentRoomId;
  } else {
    currentRoomId = '';
  }
  paintRooms();
  await loadSchedules();
}

function openRoomForm(room = null) {
  const editing = Boolean(room);
  const nameInput = el('input', { name: 'name', type: 'text', required: 'required', value: room?.name ?? '' });
  const groupSelect = select('group_name', GROUP_OPTIONS, room?.group_name || GROUP_OPTIONS[0][0], true);
  const submit = el('button', { class: 'button', type: 'submit' }, editing ? 'حفظ التعديل' : 'إضافة');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' },
    el('label', {}, el('span', {}, 'اسم القاعة'), nameInput),
    el('label', {}, el('span', {}, 'المجموعة'), groupSelect),
    el('div', { class: 'form-actions' }, submit, cancel)
  );
  const dialog = modal(editing ? 'تعديل قاعة' : 'إضافة قاعة', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const body = { name: nameInput.value.trim(), group_name: groupSelect.value };
    if (!body.name) { toast('أدخل اسم القاعة', true); return; }
    try {
      if (editing) await viewApi.patch(`rooms/${room.id}`, body);
      else await viewApi.post('rooms', body);
      dialog.close();
      toast(editing ? 'تم تعديل القاعة' : 'تمت إضافة القاعة');
      await reload();
    } catch (error) { toast(error.message, true); }
  });
}

function openScheduleForm() {
  if (!currentRoomId) { toast('أضف قاعة أولا', true); return; }

  // Default to 3 days (Saturday, Monday, Wednesday)
  const selectedDays = new Set(['السبت', 'الاثنين', 'الأربعاء']);

  const chipsContainer = el('div', { style: 'display:flex; gap:6px; flex-wrap:wrap; margin-top:6px;' });
  const dayButtons = new Map();

  const dayCountBadge = el('span', { class: 'badge', style: 'font-size:12px; margin-right:8px;' });

  function updateDayButtons() {
    WORKING_DAYS.forEach(day => {
      const btn = dayButtons.get(day);
      if (!btn) return;
      const isActive = selectedDays.has(day);
      btn.style.background = isActive ? 'var(--g-teal)' : '#fff';
      btn.style.color = isActive ? '#fff' : 'var(--g-teal)';
      btn.style.borderColor = 'var(--g-teal)';
      btn.innerHTML = isActive ? `✓ ${day}` : day;
    });
    dayCountBadge.textContent = `${selectedDays.size} أيام محددة`;
  }

  WORKING_DAYS.forEach(day => {
    const btn = el('button', {
      type: 'button',
      class: 'button button-outline',
      style: 'flex:1; min-width:70px; padding:8px 4px; font-size:13px; font-weight:700; border-radius:8px;'
    }, day);
    btn.onclick = () => {
      if (selectedDays.has(day)) selectedDays.delete(day);
      else selectedDays.add(day);
      updateDayButtons();
    };
    dayButtons.set(day, btn);
    chipsContainer.append(btn);
  });

  const presetsBar = el('div', { style: 'display:flex; gap:6px; flex-wrap:wrap; margin-top:8px;' },
    (() => {
      const b = el('button', { type: 'button', style: 'border:1px solid #cbd5e1; background:#f8fafc; border-radius:6px; padding:4px 8px; font-size:11px; cursor:pointer;' }, '⚡ سبت - اثنين - أربعاء');
      b.onclick = () => {
        selectedDays.clear();
        selectedDays.add('السبت');
        selectedDays.add('الاثنين');
        selectedDays.add('الأربعاء');
        updateDayButtons();
      };
      return b;
    })(),
    (() => {
      const b = el('button', { type: 'button', style: 'border:1px solid #cbd5e1; background:#f8fafc; border-radius:6px; padding:4px 8px; font-size:11px; cursor:pointer;' }, '⚡ أحد - ثلاثاء - خميس');
      b.onclick = () => {
        selectedDays.clear();
        selectedDays.add('الأحد');
        selectedDays.add('الثلاثاء');
        selectedDays.add('الخميس');
        updateDayButtons();
      };
      return b;
    })(),
    (() => {
      const b = el('button', { type: 'button', style: 'border:1px solid #cbd5e1; background:#f8fafc; border-radius:6px; padding:4px 8px; font-size:11px; cursor:pointer;' }, '⚡ طوال الأسبوع (السبت-الخميس)');
      b.onclick = () => {
        WORKING_DAYS.forEach(d => selectedDays.add(d));
        updateDayButtons();
      };
      return b;
    })(),
    (() => {
      const b = el('button', { type: 'button', style: 'border:1px solid #cbd5e1; background:#f8fafc; border-radius:6px; padding:4px 8px; font-size:11px; cursor:pointer;' }, 'تفريغ');
      b.onclick = () => {
        selectedDays.clear();
        updateDayButtons();
      };
      return b;
    })()
  );

  const durationBadge = el('div', {
    style: 'grid-column: 1 / -1; background:#f0fdfa; border:1px solid #ccfbf1; border-radius:8px; padding:8px 12px; font-size:13px; color:var(--g-teal-dark); display:flex; align-items:center; gap:6px;'
  }, '⏳ مدة الحصة المقترحة: 45 دقيقة');

  function refreshDuration() {
    const s = startField.getValue();
    const e = endField.getValue();
    const diff = calcDurationMinutes(s, e);
    if (diff !== null) {
      if (diff > 0) {
        durationBadge.textContent = `⏳ مدة الحصة: ${diff} دقيقة`;
        durationBadge.style.background = '#f0fdfa';
        durationBadge.style.color = 'var(--g-teal-dark)';
      } else {
        durationBadge.textContent = `⚠️ تنبيه: وقت النهاية يجب أن يكون بعد وقت البداية`;
        durationBadge.style.background = '#fef2f2';
        durationBadge.style.color = '#dc2626';
      }
    }
  }

  const startField = createModernTimeField('start_time', 'وقت البدء (من)', '16:00', newStart => {
    const currentEnd = endField.getValue();
    if (!currentEnd || currentEnd <= newStart) {
      endField.setValue(addMinutesToTime24(newStart, 45));
    }
    refreshDuration();
  });

  const endField = createModernTimeField('end_time', 'وقت الانتهاء (إلى)', '16:45', () => {
    refreshDuration();
  });

  const subjectSelect = select('subject', SUBJECT_OPTIONS, SUBJECT_OPTIONS[1][0], true);
  const teacherSelect = select('teacher_user_id', [['', 'اختر المعلم'], ...teachers.map(teacher => [teacher.id, teacherName(teacher)])], '', true);
  const submit = el('button', { class: 'button', type: 'submit' }, 'إضافة الحصة');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');

  const form = el('form', { class: 'form-grid' },
    el('div', { style: 'grid-column: 1 / -1;' },
      el('div', { style: 'display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;' },
        el('span', { style: 'font-weight:700;' }, 'تحديد أيام الحصة (يمكنك اختيار أكثر من يوم):'),
        dayCountBadge
      ),
      chipsContainer,
      presetsBar
    ),
    startField.node,
    endField.node,
    durationBadge,
    el('label', {}, el('span', {}, 'المادة'), subjectSelect),
    el('label', {}, el('span', {}, 'المعلم'), teacherSelect),
    el('div', { class: 'form-actions', style: 'grid-column: 1 / -1;' }, submit, cancel)
  );

  const dialog = modal('📅 إضافة حصة جديدة للجدول الدراسي', form);
  cancel.addEventListener('click', dialog.close);

  updateDayButtons();
  refreshDuration();

  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (!selectedDays.size) {
      toast('يرجى اختيار يوم واحد على الأقل للحصة', true);
      return;
    }
    const sTime = startField.getValue();
    const eTime = endField.getValue();
    if (!sTime || !eTime) {
      toast('يرجى تحديد وقت البدء والانتهاء', true);
      return;
    }
    if (eTime <= sTime) {
      toast('وقت نهاية الحصة يجب أن يكون بعد وقت البدء', true);
      return;
    }

    const daysList = Array.from(selectedDays).sort((a, b) => (DAY_ORDER.get(a) ?? 99) - (DAY_ORDER.get(b) ?? 99));
    submit.disabled = true;
    submit.textContent = 'جاري جدولة الحصص...';

    // One request per day; a partial failure must not leave the table stale or
    // let a retry re-post the days that already succeeded.
    const results = await Promise.allSettled(daysList.map(day => viewApi.post('schedules', {
      room_id: currentRoomId,
      day,
      start_time: sTime,
      end_time: eTime,
      subject: subjectSelect.value,
      teacher_user_id: teacherSelect.value
    })));
    const failed = daysList.filter((_, i) => results[i].status === 'rejected');
    await loadSchedules();
    if (!failed.length) {
      dialog.close();
      toast(`تمت جدولة الحصة بنجاح لـ ${daysList.length} أيام (${daysList.join('، ')})`);
      return;
    }
    const firstError = results.find(r => r.status === 'rejected')?.reason;
    toast(`تعذر جدولة ${failed.join('، ')}: ${firstError?.message || 'فشلت إضافة الحصة'}`, true);
    failed.forEach(day => selectedDays.add(day));
    daysList.filter(day => !failed.includes(day)).forEach(day => selectedDays.delete(day));
    updateDayButtons();
    submit.disabled = false;
    submit.textContent = 'إضافة الحصة';
  });
}

export async function render(container, api) {
  viewApi = api;
  rooms = [];
  teachers = [];
  staff = [];
  schedules = [];
  currentRoomId = '';

  const addRoom = el('button', { class: 'button', type: 'button' }, 'إضافة قاعة');
  const addLesson = el('button', { class: 'button', type: 'button' }, 'إضافة حصة');
  roomSelect = el('select', { name: 'room_id', 'aria-label': 'القاعة' });
  printLink = el('a', { class: 'button button-outline', href: '#', target: '_blank', rel: 'noopener' }, 'طباعة الجدول');
  roomsBody = el('tbody');
  scheduleBody = el('tbody');

  const roomsSection = el('section', { class: 'panel' },
    el('div', { class: 'panel-head' }, el('h2', {}, 'القاعات'), addRoom),
    el('table', { class: 'table' },
      el('thead', {}, el('tr', {},
        el('th', {}, 'الاسم'),
        el('th', {}, 'المجموعة'),
        el('th', {}, 'عدد الطلاب'),
        el('th', {}, 'إجراءات')
      )),
      roomsBody
    )
  );

  const scheduleSection = el('section', { class: 'panel' },
    el('div', { class: 'panel-head' },
      el('h2', {}, 'الجدول الدراسي'),
      el('div', { class: 'panel-tools' }, roomSelect, addLesson, printLink)
    ),
    el('table', { class: 'table' },
      el('thead', {}, el('tr', {},
        el('th', {}, 'اليوم'),
        el('th', {}, 'من'),
        el('th', {}, 'إلى'),
        el('th', {}, 'المادة'),
        el('th', {}, 'المعلم'),
        el('th', {}, 'إجراءات')
      )),
      scheduleBody
    )
  );

  container.replaceChildren(roomsSection, scheduleSection);

  addRoom.addEventListener('click', () => openRoomForm());
  addLesson.addEventListener('click', () => openScheduleForm());
  roomSelect.addEventListener('change', async () => {
    currentRoomId = roomSelect.value;
    await loadSchedules();
  });
  printLink.addEventListener('click', event => {
    if (!currentRoomId) { event.preventDefault(); toast('أضف قاعة أولا', true); }
  });

  await reload();
}

