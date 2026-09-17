import { api } from './api.js';
import { toast } from './ui.js';

const ROUTES = ['home', 'students', 'attendance', 'payments', 'expenses', 'accounts', 'staff', 'rooms', 'reports', 'settings'];
const ALLOWED_ROLES = ['manager', 'supervisor'];

const main = document.getElementById('view');
const sidebar = document.getElementById('sidebar');
const userName = document.getElementById('user-name');
const menuToggle = document.getElementById('menu-toggle');
const logoutButton = document.getElementById('logout-button');
const navLinks = Array.from(sidebar.querySelectorAll('a[data-route]'));

let currentUser = null;
let loginActive = false;
let renderId = 0;
let logoutInFlight = false;

function currentRoute() {
  const hash = location.hash.replace(/^#\/?/, '');
  const [name] = hash.split('?');
  return ROUTES.includes(name) ? name : 'home';
}

function filterSidebarByPermissions(user) {
  if (!user || user.role === 'manager') {
    navLinks.forEach(link => { link.style.display = ''; });
    return;
  }
  const perms = user.permissions || {};
  navLinks.forEach(link => {
    const route = link.dataset.route;
    let visible = true;
    if (route === 'students' && !perms.students) visible = false;
    if (route === 'attendance' && !perms.attendance && !perms.daily_evaluation) visible = false;
    if ((route === 'payments' || route === 'expenses' || route === 'accounts') && !perms.finance) visible = false;
    link.style.display = visible ? '' : 'none';
  });
}

function setActiveLink(route) {
  navLinks.forEach(link => link.classList.toggle('active', link.dataset.route === route));
}

async function showLogin(id) {
  setActiveLink('');
  sidebar.classList.remove('open');
  userName.textContent = 'الإدارة';
  loginActive = true;
  document.body.classList.add('auth-locked');
  const { render } = await import('./views/login.js');
  if (id !== undefined && id !== renderId) return;
  main.replaceChildren();
  await render(main, api);
}

async function handleRoute() {
  const id = ++renderId;
  if (!sessionStorage.getItem('gheras_token')) {
    currentUser = null;
    if (!loginActive) await showLogin(id);
    return;
  }
  if (!currentUser) {
    let me = null;
    try { me = await api.get('me'); } catch (error) { toast(error.message, true); }
    if (id !== renderId) return;
    if (!me || !ALLOWED_ROLES.includes(me.role)) {
      if (me) toast('هذه اللوحة مخصصة لإدارة المركز', true);
      await doLogout();
      return;
    }
    currentUser = me;
    api.currentUser = me;
    userName.textContent = me.name || me.username || 'الإدارة';
    filterSidebarByPermissions(me);
  }
  const route = currentRoute();
  if (id !== renderId) return;

  // Enforce frontend permission guard for restricted routes
  if (currentUser && currentUser.role === 'supervisor') {
    const perms = currentUser.permissions || {};
    if (route === 'students' && !perms.students) {
      toast('ليس لديك صلاحية الوصول إلى قسم الطلاب', true);
      location.hash = '#/home';
      return;
    }
    if (route === 'attendance' && !perms.attendance && !perms.daily_evaluation) {
      toast('ليس لديك صلاحية الوصول إلى الحضور والتقييم', true);
      location.hash = '#/home';
      return;
    }
    if (['payments', 'expenses', 'accounts'].includes(route) && !perms.finance) {
      toast('ليس لديك صلاحية الوصول إلى العمليات المالية', true);
      location.hash = '#/home';
      return;
    }
  }

  setActiveLink(route);
  sidebar.classList.remove('open');
  loginActive = false;
  document.body.classList.remove('auth-locked');
  let view;
  try { view = await import(`./views/${route}.js`); } catch (error) {
    toast('تعذر تحميل هذه الصفحة', true);
    return;
  }
  if (id !== renderId) return;
  main.replaceChildren();
  try { await view.render(main, api); } catch (error) { toast(error.message, true); }
}

async function doLogout() {
  if (logoutInFlight) return;
  logoutInFlight = true;
  if (sessionStorage.getItem('gheras_token')) {
    try { await api.post('auth/logout'); } catch (error) {}
  }
  sessionStorage.removeItem('gheras_token');
  currentUser = null;
  api.currentUser = null;
  logoutInFlight = false;
  const id = ++renderId;
  await showLogin(id);
}

function onForcedLogout() {
  if (!sessionStorage.getItem('gheras_token')) return;
  doLogout();
}

menuToggle.addEventListener('click', () => sidebar.classList.toggle('open'));
logoutButton.addEventListener('click', doLogout);
window.addEventListener('hashchange', handleRoute);
window.addEventListener('gheras:login', handleRoute);
window.addEventListener('gheras:logout', onForcedLogout);

handleRoute();
