import { api } from './api.js';
import { toast } from './ui.js';

const ROUTES = ['home', 'students', 'attendance', 'payments', 'expenses', 'accounts', 'staff', 'rooms', 'reports'];
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
  const name = location.hash.replace(/^#\/?/, '');
  return ROUTES.includes(name) ? name : 'home';
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
    userName.textContent = me.name || me.username || 'الإدارة';
  }
  const route = currentRoute();
  if (id !== renderId) return;
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
