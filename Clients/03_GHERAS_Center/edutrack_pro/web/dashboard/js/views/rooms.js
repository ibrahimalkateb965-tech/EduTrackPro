import { el, toast, modal } from '../ui.js';

const GROUP_OPTIONS = [['الصباح', 'الصباح'], ['المساء', 'المساء'], ['الإنجليزي', 'الإنجليزي'], ['القدرات', 'القدرات']];
const DAY_OPTIONS = [['', 'اختر اليوم'], ['الأحد', 'الأحد'], ['الاثنين', 'الاثنين'], ['الثلاثاء', 'الثلاثاء'], ['الأربعاء', 'الأربعاء'], ['الخميس', 'الخميس']];
const SUBJECT_OPTIONS = [['', 'اختر المادة'], ['القرآن', 'القرآن'], ['لغتي', 'لغتي'], ['الإنجليزي', 'الإنجليزي'], ['الرياضيات', 'الرياضيات']];
const DAY_ORDER = new Map([['الأحد', 0], ['الاثنين', 1], ['الثلاثاء', 2], ['الأربعاء', 3], ['الخميس', 4]]);

let viewApi = null;
let rooms = [];
let teachers = [];
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
  return teacher.name || teacher.full_name || teacher.username || `معلم ${teacher.id}`;
}

function teacherLabel(row) {
  if (row.teacher_name) return row.teacher_name;
  const teacher = teachers.find(item => String(item.id) === String(row.teacher_user_id));
  return teacher ? teacherName(teacher) : '—';
}

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
  return el('tr', {},
    el('td', {}, row.day || '—'),
    el('td', {}, row.start_time || '—'),
    el('td', {}, row.end_time || '—'),
    el('td', {}, row.subject || '—'),
    el('td', {}, teacherLabel(row))
  );
}

function paintRooms() {
  const rows = rooms.map(roomRow);
  roomsBody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '4', class: 'muted' }, 'لا توجد قاعات بعد'))]));
}

function paintSchedules() {
  const rows = schedules.map(scheduleRow);
  scheduleBody.replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colspan: '5', class: 'muted' }, currentRoomId ? 'لا توجد حصص بعد' : 'أضف قاعة أولا لعرض الجدول'))]));
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
  try { teachers = toList(await viewApi.get('users?role=teacher')); } catch (error) { toast(error.message, true); }
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
  const daySelect = select('day', DAY_OPTIONS, '', true);
  const startInput = el('input', { name: 'start_time', type: 'time', required: 'required' });
  const endInput = el('input', { name: 'end_time', type: 'time', required: 'required' });
  const subjectSelect = select('subject', SUBJECT_OPTIONS, '', true);
  const teacherSelect = select('teacher_user_id', [['', 'اختر المعلم'], ...teachers.map(teacher => [teacher.id, teacherName(teacher)])], '', true);
  const submit = el('button', { class: 'button', type: 'submit' }, 'إضافة');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');
  const form = el('form', { class: 'form-grid' },
    el('label', {}, el('span', {}, 'اليوم'), daySelect),
    el('label', {}, el('span', {}, 'من'), startInput),
    el('label', {}, el('span', {}, 'إلى'), endInput),
    el('label', {}, el('span', {}, 'المادة'), subjectSelect),
    el('label', {}, el('span', {}, 'المعلم'), teacherSelect),
    el('div', { class: 'form-actions' }, submit, cancel)
  );
  const dialog = modal('إضافة حصة', form);
  cancel.addEventListener('click', dialog.close);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (startInput.value && endInput.value && endInput.value < startInput.value) { toast('وقت النهاية قبل وقت البداية', true); return; }
    const body = { room_id: currentRoomId, day: daySelect.value, start_time: startInput.value, end_time: endInput.value, subject: subjectSelect.value, teacher_user_id: teacherSelect.value };
    try {
      await viewApi.post('schedules', body);
      dialog.close();
      toast('تمت إضافة الحصة');
      await loadSchedules();
    } catch (error) { toast(error.message, true); }
  });
}

export async function render(container, api) {
  viewApi = api;
  rooms = [];
  teachers = [];
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
        el('th', {}, 'المعلم')
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
