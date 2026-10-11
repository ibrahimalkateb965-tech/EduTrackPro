import { existsSync } from 'node:fs';
import type { E2EConfig } from 'e2e';
import { web } from '@e2e-dev/web';
import { opencodeConsole } from 'e2e/oauth/opencode-console';

// Local, untracked values: OPENCODE_API_KEY, APP_URL, E2E_USER_ADMIN_USERNAME, E2E_USER_ADMIN_PASSWORD.
if (existsSync('.env')) process.loadEnvFile('.env');

// APP_URL = a deployed EduTrack Pro instance (dashboard + /api/v1), e.g. https://staging.example.com/web/dashboard/
// Without it the runner serves the static dashboard locally (no backend: only the smoke test can pass).
const deployedUrl = process.env.APP_URL;

export default {
  agents: {
    default: {
      // OpenCode Console (Zen) model; OPENCODE_API_KEY authenticates it. Override with E2E_MODEL.
      model: opencodeConsole(process.env.E2E_MODEL ?? 'deepseek-v4.1-flash'),
      // The target is a shared deployment: never improvise data the step did not ask for.
      system:
        'You are a thorough QA agent. Verify every outcome on screen. ' +
        'Fill only the fields the step names, with exactly the given values; leave every other field untouched ' +
        '(never fees, payments, national IDs, or other phones). If the app rejects the submission, report the step ' +
        'as failed with the error shown instead of changing values or filling extra fields.',
      context:
        'EduTrack Pro is the Arabic (RTL) admin dashboard of مركز غراس, a learning center. ' +
        'All UI text is Arabic. الطلاب = Students, إضافة طالب = Add student, تسجيل الدخول = Sign in, ' +
        'اسم الطالب = Student name, هاتف ولي الأمر = Guardian phone, المجموعة الدراسية = Study group.',
    },
  },
  credentials: {
    // Manager account on the deployment under test; values come from the environment.
    admin: {
      username: process.env.E2E_USER_ADMIN_USERNAME ?? 'manager',
      password: () => process.env.E2E_USER_ADMIN_PASSWORD ?? '',
    },
  },
  targets: [{
    engine: web({ locale: 'ar-SA', timezoneId: 'Asia/Riyadh' }),
    app: deployedUrl
      ? { url: deployedUrl }
      : {
          url: 'http://127.0.0.1:4173/dashboard/',
          readyUrl: 'http://127.0.0.1:4173/dashboard/index.html',
          command: {
            executable: 'node',
            args: ['scripts/serve-static.mjs'],
            env: { PORT: '4173' },
            log: '.e2e/logs/app.log',
          },
        },
  }],
} satisfies E2EConfig;
