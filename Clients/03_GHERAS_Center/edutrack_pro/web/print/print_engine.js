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
    const list = data[key];
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

function injectToolbar(docTitle) {
  if (document.querySelector('.print-toolbar')) return;

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
  closeBtn.addEventListener('click', () => window.close());

  actions.append(printBtn, closeBtn);
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
    }

    injectToolbar(document.title);
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
