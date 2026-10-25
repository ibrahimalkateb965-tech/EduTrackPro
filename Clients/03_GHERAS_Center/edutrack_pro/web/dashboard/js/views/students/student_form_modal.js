import { el, toast, modal, createDatePicker } from '../../ui.js';
import { openDeleteStudentModal, isGirl, getAllGroups, openAddGroupModal } from './student_modals.js?v=3.5';

const GUARDIAN_RELATIONS = ['الأب', 'الأم', 'ولي الأمر', 'شخص آخر'];
const YES_NO = [['false', 'لا'], ['true', 'نعم']];
const INTERVAL_OPTIONS = [[30, 'شهري (كل 30 يوم)'], [14, 'كل 14 يوم'], [7, 'أسبوعي (كل 7 أيام)']];

function isoTomorrow() {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function select(name, options, selected) {
  const node = el('select', { name });
  options.forEach(([optionValue, optionLabel]) => node.append(el('option', { value: optionValue }, optionLabel)));
  node.value = selected === undefined || selected === null ? '' : String(selected);
  return node;
}

export function openStudentForm(api, student, options = {}) {
  const { rooms = [], activeTab = 'all', onReload = null, studentsList = [], onGroupAdded = null } = options;
  const editing = Boolean(student && student.id);
  const value = key => (student && student[key] !== undefined && student[key] !== null) ? String(student[key]) : '';
  const boolValue = key => String(student ? student[key] === true : false);
  const roomOptions = [['', 'بدون فصل']].concat(rooms.map(room => [room.id, room.name]));
  const relationOptions = GUARDIAN_RELATIONS.map(relation => [relation, relation]);

  let defaultGroup = '';
  let defaultGender = 'بنين';
  if (activeTab === 'english_boys') {
    defaultGroup = 'الإنجليزي';
    defaultGender = 'بنين';
  } else if (activeTab === 'english_girls') {
    defaultGroup = 'الإنجليزي';
    defaultGender = 'بنات';
  } else if (activeTab === 'english_all') {
    defaultGroup = 'الإنجليزي';
    defaultGender = 'بنين';
  } else if (activeTab === 'morning') {
    defaultGroup = 'الصباح';
  } else if (activeTab === 'evening') {
    defaultGroup = 'المساء';
  } else if (activeTab === 'qudrat') {
    defaultGroup = 'القدرات';
  }

  const allGroups = getAllGroups(studentsList);
  let groupSelect = select('group_name', [['', 'اختر المجموعة']].concat(allGroups.map(g => [g, g])), value('group_name') || defaultGroup);

  const addGroupInlineBtn = el('button', {
    type: 'button',
    class: 'button button-outline',
    style: 'padding: 2px 8px; font-size: 11px; margin-right: 6px; color: #0284c7; border-color: #bae6fd; font-weight:600;',
    title: 'إضافة مجموعة دراسية جديدة'
  }, '+ مجموعة جديدة');

  addGroupInlineBtn.onclick = () => {
    openAddGroupModal(api, newName => {
      const updated = getAllGroups(studentsList);
      groupSelect.replaceChildren(
        el('option', { value: '' }, 'اختر المجموعة'),
        ...updated.map(g => el('option', { value: g }, g))
      );
      groupSelect.value = newName;
      if (typeof onGroupAdded === 'function') onGroupAdded(newName);
    });
  };

  const groupField = el('label', {},
    el('div', { style: 'display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;' },
      el('span', { style: 'font-weight:600;' }, 'المجموعة الدراسية *'),
      addGroupInlineBtn
    ),
    groupSelect
  );

  // --- 📷 صورة الطالب: تُرفع إلى مخزن خاص على الخادم بعد حفظ الطالب (لا تُحفظ داخل بيانات الطالب) ---
  const photoState = { blob: null, remove: false, objectUrl: null };
  const fileInput = el('input', { type: 'file', accept: 'image/png, image/jpeg, image/webp', style: 'display:none;' });

  const previewImg = el('img', {
    alt: 'صورة الطالب',
    style: 'width:60px; height:60px; border-radius:10px; object-fit:cover; border:2px solid #0284c7; box-shadow:0 2px 6px rgba(0,0,0,0.08); display:none;'
  });

  const previewPlaceholder = el('div', {
    style: 'width:60px; height:60px; border-radius:10px; background:#f1f5f9; border:2px dashed #cbd5e1; display:flex; align-items:center; justify-content:center; font-size:26px; color:#94a3b8;'
  }, '📷');

  const uploadBtn = el('button', {
    type: 'button',
    class: 'button button-outline',
    style: 'display:inline-flex; align-items:center; gap:6px; padding:7px 12px; font-size:12px; font-weight:600; color:#0284c7; border-color:#38bdf8; background:#f0f9ff;'
  }, '📁 إدراج صورة من الجهاز (كمبيوتر / لابتوب)');

  const clearPhotoBtn = el('button', {
    type: 'button',
    class: 'button button-outline',
    style: 'padding:4px 8px; font-size:11px; color:#ef4444; border-color:#fecaca; background:#fff; display:none;'
  }, 'حذف الصورة');

  const showPreview = url => {
    if (photoState.objectUrl) URL.revokeObjectURL(photoState.objectUrl);
    photoState.objectUrl = url || null;
    previewImg.src = url || '';
    previewImg.style.display = url ? 'block' : 'none';
    previewPlaceholder.style.display = url ? 'none' : 'flex';
    clearPhotoBtn.style.display = url ? 'inline-block' : 'none';
  };

  if (editing && student.photo_url) {
    api.fetchBlobUrl(`students/${student.id}/photo`).then(showPreview).catch(() => showPreview(null));
  }

  uploadBtn.onclick = () => fileInput.click();

  clearPhotoBtn.onclick = () => {
    photoState.blob = null;
    photoState.remove = true;
    fileInput.value = '';
    showPreview(null);
    toast('سيتم حذف صورة الطالب عند الحفظ');
  };

  fileInput.onchange = () => {
    const file = fileInput.files && fileInput.files[0];
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      toast('يرجى اختيار ملف صورة صالح (JPEG, PNG, WebP)', true);
      return;
    }
    const sourceUrl = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(sourceUrl);
      const maxDim = 600;
      let w = img.width;
      let h = img.height;
      if (w > maxDim || h > maxDim) {
        if (w > h) { h = Math.round((h * maxDim) / w); w = maxDim; } else { w = Math.round((w * maxDim) / h); h = maxDim; }
      }
      const canvas = document.createElement('canvas');
      canvas.width = w;
      canvas.height = h;
      canvas.getContext('2d').drawImage(img, 0, 0, w, h);
      canvas.toBlob(blob => {
        if (!blob) { toast('تعذر معالجة ملف الصورة', true); return; }
        photoState.blob = blob;
        photoState.remove = false;
        showPreview(URL.createObjectURL(blob));
        toast(`تم تجهيز صورة الطالب: ${file.name}`);
      }, 'image/jpeg', 0.85);
    };
    img.onerror = () => {
      URL.revokeObjectURL(sourceUrl);
      toast('تعذر معالجة ملف الصورة', true);
    };
    img.src = sourceUrl;
  };

  const photoPickerField = el('div', { style: 'grid-column: 1 / -1; background:#f8fafc; border:1px solid #e2e8f0; border-radius:10px; padding:12px; margin-top:4px;' },
    el('label', { style: 'display:block; font-weight:600; font-size:13px; margin-bottom:8px; color:#334155;' }, '📷 صورة الطالب الشخصية (من الكمبيوتر أو اللابتوب)'),
    el('div', { style: 'display:flex; align-items:center; gap:14px; flex-wrap:wrap;' },
      previewPlaceholder,
      previewImg,
      el('div', { style: 'display:flex; flex-direction:column; gap:6px;' },
        el('div', { style: 'display:flex; gap:8px; align-items:center; flex-wrap:wrap;' },
          uploadBtn,
          clearPhotoBtn
        ),
        el('span', { class: 'muted', style: 'font-size:11px;' }, 'يدعم JPG و PNG و WebP، وتُصغَّر تلقائيًا وتظهر في البطاقة والتقرير والشهادة')
      )
    ),
    fileInput
  );

  const feeInputAttrs = { name: 'registration_fee', type: 'number', step: '0.01', min: '0', placeholder: 'مثال: 500', value: value('registration_fee') };
  const paidInputAttrs = { name: 'initial_payment_amount', type: 'number', step: '0.01', min: '0', placeholder: 'مثال: 200', value: value('initial_payment_amount') };
  if (editing) {
    feeInputAttrs.readonly = 'readonly';
    feeInputAttrs.style = 'background: #f1f5f9; color: #64748b; cursor: not-allowed; border-color: #cbd5e1;';
    feeInputAttrs.title = 'رسوم التسجيل محددة في الخطة المالية';
    paidInputAttrs.readonly = 'readonly';
    paidInputAttrs.style = 'background: #f1f5f9; color: #64748b; cursor: not-allowed; border-color: #cbd5e1;';
    paidInputAttrs.title = 'الدفعة الأولى محددة في السند الأصلي';
  }
  const feeInput = el('input', feeInputAttrs);
  const paidInput = el('input', paidInputAttrs);
  const methodSelect = select('payment_method', [
    ['كاش', 'كاش نقدي'],
    ['مدى', 'مدى / شبكة'],
    ['تحويل بنكي', 'تحويل بنكي'],
    ['Apple Pay', 'Apple Pay'],
    ['تابي', 'تابي (Tabby)'],
    ['تمارا', 'تمارا (Tamara)']
  ], 'كاش');
  if (editing) methodSelect.disabled = true;

  // اشتراك الباص المدرسي
  const hasBusSelect = select('has_bus_subscription', YES_NO, boolValue('has_bus_subscription'));
  const busFeeAttrs = { name: 'bus_fee', type: 'number', step: '0.01', min: '0', placeholder: 'مثال: 500', value: value('bus_fee') };
  const busPaidAttrs = { name: 'bus_paid', type: 'number', step: '0.01', min: '0', placeholder: 'مثال: 200', value: value('bus_paid') };
  const busFeeInput = el('input', busFeeAttrs);
  const busPaidInput = el('input', busPaidAttrs);
  const busRemainingPreview = el('span', { id: 'bus-remaining-preview', style: 'font-weight: 700; color: #0f766e;' }, '0.00 ر.س');

  const updateBusRemaining = () => {
    const fee = parseFloat(busFeeInput.value) || 0;
    const paid = parseFloat(busPaidInput.value) || 0;
    const remaining = Math.max(0, fee - paid);
    busRemainingPreview.textContent = `${remaining.toFixed(2)} ر.س`;
  };

  busFeeInput.addEventListener('input', updateBusRemaining);
  busPaidInput.addEventListener('input', updateBusRemaining);
  updateBusRemaining();

  const busFieldsContainer = el('div', {
    style: `grid-column: 1 / -1; display: ${boolValue('has_bus_subscription') === 'true' ? 'grid' : 'none'}; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 10px; background: #f0fdfa; border: 1px solid #99f6e4; border-radius: 8px; padding: 12px; margin-top: 4px;`
  },
    el('label', {}, el('span', { style: 'font-weight:600;' }, 'رسوم اشتراك الباص (ر.س)'), busFeeInput),
    el('label', {}, el('span', { style: 'font-weight:600;' }, 'الدفعة المسددة للباص (ر.س)'), busPaidInput),
    el('div', { style: 'display: flex; flex-direction: column; justify-content: center; padding: 4px;' },
      el('span', { style: 'font-size: 12px; color: #64748b; font-weight: 600;' }, 'المتبقي من اشتراك الباص:'),
      busRemainingPreview
    )
  );

  hasBusSelect.addEventListener('change', () => {
    const active = hasBusSelect.value === 'true';
    busFieldsContainer.style.display = active ? 'grid' : 'none';
    if (!active) {
      busFeeInput.value = '';
      busPaidInput.value = '';
      updateBusRemaining();
    }
  });

  // ملف الحساسية للطفل
  const hasAllergiesSelect = select('has_allergies', YES_NO, boolValue('has_allergies'));
  const allergyNotesTextarea = el('textarea', { name: 'allergy_notes', placeholder: 'يرجى توضيح نوع الحساسية (غذائية أو دوائية)...' }, value('allergy_notes'));
  const allergyNotesField = el('label', { style: `grid-column: 1 / -1; display: ${boolValue('has_allergies') === 'true' ? 'grid' : 'none'};` },
    el('span', { style: 'font-weight:600;' }, 'تفاصيل الحساسية (غذائية أو دوائية) *'),
    allergyNotesTextarea
  );
  hasAllergiesSelect.addEventListener('change', () => {
    allergyNotesField.style.display = hasAllergiesSelect.value === 'true' ? 'grid' : 'none';
  });

  // خطة أقساط الرسوم (تظهر فقط عند الإنشاء مع رسوم متبقية غير مسددة)
  let countInput = null;
  let dateInput = null;
  let intervalSelect = null;
  let datePickerWrap = null;
  const planInputs = [];
  if (!editing) {
    countInput = el('input', { name: 'installments_count', type: 'number', min: '1', step: '1', value: '1' });
    datePickerWrap = createDatePicker({
      name: 'next_due_date',
      label: 'تاريخ الدفعة القادمة',
      minDate: isoTomorrow(),
      minYear: new Date().getFullYear(),
      maxYear: new Date().getFullYear() + 2,
      showAge: false
    });
    dateInput = datePickerWrap.querySelector('input[name="next_due_date"]');
    intervalSelect = select('interval_days', INTERVAL_OPTIONS, 30);
    planInputs.push(
      el('label', {}, el('span', {}, 'عدد الدفعات المتبقية'), countInput),
      datePickerWrap,
      el('label', {}, el('span', {}, 'الفاصل بين الدفعات'), intervalSelect)
    );
  }
  const planContainer = el('div', { style: 'grid-column: 1 / -1; display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px;' }, ...planInputs);
  const planSummary = el('div', { style: 'grid-column: 1 / -1; font-size: 13px; font-weight: 600; display: none;' });

  const fields = [
    // --- 1. البيانات الأساسية والأكاديمية ---
    el('div', { class: 'form-section-title', style: 'grid-column: 1 / -1; font-weight: bold; margin: 4px 0 6px; padding-bottom: 4px; border-bottom: 1.5px solid var(--border, #e2e8f0); color: var(--primary, #0284c7); display: flex; align-items: center; gap: 6px;' }, '👤 البيانات الأساسية والأكاديمية'),

    el('label', {}, el('span', { style: 'font-weight:600;' }, 'اسم الطالب *'), el('input', { name: 'name', type: 'text', required: 'required', placeholder: 'الاسم الثلاثي أو الرباعي', value: value('name') })),

    el('label', { style: 'background: rgba(14, 165, 233, 0.08); padding: 6px 10px; border-radius: 8px; border: 1.5px solid rgba(14, 165, 233, 0.3);' }, 
      el('span', { style: 'font-weight: 700; color: #0369a1; display: flex; align-items: center; gap: 4px;' }, '⚥ تحديد الجنس (بنين / بنات) *'), 
      select('gender', [['بنين', '👦 بنين (أولاد)'], ['بنات', '👧 بنات']], value('gender') || (editing ? (isGirl(student || {}) ? 'بنات' : 'بنين') : defaultGender))
    ),

    groupField,
    el('label', {}, el('span', {}, 'الفصل / القاعة'), select('room_id', roomOptions, value('room_id'))),
    el('label', {}, el('span', {}, 'رقم الهوية الوطنية / الإقامة'), el('input', { name: 'national_id', type: 'text', value: value('national_id') })),
    createDatePicker({
      name: 'birth_date',
      value: value('birth_date'),
      label: 'تاريخ الميلاد',
      minYear: 1970,
      maxYear: new Date().getFullYear(),
      showAge: true
    }),
    el('label', {}, el('span', {}, 'الجنسية'), el('input', { name: 'nationality', type: 'text', value: value('nationality') || 'سعودي' })),
    photoPickerField,

    // --- 2. بيانات ولي الأمر والتواصل ---
    el('div', { class: 'form-section-title', style: 'grid-column: 1 / -1; font-weight: bold; margin: 12px 0 6px; padding-bottom: 4px; border-bottom: 1.5px solid var(--border, #e2e8f0); color: var(--primary, #0284c7); display: flex; align-items: center; gap: 6px;' }, '📞 بيانات ولي الأمر والتواصل'),

    el('label', {}, el('span', { style: 'font-weight:600;' }, 'هاتف ولي الأمر *'), el('input', { name: 'guardian_phone', type: 'text', required: 'required', value: value('guardian_phone') })),
    el('label', {}, el('span', {}, 'علاقة ولي الأمر'), select('guardian_relation', relationOptions, value('guardian_relation') || 'الأب')),
    el('label', {}, el('span', {}, 'اسم الأب'), el('input', { name: 'father_name', type: 'text', value: value('father_name') })),
    el('label', {}, el('span', {}, 'هاتف الأب'), el('input', { name: 'father_phone', type: 'text', value: value('father_phone') })),
    el('label', {}, el('span', {}, 'اسم الأم'), el('input', { name: 'mother_name', type: 'text', value: value('mother_name') })),
    el('label', {}, el('span', {}, 'هاتف الأم'), el('input', { name: 'mother_phone', type: 'text', value: value('mother_phone') })),
    el('label', {}, el('span', {}, 'طريقة الاستلام'), el('input', { name: 'pickup_type', type: 'text', value: value('pickup_type') })),
    el('label', {}, el('span', {}, 'اسم المستلم'), el('input', { name: 'pickup_name', type: 'text', value: value('pickup_name') })),
    el('label', {}, el('span', {}, 'علاقة المستلم'), el('input', { name: 'pickup_relation', type: 'text', value: value('pickup_relation') })),
    el('label', {}, el('span', {}, 'هاتف المستلم'), el('input', { name: 'pickup_phone', type: 'text', value: value('pickup_phone') })),

    // --- 3. البيانات التعليمية والملاحظات ---
    el('div', { class: 'form-section-title', style: 'grid-column: 1 / -1; font-weight: bold; margin: 12px 0 6px; padding-bottom: 4px; border-bottom: 1.5px solid var(--border, #e2e8f0); color: var(--primary, #0284c7); display: flex; align-items: center; gap: 6px;' }, '📝 البيانات التعليمية والملاحظات'),

    el('label', {}, el('span', {}, 'دراسة سابقة'), select('previous_study', YES_NO, boolValue('previous_study'))),
    el('label', {}, el('span', {}, 'المدرسة السابقة'), el('input', { name: 'previous_school', type: 'text', value: value('previous_school') })),
    el('label', {}, el('span', {}, 'المستوى السابق'), el('input', { name: 'previous_level', type: 'text', value: value('previous_level') })),
    el('label', {}, el('span', {}, 'صعوبات تعلم'), select('has_difficulties', YES_NO, boolValue('has_difficulties'))),
    el('label', {}, el('span', {}, 'تفاصيل الصعوبات'), el('textarea', { name: 'difficulty_notes' }, value('difficulty_notes'))),
    el('label', {}, el('span', {}, 'يشكو الطفل من حساسية؟'), hasAllergiesSelect),
    allergyNotesField,
    el('label', {}, el('span', {}, 'ملاحظات عن الطالب'), el('textarea', { name: 'child_notes' }, value('child_notes'))),
    el('label', {}, el('span', {}, 'ملاحظات تعليمية'), el('textarea', { name: 'education_notes' }, value('education_notes'))),

    // --- 4. الرسوم والتسجيل المالي الفوري (أتمتة الدفعة الأولى) ---
    el('div', { class: 'form-section-title', style: 'grid-column: 1 / -1; font-weight: bold; margin: 12px 0 6px; padding-bottom: 4px; border-bottom: 1.5px solid var(--border, #e2e8f0); color: #059669; display: flex; align-items: center; gap: 6px;' }, '💰 الرسوم والتسجيل المالي الفوري'),

    el('label', {}, el('span', {}, 'رسوم التسجيل المقررة (ر.س)'), feeInput),
    el('label', {}, el('span', {}, 'الدفعة الأولى المسددة الآن (ر.س)'), paidInput),
    el('label', {}, el('span', {}, 'طريقة سداد الدفعة الأولى'), methodSelect),

    el('label', {}, el('span', {}, 'الاشتراك في الباص'), hasBusSelect),
    busFieldsContainer,

    planContainer,
    planSummary
  ];

  const submit = el('button', { class: 'button', type: 'button', style: 'padding: 10px 24px; font-weight: 700;' }, editing ? 'حفظ التعديلات' : 'إضافة طالب');
  const cancel = el('button', { class: 'button button-outline', type: 'button' }, 'إلغاء');

  const deleteStudentBtn = editing ? el('button', {
    class: 'button button-outline',
    type: 'button',
    style: 'color: #dc2626; border-color: #fca5a5; background: #fef2f2; margin-inline-start: auto; padding: 10px 16px; font-weight: 600;',
    title: 'حذف هذا الطالب نهائياً من النظام'
  }, '🗑️ حذف الطالب') : null;

  const actionsBar = el('div', { class: 'form-actions', style: 'grid-column: 1 / -1; display: flex; gap: 10px; margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--border, #e2e8f0); align-items: center;' },
    submit,
    cancel,
    ...(deleteStudentBtn ? [deleteStudentBtn] : [])
  );
  const form = el('form', { class: 'form-grid', novalidate: 'novalidate' }, ...fields, actionsBar);
  const dialog = modal(editing ? 'تعديل بيانات الطالب' : 'إضافة طالب جديد', form);
  cancel.addEventListener('click', dialog.close);
  cancel.addEventListener('click', () => { if (photoState.objectUrl) URL.revokeObjectURL(photoState.objectUrl); });

  if (deleteStudentBtn) {
    deleteStudentBtn.addEventListener('click', () => {
      openDeleteStudentModal(api, student, async () => {
        dialog.close();
        if (typeof onReload === 'function') await onReload();
      });
    });
  }

  const updatePlanSummary = () => {
    if (editing) return;
    const fee = Number(feeInput.value);
    const paid = paidInput.value === '' ? 0 : Number(paidInput.value);
    if (!fee || fee <= 0) {
      planContainer.style.display = 'none';
      planSummary.style.display = 'none';
      submit.disabled = false;
      return;
    }
    planSummary.style.display = '';
    if (paid > fee) {
      planContainer.style.display = 'none';
      planSummary.textContent = '⚠️ المبلغ المسدد أكبر من رسوم التسجيل المقررة';
      planSummary.style.color = '#b91c1c';
      submit.disabled = true;
      return;
    }
    submit.disabled = false;
    if (paid === fee) {
      planContainer.style.display = 'none';
      planSummary.textContent = '✅ سيتم تسجيل الرسوم مسددة بالكامل';
      planSummary.style.color = '#166534';
      return;
    }
    planContainer.style.display = '';
    const count = Number(countInput && countInput.value) || 1;
    const each = ((fee - paid) / count).toFixed(2);
    planSummary.style.color = '#1e40af';
    planSummary.textContent = `📅 المتبقي ${(fee - paid).toFixed(2)} ر.س على ${count} دفعات، قيمة الدفعة تقريبًا ${each} ر.س، أولها بتاريخ ⁦${(dateInput && dateInput.value) || '—'}⁩`; // LTR isolate keeps YYYY-MM-DD in order
  };

  if (!editing) {
    [feeInput, paidInput, countInput, dateInput].forEach(node => {
      node.addEventListener('input', updatePlanSummary);
      node.addEventListener('change', updatePlanSummary);
    });
    intervalSelect.addEventListener('change', updatePlanSummary);
  }
  updatePlanSummary();

  const saveStudent = async () => {
    const rawData = new FormData(form);
    const payload = Object.fromEntries(rawData.entries());
    Object.keys(payload).forEach(key => {
      payload[key] = typeof payload[key] === 'string' ? payload[key].trim() : payload[key];
    });

    if (!payload.name) {
      toast('يرجى كتابة اسم الطالب *', true);
      const nameInput = form.querySelector('input[name="name"]');
      if (nameInput) nameInput.focus();
      return;
    }

    if (!payload.guardian_phone) {
      toast('يرجى إدخال هاتف ولي الأمر *', true);
      const phoneInput = form.querySelector('input[name="guardian_phone"]');
      if (phoneInput) phoneInput.focus();
      return;
    }

    payload.has_difficulties = payload.has_difficulties === 'true';
    payload.previous_study = payload.previous_study === 'true';
    payload.room_id = payload.room_id || null;
    payload.gender = payload.gender || (editing ? (isGirl(student || {}) ? 'بنات' : 'بنين') : defaultGender);

    payload.has_allergies = payload.has_allergies === 'true';
    if (payload.has_allergies) {
      payload.allergy_notes = payload.allergy_notes ? payload.allergy_notes.trim() : '';
    } else {
      payload.allergy_notes = null;
    }

    payload.has_bus_subscription = payload.has_bus_subscription === 'true';
    if (payload.has_bus_subscription) {
      payload.bus_fee = payload.bus_fee === '' ? 0 : Number(payload.bus_fee);
      payload.bus_paid = payload.bus_paid === '' ? 0 : Number(payload.bus_paid);
    } else {
      payload.bus_fee = 0;
      payload.bus_paid = 0;
    }

    // PostgreSQL nullable & check constraint guarantees
    ['birth_date', 'group_name', 'guardian_relation', 'national_id', 'father_phone', 'mother_phone', 'pickup_phone', 'registration_fee', 'initial_payment_amount'].forEach(k => {
      if (payload[k] === '') payload[k] = null;
    });

    // حقول خطة الأقساط تُرسل فقط عند الإنشاء مع رسوم متبقية غير مسددة
    const planFee = Number(feeInput.value);
    const planPaid = paidInput.value === '' ? 0 : Number(paidInput.value);
    if (!editing && planFee > 0 && planPaid < planFee) {
      payload.installments_count = Number(payload.installments_count) || 1;
      payload.interval_days = Number(payload.interval_days) || 30;
    } else {
      delete payload.installments_count;
      delete payload.next_due_date;
      delete payload.interval_days;
    }

    submit.disabled = true;
    submit.textContent = 'جاري الحفظ...';

    try {
      let createdStudent = null;
      if (editing) {
        await api.patch(`students/${student.id}`, payload);
      } else {
        createdStudent = await api.post('students', payload);
      }
      const savedId = editing ? student.id : createdStudent?.id;
      let photoFailed = false;
      if (savedId && (photoState.blob || (editing && photoState.remove))) {
        try {
          if (photoState.blob) await api.upload(`students/${savedId}/photo`, photoState.blob);
          else await api.del(`students/${savedId}/photo`);
        } catch (photoError) {
          console.error('Student photo failed:', photoError);
          photoFailed = true;
        }
      }
      if (photoState.objectUrl) URL.revokeObjectURL(photoState.objectUrl);
      dialog.close();
      if (photoFailed) toast('تم حفظ الطالب، لكن تعذر رفع الصورة. أعد المحاولة من نافذة التعديل', true);
      else toast(editing ? 'تم تحديث بيانات الطالب بنجاح' : 'تمت إضافة الطالب بنجاح');
      if (typeof onReload === 'function') await onReload();

      // Automated redirect to Receipt Print Page if an initial payment was recorded
      if (!editing && createdStudent) {
        const paymentId = createdStudent.initial_payment?.id || createdStudent.initial_receipt?.payment_id;
        const initialAmount = parseFloat(payload.initial_payment_amount || payload.registration_fee || '0');
        const busAmount = parseFloat(payload.bus_paid || '0');
        if (paymentId && (initialAmount > 0 || busAmount > 0)) {
          toast('تم تسجيل الطالب وسند القبض، جاري التحويل لطباعة السند...');
          setTimeout(() => {
            window.location.href = `../print/templates/receipt.html?payment=${encodeURIComponent(paymentId)}`;
          }, 600);
        }
      }
    } catch (error) {
      console.error('Save student failed:', error);
      toast(error.message || 'تعذر حفظ بيانات الطالب', true);
      submit.disabled = false;
      submit.textContent = editing ? 'حفظ التعديلات' : 'إضافة طالب';
    }
  };

  form.addEventListener('submit', event => {
    event.preventDefault();
    saveStudent();
  });

  submit.addEventListener('click', event => {
    event.preventDefault();
    saveStudent();
  });
}
