const BASE = '/api/v1';

function authHeaders(extra = {}) {
  const token = sessionStorage.getItem('gheras_token') || localStorage.getItem('gheras_token');
  const headers = { Accept: 'application/json', ...extra };
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

async function send(path, options) {
  const response = await fetch(`${BASE}/${path.replace(/^\//, '')}`, options);
  if (response.status === 401) window.dispatchEvent(new CustomEvent('gheras:logout'));
  return response;
}

async function request(path, options = {}) {
  const headers = authHeaders(options.headers);
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await send(path, { ...options, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body) });
  const data = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) throw new Error(data?.error?.message || data?.error?.code || 'تعذر إكمال الطلب');
  return data;
}

// Raw binary upload (student photo): the blob is sent as-is with its own type, not as JSON.
async function upload(path, blob) {
  const response = await send(path, { method: 'POST', headers: authHeaders({ 'Content-Type': blob.type || 'application/octet-stream' }), body: blob });
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error(data?.error?.message || data?.error?.code || 'تعذر رفع الملف');
  return data;
}

// Authenticated image fetch for <img src>; the caller must URL.revokeObjectURL() the result.
async function fetchBlobUrl(path) {
  const response = await send(path, { headers: authHeaders({ Accept: 'image/*' }) });
  if (!response.ok) throw new Error('تعذر تحميل الصورة');
  return URL.createObjectURL(await response.blob());
}
const PAGE = 500, HARD_CAP = 20000;
// Walks a paginated list endpoint ({items,total,limit,offset}) and returns the full array.
// First page reveals `total`; remaining pages are fetched in parallel. Unpaginated (array) responses pass through.
async function fetchAll(path) {
  const sep = path.includes('?') ? '&' : '?';
  const page = n => request(`${path}${sep}limit=${PAGE}&offset=${n * PAGE}`);
  const first = await page(0);
  if (Array.isArray(first)) return first;
  const items = [...(first?.items || [])];
  const total = Math.min(Number(first?.total) || items.length, HARD_CAP);
  const pages = Math.ceil(total / PAGE);
  if (pages > 1) {
    const rest = await Promise.all(Array.from({ length: pages - 1 }, (_, i) => page(i + 1)));
    for (const p of rest) items.push(...(p?.items || []));
  }
  return items;
}
export const api = { get: path => request(path), fetchAll, upload, fetchBlobUrl, post: (path, body) => request(path, { method: 'POST', body }), patch: (path, body) => request(path, { method: 'PATCH', body }), put: (path, body) => request(path, { method: 'PUT', body }), del: path => request(path, { method: 'DELETE' }) };
