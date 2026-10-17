/**
 * EduTrack Pro - Universal Print Engine
 * Dynamic data rendering, XSS sanitization, and print toolbar injection.
 * Adheres strictly to Rule 50 (0-9 Western Arabic numerals only).
 */

const ENDPOINTS = {
  receipt: 'print/receipt',
  guardian_card: 'print/guardian-card',
  excellence_certificate: 'print/certificate',
  student_report: 'print/student-report',
  admin_report: 'print/admin-report',
  monthly_report: 'print/monthly-report',
  schedule: 'print/schedule',
  attendance_report: 'print/attendance-report',
  student_receipt: 'print/student-receipt',
  lesson_log: 'print/lesson-log',
  statistics_report: 'print/statistics'
};

function escapeHtml(str) {
  if (str === null || str === undefined) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function getTemplateKey() {
  const path = window.location.pathname;
  const filename = path.split('/').pop().replace(/\.html$/i, '');
  return filename;
}

function getToken() {
  return sessionStorage.getItem('gheras_token') ||
    localStorage.getItem('gheras_token') ||
    '';
}

function renderMustache(template, data) {
  if (!template || !data) return template || '';

  // 1. Process block loops: {{#key}}...{{/key}}
  const loopRegex = /\{\{#([a-zA-Z0-9_]+)\}\}([\s\S]*?)\{\{\/\1\}\}/g;
  let rendered = template.replace(loopRegex, (match, key, inner) => {
    const val = data[key];
    // Truthy scalars (non-empty string, non-zero number, or boolean true)
    // render the block once with the surrounding data. This covers scalar
    // sections like {{#photo_url}} holding a string data URL. Strings, numbers
    // and booleans are never objects, so objects are excluded by construction.
    if (
      (typeof val === 'string' && val !== '') ||
      (typeof val === 'number' && val !== 0) ||
      val === true
    ) {
      return renderMustache(inner, data);
    }
    const list = val;
    if (!Array.isArray(list) || list.length === 0) {
      return '';
    }
    return list.map(item => {
      const merged = (typeof item === 'object' && item !== null)
        ? { ...data, ...item }
        : { ...data, this: item };
      return renderMustache(inner, merged);
    }).join('');
  });

  // 2. Process inverted sections: {{^key}}...{{/key}}
  const invertedRegex = /\{\{\^([a-zA-Z0-9_]+)\}\}([\s\S]*?)\{\{\/\1\}\}/g;
  rendered = rendered.replace(invertedRegex, (match, key, inner) => {
    const val = data[key];
    if (!val || (Array.isArray(val) && val.length === 0)) {
      return renderMustache(inner, data);
    }
    return '';
  });

  // 3. Process unescaped raw placeholders: {{{key}}} or {{&key}}
  rendered = rendered.replace(/\{\{\{([a-zA-Z0-9_]+)\}\}\}/g, (match, key) => {
    const val = data[key];
    if (val === undefined || val === null) return '';
    return String(val);
  });
  rendered = rendered.replace(/\{\{&([a-zA-Z0-9_]+)\}\}/g, (match, key) => {
    const val = data[key];
    if (val === undefined || val === null) return '';
    return String(val);
  });

  // 4. Process standard scalar placeholders (HTML-escaped by default to prevent XSS): {{key}}
  rendered = rendered.replace(/\{\{([a-zA-Z0-9_]+)\}\}/g, (match, key) => {
    const val = data[key];
    if (val === undefined || val === null) return '';
    return escapeHtml(val);
  });

  return rendered;
}

function showPrintToast(msg) {
  let toastEl = document.querySelector('.print-toast');
  if (!toastEl) {
    toastEl = document.createElement('div');
    toastEl.className = 'print-toast';
    document.body.appendChild(toastEl);
  }
  toastEl.textContent = msg;
  toastEl.classList.add('visible');
  setTimeout(() => toastEl.classList.remove('visible'), 6000);
}

// Request PDF render from the server headless Chromium engine
async function requestPdfRender(templateKey) {
  const searchParams = new URLSearchParams(window.location.search);
  searchParams.delete('token');
  const queryString = searchParams.toString();
  const token = getToken();
  const headers = { 'Accept': 'application/json' };
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  const response = await fetch(
    `/api/v1/print/pdf/${encodeURIComponent(templateKey)}${queryString ? '?' + queryString : ''}`,
    { method: 'POST', headers }
  );
  const data = await response.json().catch(() => null);
  if (!response.ok || !data?.url) {
    throw new Error(data?.error?.message || data?.detail || `خطأ استجابة الخادم (${response.status})`);
  }
  return data;
}

// Direct PDF download: the server renders this same template in headless Chromium
// (POST /api/v1/print/pdf/<key>), so Arabic shaping and spacing match the print preview
// and the file keeps real text. The browser then follows the returned short-lived URL as a
// plain attachment download (no print dialog, no blob): download managers such as IDM empty
// PDF bodies read through fetch(), but take over a URL download correctly.
async function downloadPdf(button, templateKey, label) {
  if (button.disabled) return;
  const labelEl = button.querySelector('.download-label');
  button.disabled = true;
  button.setAttribute('aria-busy', 'true');
  labelEl.textContent = 'جارٍ تجهيز الملف...';

  try {
    const data = await requestPdfRender(templateKey);
    const link = document.createElement('a');
    link.href = data.url;
    document.body.append(link);
    link.click();
    link.remove();
  } catch (err) {
    console.error('[PrintEngine] PDF download failed:', err);
    alert(`تعذر تنزيل ملف PDF: ${err.message || 'خطأ غير معروف'}`);
  } finally {
    button.disabled = false;
    button.removeAttribute('aria-busy');
    labelEl.textContent = label;
  }
}

function injectToolbar(docTitle, templateKey, data = null) {
  if (document.querySelector('.print-toolbar')) return;
  const currentKey = templateKey || getTemplateKey();

  const toolbar = document.createElement('aside');
  toolbar.className = 'print-toolbar';
  toolbar.setAttribute('aria-label', 'شريط أدوات الطباعة');

  const info = document.createElement('div');
  info.className = 'print-toolbar-info';

  const badge = document.createElement('span');
  badge.className = 'print-toolbar-badge';
  badge.textContent = 'معاينة المستند';

  const title = document.createElement('span');
  title.className = 'print-toolbar-title';
  title.textContent = docTitle || document.title || 'مستند غراس';

  info.append(badge, title);

  const actions = document.createElement('div');
  actions.className = 'print-toolbar-actions';

  const downloadBtn = document.createElement('button');
  downloadBtn.type = 'button';
  downloadBtn.className = 'print-toolbar-btn download-btn';
  downloadBtn.id = 'gheras-download-action';
  const downloadLabel = currentKey === 'receipt' ? 'تنزيل السند (PDF)' : 'تنزيل (PDF)';
  downloadBtn.innerHTML = `
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" style="margin-left: 6px;">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
      <polyline points="7 10 12 15 17 10"></polyline>
      <line x1="12" y1="15" x2="12" y2="3"></line>
    </svg>
    <span class="download-label">${downloadLabel}</span>
  `;
  downloadBtn.addEventListener('click', () => downloadPdf(downloadBtn, currentKey, downloadLabel));

  const printBtn = document.createElement('button');
  printBtn.type = 'button';
  printBtn.className = 'print-toolbar-btn print-btn';
  printBtn.id = 'gheras-print-action';
  printBtn.innerHTML = `
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" style="margin-left: 6px;">
      <polyline points="6 9 6 2 18 2 18 9"></polyline>
      <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"></path>
      <rect x="6" y="14" width="12" height="8"></rect>
    </svg>
    طباعة المستند
  `;
  printBtn.addEventListener('click', () => window.print());

  actions.append(downloadBtn, printBtn);

  if (currentKey === 'receipt' && data && data.guardian_whatsapp) {
    const phone = String(data.guardian_whatsapp).replace(/\D/g, '');
    if (phone) {
      const centerName = data.center_name || 'مركز غراس';
      const receiptNo = data.receipt_no || '';
      const studentName = data.student_name || '';
      const amountVal = Number(data.amount || 0).toFixed(2);
      const receiptDate = data.date || '';

      const lines = [
        centerName,
        `سند قبض رقم: ${receiptNo}`,
        `الطالب: ${studentName}`,
        `المبلغ: ${amountVal} ريال`,
        `التاريخ: ${receiptDate}`,
      ];
      if (data.has_bus && Number(data.bus_paid_amount || 0) > 0) {
        lines.push(`المسدد من الباص: ${Number(data.bus_paid_amount).toFixed(2)} ريال`);
      }
      if (Number(data.remaining_balance || 0) > 0) {
        lines.push(`المتبقي: ${Number(data.remaining_balance).toFixed(2)} ريال`);
      }
      const messageText = lines.join('\n');
      const encodedText = encodeURIComponent(messageText);

      const isMobile = /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent || '') || Boolean(navigator.userAgentData?.mobile);
      const whatsappBtn = document.createElement('a');
      whatsappBtn.id = 'gheras-whatsapp-action';
      whatsappBtn.className = 'print-toolbar-btn whatsapp-btn';
      whatsappBtn.target = '_blank';
      whatsappBtn.rel = 'noopener noreferrer';
      // Unified universal click-to-chat URL matching communication.js openWhatsApp: works reliably across PC and mobile
      whatsappBtn.href = `https://wa.me/${phone}?text=${encodedText}`;
      whatsappBtn.innerHTML = `
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" style="margin-left: 6px;">
          <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"></path>
        </svg>
        <span class="whatsapp-label">مشاركة عبر واتساب (PDF)</span>
      `;

      let cachedShareFile = null;
      whatsappBtn.addEventListener('click', async (e) => {
        if (isMobile && typeof navigator.share === 'function') {
          e.preventDefault();
          const labelSpan = whatsappBtn.querySelector('.whatsapp-label');
          if (cachedShareFile && navigator.canShare && navigator.canShare({ files: [cachedShareFile] })) {
            try {
              await navigator.share({
                title: `سند قبض رقم ${receiptNo} - ${studentName}`,
                text: messageText,
                files: [cachedShareFile]
              });
              return;
            } catch (sErr) {
              if (sErr.name === 'AbortError') return;
            }
          }
          whatsappBtn.setAttribute('aria-busy', 'true');
          whatsappBtn.style.opacity = '0.75';
          if (labelSpan) labelSpan.textContent = 'جارٍ تجهيز ملف PDF...';
          try {
            const pdfMeta = await requestPdfRender('receipt');
            const pdfResp = await fetch(pdfMeta.url);
            const pdfBlob = await pdfResp.blob();
            const fileName = pdfMeta.filename || `سند_قبض_${receiptNo}.pdf`;
            const file = new File([pdfBlob], fileName, { type: 'application/pdf' });
            cachedShareFile = file;
            if (navigator.canShare && navigator.canShare({ files: [file] })) {
              try {
                await navigator.share({
                  title: `سند قبض رقم ${receiptNo} - ${studentName}`,
                  text: messageText,
                  files: [file]
                });
              } catch (sErr) {
                if (sErr.name === 'AbortError') return;
                if (sErr.name === 'NotAllowedError') {
                  if (labelSpan) labelSpan.textContent = 'الملف جاهز — اضغط للمشاركة 📄';
                  return;
                }
                throw sErr;
              }
            } else {
              window.location.href = `https://wa.me/${phone}?text=${encodedText}`;
            }
          } catch (err) {
            console.warn('[PrintEngine] Web Share PDF fallback:', err);
            window.location.href = `https://wa.me/${phone}?text=${encodedText}`;
          } finally {
            whatsappBtn.removeAttribute('aria-busy');
            whatsappBtn.style.opacity = '1';
            if (labelSpan && labelSpan.textContent !== 'الملف جاهز — اضغط للمشاركة 📄') {
              labelSpan.textContent = 'مشاركة عبر واتساب (PDF)';
            }
          }
          return;
        }

        // Desktop flow
        try {
          const dlBtn = document.getElementById('gheras-download-action');
          if (dlBtn) {
            downloadPdf(dlBtn, 'receipt', 'تنزيل السند (PDF)');
          }
          showPrintToast('تم فتح المحادثة وتنزيل ملف السند (PDF) — قم بسحبه وإرفاقه في المحادثة 📎');
        } catch (err) {
          console.warn('[PrintEngine] Desktop parallel PDF download:', err);
        }
      });

      actions.append(whatsappBtn);
    }
  }

  const closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'print-toolbar-btn close-btn';
  closeBtn.id = 'gheras-close-action';
  closeBtn.innerHTML = `
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" style="margin-left: 6px;">
      <line x1="18" y1="6" x2="6" y2="18"></line>
      <line x1="6" y1="6" x2="18" y2="18"></line>
    </svg>
    إغلاق
  `;
  closeBtn.addEventListener('click', () => {
    if (window.opener) {
      window.close();
    } else if (window.history.length > 1) {
      window.history.back();
    } else {
      window.location.href = '../../dashboard/';
    }
  });

  actions.append(closeBtn);
  toolbar.append(info, actions);
  document.body.prepend(toolbar);
}

function showError(message) {
  const existingToolbar = document.querySelector('.print-toolbar');
  if (existingToolbar) existingToolbar.remove();

  const errorBox = document.createElement('div');
  errorBox.className = 'print-error-banner';

  const content = document.createElement('div');
  content.className = 'print-error-content';

  const icon = document.createElement('div');
  icon.className = 'print-error-icon';
  icon.textContent = '⚠️';

  const textDiv = document.createElement('div');
  textDiv.className = 'print-error-text';

  const h3 = document.createElement('h3');
  h3.textContent = 'تعذر تجهيز مستند الطباعة';

  const p = document.createElement('p');
  p.textContent = String(message || 'حدث خطأ في جلب بيانات المستند');

  textDiv.append(h3, p);

  const closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'print-toolbar-btn close-btn';
  closeBtn.textContent = 'إغلاق النافذة';
  closeBtn.addEventListener('click', () => window.close());

  content.append(icon, textDiv, closeBtn);
  errorBox.append(content);
  document.body.prepend(errorBox);
}

async function initPrintEngine() {
  const templateKey = getTemplateKey();
  const endpoint = ENDPOINTS[templateKey];

  if (!endpoint) {
    console.warn(`[PrintEngine] Unknown template key: ${templateKey}`);
    injectToolbar(document.title);
    return;
  }

  // Preserve the raw template HTML before any mutations
  const rawHtml = document.body.innerHTML;

  // Build query string from location search
  const searchParams = new URLSearchParams(window.location.search);

  // Remove any legacy token from search params to avoid forwarding in API requests
  searchParams.delete('token');

  // Parameter normalization
  if (templateKey === 'guardian_card' || templateKey === 'student_report' || templateKey === 'student_receipt') {
    const sid = searchParams.get('id') || searchParams.get('student_id') || searchParams.get('student');
    if (sid) {
      searchParams.set('student', sid);
      searchParams.set('student_id', sid);
    }
  } else if (templateKey === 'receipt') {
    const pid = searchParams.get('payment_id') || searchParams.get('payment') || searchParams.get('id');
    if (pid) {
      searchParams.set('payment', pid);
      searchParams.set('payment_id', pid);
    }
  } else if (templateKey === 'schedule') {
    const rid = searchParams.get('room_id') || searchParams.get('room');
    if (rid) {
      searchParams.set('room', rid);
      searchParams.set('room_id', rid);
    }
  }

  const token = getToken();
  const headers = {
    'Accept': 'application/json'
  };
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const queryString = searchParams.toString();
  const apiUrl = `/api/v1/${endpoint}${queryString ? '?' + queryString : ''}`;

  try {
    const response = await fetch(apiUrl, { headers });
    if (!response.ok) {
      const errData = await response.json().catch(() => null);
      const errMsg = errData?.error?.message || errData?.detail || `خطأ استجابة الخادم (${response.status})`;
      showError(errMsg);
      return;
    }

    const data = await response.json();

    // Render template with real database payload (HTML escaped)
    let renderedHtml = renderMustache(rawHtml, data);

    // Strip any remaining unrendered {{...}} tags
    renderedHtml = renderedHtml.replace(/\{\{[^}]+\}\}/g, '—');

    document.body.innerHTML = renderedHtml;

    if (data.title && templateKey === 'excellence_certificate') {
      document.title = data.title;
    } else if (templateKey === 'receipt' && data.receipt_no) {
      const cleanStudent = (data.student_name || '').replace(/[/\\?%*:|"<>]/g, '_').trim();
      const studentPart = cleanStudent ? `_${cleanStudent}` : '';
      document.title = `سند_قبض_${data.receipt_no}${studentPart}`;
    }

    injectToolbar(document.title, templateKey, data);
  } catch (err) {
    console.error('[PrintEngine] Fetch failed:', err);
    showError(err.message || 'حدث خطأ في الاتصال بالخادم أثناء جلب بيانات الطباعة');
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initPrintEngine);
} else {
  initPrintEngine();
}
