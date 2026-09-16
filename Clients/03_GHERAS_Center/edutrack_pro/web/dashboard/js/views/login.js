import { el, toast } from '../ui.js';

export async function render(container, api) {
  container.replaceChildren();
  const username = el('input', { name: 'username', type: 'text', autocomplete: 'username', required: 'required' });
  const password = el('input', { name: 'password', type: 'password', autocomplete: 'current-password', required: 'required' });
  const submit = el('button', { class: 'button', type: 'submit' }, 'تسجيل الدخول');
  const form = el('form', {},
    el('label', {}, 'اسم المستخدم', username),
    el('label', {}, 'كلمة المرور', password),
    el('div', { class: 'form-actions' }, submit)
  );
  form.addEventListener('submit', async event => {
    event.preventDefault();
    submit.disabled = true;
    try {
      const data = await api.post('auth/login', { username: username.value.trim(), password: password.value });
      if (!data || !data.token) throw new Error('تعذر تسجيل الدخول');
      sessionStorage.setItem('gheras_token', data.token);
      location.hash = '#/home';
      window.dispatchEvent(new CustomEvent('gheras:login'));
    } catch (error) {
      toast(error.message || 'تعذر تسجيل الدخول', true);
      submit.disabled = false;
    }
  });
  container.append(el('div', { class: 'login' },
    el('div', { class: 'card' },
      el('img', { src: '../../assets/gheras_logo.png', alt: 'شعار مركز غراس' }),
      el('h1', {}, 'مركز غراس'),
      form
    )
  ));
  username.focus();
}
