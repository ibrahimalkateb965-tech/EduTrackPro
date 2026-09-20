const BASE = '/api/v1';

async function request(path, options = {}) {
  const token = sessionStorage.getItem('gheras_token');
  const headers = { Accept: 'application/json', ...options.headers };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`${BASE}/${path.replace(/^\//, '')}`, { ...options, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body) });
  if (response.status === 401) window.dispatchEvent(new CustomEvent('gheras:logout'));
  const data = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) throw new Error(data?.error?.message || data?.error?.code || 'تعذر إكمال الطلب');
  return data;
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
export const api = { get: path => request(path), fetchAll, post: (path, body) => request(path, { method: 'POST', body }), patch: (path, body) => request(path, { method: 'PATCH', body }), put: (path, body) => request(path, { method: 'PUT', body }), del: path => request(path, { method: 'DELETE' }) };
