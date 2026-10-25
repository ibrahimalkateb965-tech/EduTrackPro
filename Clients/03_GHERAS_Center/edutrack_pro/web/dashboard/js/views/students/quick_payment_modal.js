import { el, toast, modal, fmtSAR, fmtDate } from '../../ui.js';

export async function openQuickPaymentModal(api, student, onReload) {
  const container = el('div', { style: 'min-width:320px; max-width:640px;' },
    el('div', { style: 'text-align:center; padding:36px 16px; color:#64748b; font-size:14px;' },
      el('div', { style: 'display:inline-block; margin-bottom:10px; font-size:26px;' }, '⏳'),
      el('div', { style: 'font-weight:600;' }, 'جاري تحميل بيانات الرسوم والأقساط للطالب...')
    )
  );

  const dialog = modal(`سند قبض وتحصيل الرسوم — ${student.name}`, container);
  if (dialog && dialog.panel) {
    dialog.panel.style.maxWidth = '720px';
    const head = dialog.panel.querySelector('.modal-head');
    if (head) {
      head.style.position = 'relative';
      head.style.display = 'flex';
      head.style.justifyContent = 'center';
      head.style.alignItems = 'center';
      const h2 = head.querySelector('h2');
      if (h2) {
        h2.style.textAlign = 'center';
        h2.style.width = '100%';
        h2.style.fontSize = '18px';
      }
      const closeBtn = head.querySelector('.modal-close');
      if (closeBtn) {
        closeBtn.style.position = 'absolute';
        closeBtn.style.left = '0';
        closeBtn.style.top = '50%';
        closeBtn.style.transform = 'translateY(-50%)';
      }
    }
  }

  let profile = null;
  try {
    profile = await api.get('reports/student/' + student.id);
  } catch (err) {
    container.replaceChildren(
      el('div', { style: 'padding:16px; background:#fee2e2; border-radius:8px; color:#991b1b; font-size:13px; text-align:center;' },
        'تعذر تحميل بيانات الرسوم: ' + (err.message || 'خطأ في الاتصال'),
        el('div', { style: 'margin-top:12px;' },
          el('button', { class: 'button', type: 'button', onclick: dialog.close }, 'إغلاق')
        )
      )
    );
    return;
  }

  const todayStr = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Riyadh' }).format(new Date());
  const stData = profile.student || student;
  const balance = profile.balance || { total_planned: 0, total_paid: 0, outstanding: 0 };
  const tuitionPlanned = Number(balance.total_planned || 0);
  const tuitionPaid = Number(balance.total_paid || 0);
  const tuitionOutstanding = Math.max(0, Number(balance.outstanding !== undefined ? balance.outstanding : (tuitionPlanned - tuitionPaid)));

  const hasBus = Boolean(stData.has_bus_subscription);
  const busFee = hasBus ? Number(stData.bus_fee || 0) : 0;
  const busPaid = hasBus ? Number(stData.bus_paid || 0) : 0;
  const busRemaining = hasBus ? Math.max(0, Number(stData.bus_remaining !== undefined ? stData.bus_remaining : (busFee - busPaid))) : 0;

  const totalOutstanding = tuitionOutstanding + busRemaining;

  const rawInstallments = profile.installments || [];
  const installments = rawInstallments.map(inst => {
    const amt = Number(inst.amount || 0);
    const paid = Number(inst.paid_amount || 0);
    const rem = Math.max(0, amt - paid);
    return { ...inst, amountNum: amt, paidNum: paid, remainingNum: rem };
  });
  const unpaidInstallments = installments.filter(inst => inst.remainingNum > 0);

  // 1. Summary Cards
  const summaryGrid = el('div', {
    style: 'display:grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap:10px; margin-bottom:14px; text-align:center;'
  },
    // Tuition Card
    el('div', {
      style: `padding:10px 12px; border-radius:8px; border:1px solid ${tuitionOutstanding > 0 ? '#fed7aa' : '#bbf7d0'}; background:${tuitionOutstanding > 0 ? '#fff7ed' : '#f0fdf4'}; text-align:center;`
    },
      el('div', { style: 'font-size:12px; font-weight:700; color:#475569; display:flex; justify-content:center; align-items:center; gap:6px;' },
        el('span', {}, '🎓 الرسوم الدراسية'),
        tuitionOutstanding <= 0 ? el('span', { style: 'color:#15803d; font-size:11px;' }, '✓ مسدد بالكامل') : null
      ),
      el('div', { style: 'font-size:18px; font-weight:800; color:' + (tuitionOutstanding > 0 ? '#c2410c' : '#15803d') + '; margin:4px 0; text-align:center;' },
        fmtSAR(tuitionOutstanding)
      ),
      el('div', { style: 'font-size:11px; color:#64748b; text-align:center;' },
        `المسدد: ${fmtSAR(tuitionPaid)} / الإجمالي: ${fmtSAR(tuitionPlanned)}`
      )
    ),

    // Bus Card
    el('div', {
      style: `padding:10px 12px; border-radius:8px; border:1px solid ${hasBus ? (busRemaining > 0 ? '#fed7aa' : '#bbf7d0') : '#e2e8f0'}; background:${hasBus ? (busRemaining > 0 ? '#fff7ed' : '#f0fdf4') : '#f8fafc'}; text-align:center;`
    },
      el('div', { style: 'font-size:12px; font-weight:700; color:#475569; display:flex; justify-content:center; align-items:center; gap:6px;' },
        el('span', {}, '🚌 رسوم الباص والنقل'),
        hasBus ? (busRemaining <= 0 ? el('span', { style: 'color:#15803d; font-size:11px;' }, '✓ مسدد بالكامل') : null) : el('span', { style: 'color:#b45309; font-size:11px; background:#fef3c7; padding:1px 6px; border-radius:4px;' }, 'غير مشترك')
      ),
      el('div', { style: 'font-size:18px; font-weight:800; color:' + (hasBus ? (busRemaining > 0 ? '#c2410c' : '#15803d') : '#64748b') + '; margin:4px 0; text-align:center;' },
        hasBus ? fmtSAR(busRemaining) : '0.00 ر.س'
      ),
      el('div', { style: 'font-size:11px; color:#64748b; text-align:center;' },
        hasBus ? `المسدد: ${fmtSAR(busPaid)} / الإجمالي: ${fmtSAR(busFee)}` :
        el('button', {
          type: 'button',
          class: 'button button-outline',
          style: 'padding:2px 10px; font-size:11px; font-weight:700; color:#047857; border-color:#10b981; background:#ffffff; margin-top:4px; display:inline-block;',
          title: 'تفعيل اشتراك جديد وسداد الرسوم',
          onclick: () => {
            purposeSelect.value = 'bus_new';
            applyPurposeUI();
            newBusFeeInput.focus();
          }
        }, '+ تفعيل الاشتراك وسداد الرسوم')
      )
    ),

    // Total Remaining Card
    el('div', {
      style: `padding:10px 12px; border-radius:8px; border:1px solid ${totalOutstanding > 0 ? '#fca5a5' : '#bbf7d0'}; background:${totalOutstanding > 0 ? '#fef2f2' : '#f0fdf4'}; text-align:center;'`
    },
      el('div', { style: 'font-size:12px; font-weight:700; color:#475569; text-align:center;' }, '💰 إجمالي المتبقي الكلي'),
      el('div', { style: 'font-size:18px; font-weight:800; color:' + (totalOutstanding > 0 ? '#b91c1c' : '#15803d') + '; margin:4px 0; text-align:center;' },
        fmtSAR(totalOutstanding)
      ),
      el('div', { style: 'font-size:11px; color:#64748b; text-align:center;' },
        totalOutstanding > 0 ? 'مجموع المتبقي للدراسة والباص' : 'تم سداد جميع الرسوم المقررة'
      )
    )
  );

  // 2. Installments Table
  let installmentsSection = null;
  if (installments.length > 0) {
    const tableRows = installments.map(inst => {
      const isPaid = inst.remainingNum <= 0;
      const isPartial = inst.paidNum > 0 && inst.remainingNum > 0;
      const badgeStyle = isPaid
        ? 'background:#dcfce7; color:#15803d;'
        : (isPartial ? 'background:#fef3c7; color:#b45309;' : 'background:#fee2e2; color:#b91c1c;');
      const statusText = isPaid ? 'مسدد' : (isPartial ? 'سداد جزئي' : 'مستحق');

      const selectBtn = !isPaid ? el('button', {
        class: 'button button-outline',
        type: 'button',
        style: 'padding:2px 8px; font-size:11px; font-weight:600; color:#047857; border-color:#10b981; background:#ffffff; margin:0 auto;',
        onclick: () => selectInstallment(inst)
      }, 'سداد هذا القسط') : el('span', { style: 'color:#94a3b8; font-size:11px;' }, '✓');

      return el('tr', { style: 'border-bottom:1px solid #f1f5f9; font-size:12px; text-align:center;' },
        el('td', { style: 'padding:6px 8px; font-weight:600; text-align:center;' }, `القسط ${inst.seq_no}`),
        el('td', { style: 'padding:6px 8px; color:#64748b; text-align:center;' }, fmtDate(inst.due_date)),
        el('td', { style: 'padding:6px 8px; text-align:center;' }, fmtSAR(inst.amountNum)),
        el('td', { style: 'padding:6px 8px; font-weight:700; color:' + (inst.remainingNum > 0 ? '#c2410c' : '#15803d') + '; text-align:center;' }, fmtSAR(inst.remainingNum)),
        el('td', { style: 'padding:6px 8px; text-align:center;' },
          el('span', { style: `display:inline-block; padding:2px 6px; border-radius:4px; font-size:10px; font-weight:700; ${badgeStyle}` }, statusText)
        ),
        el('td', { style: 'padding:6px 8px; text-align:center;' }, selectBtn)
      );
    });

    installmentsSection = el('div', {
      style: 'margin-bottom:14px; border:1px solid #e2e8f0; border-radius:8px; overflow:hidden; background:#ffffff; text-align:center;'
    },
      el('div', { style: 'background:#f8fafc; padding:6px 12px; font-size:12px; font-weight:700; color:#334155; border-bottom:1px solid #e2e8f0; display:flex; justify-content:space-between; align-items:center;' },
        el('span', {}, '📋 جدول الأقساط الدراسية المقررة'),
        el('span', { style: 'font-size:11px; font-weight:500; color:#64748b;' }, `عدد الأقساط: ${installments.length}`)
      ),
      el('div', { style: 'max-height:130px; overflow-y:auto;' },
        el('table', { style: 'width:100%; text-align:center; border-collapse:collapse;' },
          el('thead', { style: 'background:#f1f5f9; font-size:11px; color:#475569;' },
            el('tr', { style: 'text-align:center;' },
              el('th', { style: 'padding:6px 8px; text-align:center;' }, 'القسط'),
              el('th', { style: 'padding:6px 8px; text-align:center;' }, 'الاستحقاق'),
              el('th', { style: 'padding:6px 8px; text-align:center;' }, 'المبلغ'),
              el('th', { style: 'padding:6px 8px; text-align:center;' }, 'المتبقي'),
              el('th', { style: 'padding:6px 8px; text-align:center;' }, 'الحالة'),
              el('th', { style: 'padding:6px 8px; text-align:center;' }, 'إجراء')
            )
          ),
          el('tbody', {}, ...tableRows)
        )
      )
    );
  }

  // 3. Form Controls
  const purposeOptions = [];
  if (tuitionOutstanding > 0) {
    purposeOptions.push(el('option', { value: 'tuition' }, `🎓 سداد رسوم دراسية / أقساط (متبقي: ${fmtSAR(tuitionOutstanding)})`));
  } else {
    purposeOptions.push(el('option', { value: 'tuition' }, '🎓 الرسوم الدراسية مسددة بالكامل (0.00 ر.س)'));
  }

  if (hasBus && busRemaining > 0) {
    purposeOptions.push(el('option', { value: 'bus' }, `🚌 سداد رسوم الباص والنقل (متبقي: ${fmtSAR(busRemaining)})`));
  } else if (!hasBus) {
    purposeOptions.push(el('option', { value: 'bus_new' }, '🚌 تفعيل اشتراك جديد في الباص وسداد الرسوم'));
  } else if (hasBus && busRemaining <= 0) {
    purposeOptions.push(el('option', { value: 'bus_new' }, '🚌 تجديد اشتراك الباص / سداد رسوم جديدة'));
  }

  if (tuitionOutstanding > 0) {
    if (hasBus && busRemaining > 0) {
      purposeOptions.push(el('option', { value: 'combined' }, '🔄 سداد مشترك (رسوم دراسية + باص)'));
    } else {
      purposeOptions.push(el('option', { value: 'combined_new' }, '🔄 سداد مشترك (دراسة + تفعيل اشتراك باص جديد)'));
    }
  }

  const purposeSelect = el('select', {
    style: 'width:100%; padding:8px 10px; border-radius:8px; border:1px solid #cbd5e1; font-weight:600; background:#fff; font-size:13px; text-align:center; text-align-last:center;'
  }, ...purposeOptions);

  const installmentSelect = el('select', {
    style: 'width:100%; padding:8px 10px; border-radius:8px; border:1px solid #cbd5e1; font-size:13px; background:#fff; text-align:center; text-align-last:center;'
  },
    el('option', { value: '' }, 'توزيع تلقائي على أقرب قسط مستحق'),
    ...unpaidInstallments.map(inst =>
      el('option', { value: inst.id }, `القسط ${inst.seq_no} — استحقاق ${fmtDate(inst.due_date)} (متبقي: ${fmtSAR(inst.remainingNum)})`)
    )
  );

  const installmentRow = el('div', { style: 'margin-bottom:10px; text-align:center;' },
    el('label', { style: 'display:block; font-size:12px; font-weight:700; margin-bottom:4px; color:#334155; text-align:center;' }, 'تحديد القسط المستهدف'),
    installmentSelect
  );

  // Single Amount Input (Tuition or Bus)
  const singleAmountLabel = el('label', { style: 'display:block; font-size:12px; font-weight:700; margin-bottom:4px; color:#334155; text-align:center;' }, 'المبلغ المسدد (ر.س) *');
  const singleAmountInput = el('input', {
    type: 'number',
    step: '0.01',
    min: '0.01',
    placeholder: 'مثال: 500',
    required: 'required',
    style: 'width:100%; padding:8px; border-radius:8px; border:1px solid #cbd5e1; font-size:14px; font-weight:600; text-align:center;'
  });
  const singleAmountRow = el('div', { style: 'margin-bottom:10px; text-align:center;' },
    singleAmountLabel,
    singleAmountInput
  );

  // New Bus Subscription Inputs
  const newBusFeeInput = el('input', {
    type: 'number',
    step: '0.01',
    min: '0.01',
    placeholder: 'مثال: 800',
    style: 'width:100%; padding:8px; border-radius:8px; border:1px solid #cbd5e1; font-size:13px; font-weight:600; text-align:center;'
  });

  const newBusPaidInput = el('input', {
    type: 'number',
    step: '0.01',
    min: '0.01',
    placeholder: 'المسدد حالياً (كامل أو دفعة أولى)',
    style: 'width:100%; padding:8px; border-radius:8px; border:1px solid #cbd5e1; font-size:13px; font-weight:600; text-align:center;'
  });

  newBusFeeInput.addEventListener('input', () => {
    if (!newBusPaidInput.value || parseFloat(newBusPaidInput.value) === parseFloat(newBusFeeInput.value)) {
      newBusPaidInput.value = newBusFeeInput.value;
    }
  });

  const newBusRow = el('div', {
    style: 'display:none; margin-bottom:10px; background:#ecfdf5; border:1px solid #a7f3d0; padding:10px; border-radius:8px; text-align:center;'
  },
    el('div', { style: 'font-size:12px; font-weight:700; color:#065f46; margin-bottom:8px;' }, '🚌 إعداد وتفعيل اشتراك الباص والنقل'),
    el('div', { style: 'display:grid; grid-template-columns: 1fr 1fr; gap:10px;' },
      el('div', {},
        el('label', { style: 'display:block; font-size:11px; font-weight:700; margin-bottom:4px; color:#334155; text-align:center;' }, 'رسوم الاشتراك المقررة (ر.س) *'),
        newBusFeeInput
      ),
      el('div', {},
        el('label', { style: 'display:block; font-size:11px; font-weight:700; margin-bottom:4px; color:#334155; text-align:center;' }, 'المبلغ المسدد حالياً (ر.س) *'),
        newBusPaidInput
      )
    )
  );

  // Combined Amount Inputs
  const combinedTuitionInput = el('input', {
    type: 'number',
    step: '0.01',
    min: '0.00',
    placeholder: '0.00',
    style: 'width:100%; padding:8px; border-radius:8px; border:1px solid #cbd5e1; font-size:13px; font-weight:600; text-align:center;'
  });
  const combinedBusInput = el('input', {
    type: 'number',
    step: '0.01',
    min: '0.00',
    placeholder: '0.00',
    style: 'width:100%; padding:8px; border-radius:8px; border:1px solid #cbd5e1; font-size:13px; font-weight:600; text-align:center;'
  });
  const combinedTotalPreview = el('div', {
    style: 'padding:8px 12px; background:#f8fafc; border:1px solid #e2e8f0; border-radius:8px; font-weight:700; color:#047857; font-size:14px; text-align:center;'
  }, '0.00 ر.س');

  const combinedRow = el('div', { style: 'display:none; margin-bottom:10px; background:#f8fafc; padding:10px; border-radius:8px; border:1px solid #e2e8f0; text-align:center;' },
    el('div', { style: 'display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:8px;' },
      el('div', {},
        el('label', { style: 'display:block; font-size:11px; font-weight:700; margin-bottom:4px; color:#334155; text-align:center;' }, 'مبلغ الرسوم الدراسية (ر.س)'),
        combinedTuitionInput
      ),
      el('div', {},
        el('label', { style: 'display:block; font-size:11px; font-weight:700; margin-bottom:4px; color:#334155; text-align:center;' }, 'مبلغ رسوم الباص (ر.س)'),
        combinedBusInput
      )
    ),
    el('div', { style: 'display:flex; justify-content:center; align-items:center; gap:8px;' },
      el('span', { style: 'font-size:12px; font-weight:700; color:#475569;' }, 'الإجمالي الكلي للدفعة:'),
      combinedTotalPreview
    )
  );

  const methodSelect = el('select', {
    style: 'width:100%; padding:8px 10px; border-radius:8px; border:1px solid #cbd5e1; font-size:13px; background:#fff; text-align:center; text-align-last:center;'
  },
    el('option', { value: 'كاش' }, 'كاش'),
    el('option', { value: 'مدى' }, 'مدى / بطاقة'),
    el('option', { value: 'تحويل بنكي' }, 'تحويل بنكي')
  );

  const dateInput = el('input', {
    type: 'date',
    value: todayStr,
    style: 'width:100%; padding:8px; border-radius:8px; border:1px solid #cbd5e1; font-size:13px; text-align:center;'
  });

  const noteInput = el('input', {
    type: 'text',
    value: 'سداد رسوم',
    style: 'width:100%; padding:8px; border-radius:8px; border:1px solid #cbd5e1; font-size:13px; text-align:center;'
  });

  const saveBtn = el('button', {
    class: 'button',
    type: 'button',
    style: 'width:100%; padding:10px; font-weight:700; margin-top:14px; background:#047857; font-size:14px; text-align:center;'
  }, '💳 تسجيل الدفعة وإصدار سند القبض');

  // Form State Synchronization Logic
  function updateCombinedTotal() {
    const t = parseFloat(combinedTuitionInput.value) || 0;
    const b = parseFloat(combinedBusInput.value) || 0;
    const total = t + b;
    combinedTotalPreview.textContent = fmtSAR(total);
  }

  combinedTuitionInput.addEventListener('input', updateCombinedTotal);
  combinedBusInput.addEventListener('input', updateCombinedTotal);

  function applyPurposeUI() {
    const purpose = purposeSelect.value;
    if (purpose === 'tuition') {
      installmentRow.style.display = unpaidInstallments.length > 0 ? 'block' : 'none';
      singleAmountRow.style.display = 'block';
      combinedRow.style.display = 'none';
      newBusRow.style.display = 'none';
      singleAmountLabel.textContent = 'المبلغ المسدد للدراسة (ر.س) *';
      saveBtn.textContent = '💳 تسجيل الدفعة وإصدار سند القبض';

      // Default amount and max bounds
      if (installmentSelect.value) {
        const found = unpaidInstallments.find(x => x.id === installmentSelect.value);
        if (found) {
          singleAmountInput.value = found.remainingNum > 0 ? found.remainingNum.toFixed(2) : '';
          singleAmountInput.max = found.remainingNum.toFixed(2);
        }
      } else if (unpaidInstallments.length > 0) {
        singleAmountInput.value = unpaidInstallments[0].remainingNum.toFixed(2);
        singleAmountInput.max = tuitionOutstanding.toFixed(2);
      } else if (tuitionOutstanding > 0) {
        singleAmountInput.value = tuitionOutstanding.toFixed(2);
        singleAmountInput.max = tuitionOutstanding.toFixed(2);
      } else {
        singleAmountInput.value = '';
        singleAmountInput.max = '0.00';
      }
      noteInput.value = 'سداد رسوم';
    } else if (purpose === 'bus') {
      installmentRow.style.display = 'none';
      singleAmountRow.style.display = 'block';
      combinedRow.style.display = 'none';
      newBusRow.style.display = 'none';
      singleAmountLabel.textContent = 'المبلغ المسدد للباص والنقل (ر.س) *';
      singleAmountInput.value = busRemaining > 0 ? busRemaining.toFixed(2) : '';
      singleAmountInput.max = busRemaining.toFixed(2);
      saveBtn.textContent = '💳 تسجيل دفعة الباص وإصدار سند القبض';
      noteInput.value = 'سداد رسوم اشتراك الباص';
    } else if (purpose === 'bus_new') {
      installmentRow.style.display = 'none';
      singleAmountRow.style.display = 'none';
      combinedRow.style.display = 'none';
      newBusRow.style.display = 'block';
      saveBtn.textContent = '💳 تفعيل اشتراك الباص وإصدار سند القبض';
      noteInput.value = 'سداد رسوم اشتراك الباص';
    } else if (purpose === 'combined') {
      installmentRow.style.display = unpaidInstallments.length > 0 ? 'block' : 'none';
      singleAmountRow.style.display = 'none';
      combinedRow.style.display = 'block';
      newBusRow.style.display = 'none';
      saveBtn.textContent = '💳 تسجيل الدفعة المشتركة وإصدار سند القبض';

      // Prefill combined values and set max bounds
      const defaultTuition = unpaidInstallments.length > 0 ? unpaidInstallments[0].remainingNum : tuitionOutstanding;
      combinedTuitionInput.value = defaultTuition > 0 ? defaultTuition.toFixed(2) : '0.00';
      combinedTuitionInput.max = tuitionOutstanding.toFixed(2);
      combinedBusInput.value = busRemaining > 0 ? busRemaining.toFixed(2) : '0.00';
      combinedBusInput.max = busRemaining.toFixed(2);
      updateCombinedTotal();
      noteInput.value = 'سداد رسوم';
    } else if (purpose === 'combined_new') {
      installmentRow.style.display = unpaidInstallments.length > 0 ? 'block' : 'none';
      singleAmountRow.style.display = 'block';
      singleAmountLabel.textContent = 'المبلغ المسدد للدراسة (ر.س) *';
      const defaultTuition = unpaidInstallments.length > 0 ? unpaidInstallments[0].remainingNum : tuitionOutstanding;
      singleAmountInput.value = defaultTuition > 0 ? defaultTuition.toFixed(2) : '0.00';
      singleAmountInput.max = tuitionOutstanding.toFixed(2);
      combinedRow.style.display = 'none';
      newBusRow.style.display = 'block';
      saveBtn.textContent = '💳 سداد الدراسة وتفعيل الباص وإصدار السند';
      noteInput.value = 'سداد رسوم';
    }
  }

  purposeSelect.addEventListener('change', applyPurposeUI);

  installmentSelect.addEventListener('change', () => {
    if (purposeSelect.value === 'tuition' && installmentSelect.value) {
      const found = unpaidInstallments.find(x => x.id === installmentSelect.value);
      if (found) {
        singleAmountInput.value = found.remainingNum.toFixed(2);
        noteInput.value = 'سداد رسوم';
      }
    }
  });

  function selectInstallment(inst) {
    purposeSelect.value = 'tuition';
    applyPurposeUI();
    installmentSelect.value = inst.id;
    singleAmountInput.value = inst.remainingNum.toFixed(2);
    noteInput.value = 'سداد رسوم';
    singleAmountInput.focus();
  }

  // Initial trigger
  if (tuitionOutstanding <= 0) {
    if (hasBus && busRemaining > 0) {
      purposeSelect.value = 'bus';
    } else {
      purposeSelect.value = 'bus_new';
    }
  } else {
    purposeSelect.value = 'tuition';
  }
  applyPurposeUI();

  // Content assemble
  const content = el('div', { style: 'text-align:center;' },
    summaryGrid,
    installmentsSection,
    el('div', { style: 'background:#f8fafc; border:1px solid #e2e8f0; border-radius:8px; padding:12px; margin-bottom:12px; text-align:center;' },
      el('div', { style: 'margin-bottom:10px;' },
        el('label', { style: 'display:block; font-size:12px; font-weight:700; margin-bottom:4px; color:#334155; text-align:center;' }, 'تحديد وجهة السداد'),
        purposeSelect
      ),
      installmentRow,
      singleAmountRow,
      newBusRow,
      combinedRow,
      el('div', { style: 'display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;' },
        el('div', {},
          el('label', { style: 'display:block; font-size:12px; font-weight:700; margin-bottom:4px; color:#334155; text-align:center;' }, 'طريقة السداد'),
          methodSelect
        ),
        el('div', {},
          el('label', { style: 'display:block; font-size:12px; font-weight:700; margin-bottom:4px; color:#334155; text-align:center;' }, 'تاريخ السداد'),
          dateInput
        )
      ),
      el('div', { style: 'margin-bottom:4px;' },
        el('label', { style: 'display:block; font-size:12px; font-weight:700; margin-bottom:4px; color:#334155; text-align:center;' }, 'البيان / ملاحظات السند'),
        noteInput
      )
    ),
    saveBtn
  );

  container.replaceChildren(content);

  // Submit Handler
  saveBtn.onclick = async () => {
    const purpose = purposeSelect.value;
    let totalVal = 0;
    let busVal = 0;
    let tuitionVal = 0;
    let targetInstallmentId = null;
    let newBusFee = null;

    if (purpose === 'tuition') {
      totalVal = parseFloat(singleAmountInput.value) || 0;
      busVal = 0;
      tuitionVal = totalVal;
      targetInstallmentId = installmentSelect.value || null;
    } else if (purpose === 'bus') {
      totalVal = parseFloat(singleAmountInput.value) || 0;
      busVal = totalVal;
      tuitionVal = 0;
      targetInstallmentId = null;
    } else if (purpose === 'bus_new') {
      const fee = parseFloat(newBusFeeInput.value) || 0;
      const paid = parseFloat(newBusPaidInput.value) || 0;
      if (fee <= 0) {
        toast('يرجى تحديد رسوم اشتراك الباص المقررة بشكل صحيح', true);
        return;
      }
      if (paid <= 0) {
        toast('يرجى تحديد المبلغ المسدد للباص بشكل صحيح', true);
        return;
      }
      if (paid > fee) {
        toast('المبلغ المسدد للباص لا يمكن أن يتجاوز رسوم الاشتراك المقررة', true);
        return;
      }
      newBusFee = fee;
      busVal = paid;
      tuitionVal = 0;
      totalVal = paid;
      targetInstallmentId = null;
    } else if (purpose === 'combined') {
      const t = parseFloat(combinedTuitionInput.value) || 0;
      const b = parseFloat(combinedBusInput.value) || 0;
      totalVal = t + b;
      busVal = b;
      tuitionVal = t;
      targetInstallmentId = installmentSelect.value || null;
    } else if (purpose === 'combined_new') {
      const t = parseFloat(singleAmountInput.value) || 0;
      const fee = parseFloat(newBusFeeInput.value) || 0;
      const paid = parseFloat(newBusPaidInput.value) || 0;
      if (fee <= 0) {
        toast('يرجى تحديد رسوم اشتراك الباص المقررة بشكل صحيح', true);
        return;
      }
      if (paid <= 0) {
        toast('يرجى تحديد المبلغ المسدد للباص بشكل صحيح', true);
        return;
      }
      if (paid > fee) {
        toast('المبلغ المسدد للباص لا يمكن أن يتجاوز رسوم الاشتراك المقررة', true);
        return;
      }
      newBusFee = fee;
      busVal = paid;
      tuitionVal = t;
      totalVal = t + paid;
      targetInstallmentId = installmentSelect.value || null;
    }

    if (totalVal <= 0) {
      toast('يرجى إدخال مبلغ سداد صحيح أكبر من الصفر', true);
      return;
    }

    // Tuition Cap Validation
    if (tuitionVal > 0) {
      if (tuitionOutstanding <= 0) {
        toast('تم سداد كامل الرسوم الدراسية المقررة للطالب مسبقاً', true);
        return;
      }
      if (tuitionVal > (tuitionOutstanding + 0.01)) {
        toast(`المبلغ المسدد للدراسة (${fmtSAR(tuitionVal)}) يتجاوز الرسوم الدراسية المتبقية (${fmtSAR(tuitionOutstanding)})`, true);
        return;
      }
      if (targetInstallmentId) {
        const found = unpaidInstallments.find(x => x.id === targetInstallmentId);
        if (found && tuitionVal > (found.remainingNum + 0.01)) {
          toast(`المبلغ المسدد (${fmtSAR(tuitionVal)}) يتجاوز المتبقي من هذا القسط (${fmtSAR(found.remainingNum)})`, true);
          return;
        }
      }
    }

    // Standard Bus Cap Validation
    if (busVal > 0 && purpose !== 'bus_new' && purpose !== 'combined_new') {
      if (!hasBus) {
        toast('الطالب غير مشترك في خدمة الباص', true);
        return;
      }
      if (busRemaining <= 0) {
        toast('تم سداد كامل رسوم الباص مسبقاً', true);
        return;
      }
      if (busVal > (busRemaining + 0.01)) {
        toast(`المبلغ المسدد للباص (${fmtSAR(busVal)}) أكبر من المتبقي (${fmtSAR(busRemaining)})`, true);
        return;
      }
    }

    saveBtn.disabled = true;
    saveBtn.textContent = 'جاري تسجيل الدفعة وإصدار السند...';
    const printWindow = window.open('about:blank', '_blank');

    try {
      // Record Payment (server handles atomic activation/renewal via new_bus_fee)
      const payload = {
        student_id: student.id,
        amount: totalVal,
        bus_amount: busVal,
        method: methodSelect.value,
        paid_on: dateInput.value || todayStr,
        note: noteInput.value.trim() || 'سداد رسوم'
      };
      if (newBusFee !== null) {
        payload.new_bus_fee = newBusFee;
      }
      if (targetInstallmentId) {
        payload.installment_id = targetInstallmentId;
      }

      const res = await api.post('payments', payload);
      toast('تم تسجيل الدفعة بنجاح! جاري فتح سند القبض...');
      if (dialog && dialog.close) dialog.close();
      const paymentId = res.id;
      if (paymentId && printWindow) {
        printWindow.location.href = `../print/templates/receipt.html?payment=${encodeURIComponent(paymentId)}`;
      } else if (paymentId) {
        window.open(`../print/templates/receipt.html?payment=${encodeURIComponent(paymentId)}`, '_blank');
      }
      if (typeof onReload === 'function') {
        await onReload();
      }
    } catch (err) {
      if (printWindow) printWindow.close();
      toast(err.message || 'تعذر تسجيل الدفعة وسند القبض', true);
      saveBtn.disabled = false;
      saveBtn.textContent = '💳 تسجيل الدفعة وإصدار سند القبض';
    }
  };
}
