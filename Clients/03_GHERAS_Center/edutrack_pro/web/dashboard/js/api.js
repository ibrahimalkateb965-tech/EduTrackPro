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
export const api = { get: path => request(path), post: (path, body) => request(path, { method: 'POST', body }), patch: (path, body) => request(path, { method: 'PATCH', body }), del: path => request(path, { method: 'DELETE' }) };
