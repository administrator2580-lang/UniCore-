import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import fs from 'node:fs';
import path from 'node:path';

function readAndroidFirebaseFallback() {
  const result = {
    apiKey: '',
    projectId: 'gen-lang-client-0870704520',
    messagingSenderId: '324526157499',
    storageBucket: 'gen-lang-client-0870704520.firebasestorage.app',
    firestoreDatabaseId: 'ai-studio-android-unicore-9b169dd7-0f78-40f7-9411-0587832d12de',
    appId: '',
    webClientId: '',
    recaptchaSiteKey: ''
  };

  try {
    const gsPath = path.resolve(__dirname, '../app/google-services.json');
    if (fs.existsSync(gsPath)) {
      const gs = JSON.parse(fs.readFileSync(gsPath, 'utf8'));
      if (gs?.project_info?.project_id) result.projectId = gs.project_info.project_id;
      if (gs?.project_info?.project_number) result.messagingSenderId = gs.project_info.project_number;
      if (gs?.project_info?.storage_bucket) result.storageBucket = gs.project_info.storage_bucket;
      const client = gs?.client?.[0];
      if (client?.client_info?.mobilesdk_app_id) {
        result.appId = client.client_info.mobilesdk_app_id;
      }
      if (client?.api_key?.[0]?.current_key) {
        result.apiKey = client.api_key[0].current_key;
      }
      const webOauth = (client?.oauth_client || []).find((c) => c.client_type === 3);
      if (webOauth?.client_id) {
        result.webClientId = webOauth.client_id;
      }
    }
  } catch {
    // Ignore missing google-services.json during unconfigured CI builds
  }

  try {
    const xmlPath = path.resolve(__dirname, '../app/src/main/res/values/firebase_applet_config.xml');
    if (fs.existsSync(xmlPath)) {
      const xml = fs.readFileSync(xmlPath, 'utf8');
      const dbMatch = xml.match(/name="firestore_database_id"[^>]*>([^<]+)</);
      if (dbMatch?.[1]) result.firestoreDatabaseId = dbMatch[1].trim();
      const recaptchaMatch = xml.match(/name="recaptcha_site_key"[^>]*>([^<]+)</);
      if (recaptchaMatch?.[1]) result.recaptchaSiteKey = recaptchaMatch[1].trim();
    }
  } catch {
    // Ignore missing XML
  }

  return result;
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const fallback = readAndroidFirebaseFallback();
  const projectId = env.VITE_FIREBASE_PROJECT_ID || fallback.projectId;

  const runtimeConfig = {
    apiKey: env.VITE_FIREBASE_API_KEY || fallback.apiKey,
    authDomain: env.VITE_FIREBASE_AUTH_DOMAIN || `${projectId}.firebaseapp.com`,
    projectId,
    storageBucket: env.VITE_FIREBASE_STORAGE_BUCKET || fallback.storageBucket,
    messagingSenderId: env.VITE_FIREBASE_MESSAGING_SENDER_ID || fallback.messagingSenderId,
    appId: env.VITE_FIREBASE_APP_ID || fallback.appId,
    firestoreDatabaseId: env.VITE_FIREBASE_DATABASE_ID || fallback.firestoreDatabaseId,
    webClientId: env.VITE_GOOGLE_WEB_CLIENT_ID || fallback.webClientId,
    recaptchaSiteKey: env.VITE_RECAPTCHA_SITE_KEY || fallback.recaptchaSiteKey,
    webRecaptchaKey: env.VITE_FIREBASE_RECAPTCHA_V3_KEY || '',
    appCheckDebugToken: env.VITE_FIREBASE_APPCHECK_DEBUG_TOKEN || ''
  };

  return {
    plugins: [react()],
    base: '/',
    define: {
      __UNICORE_FIREBASE_DEFAULTS__: JSON.stringify(runtimeConfig)
    },
    server: {
      port: 5173,
      strictPort: true
    },
    build: {
      outDir: 'dist',
      emptyOutDir: true
    }
  };
});
