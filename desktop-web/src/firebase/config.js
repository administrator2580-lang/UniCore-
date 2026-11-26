import { deleteApp, getApps, initializeApp } from 'firebase/app';
import {
  browserLocalPersistence,
  getAuth,
  GoogleAuthProvider,
  setPersistence,
  signInWithPopup,
  signInWithRedirect,
  signOut as firebaseSignOut
} from 'firebase/auth';
import {
  getFirestore,
  initializeFirestore,
  persistentLocalCache,
  persistentMultipleTabManager
} from 'firebase/firestore';

/* global __UNICORE_FIREBASE_DEFAULTS__ */
const BUILD_DEFAULTS = typeof __UNICORE_FIREBASE_DEFAULTS__ !== 'undefined' ? __UNICORE_FIREBASE_DEFAULTS__ : {};
const OVERRIDE_KEY = 'unicore_firebase_override_v1';

export function getFirebaseRuntimeConfig() {
  let override = {};
  try {
    const raw = localStorage.getItem(OVERRIDE_KEY);
    if (raw) override = JSON.parse(raw);
  } catch {
    override = {};
  }
  const projectId = override.projectId || BUILD_DEFAULTS.projectId || 'gen-lang-client-0870704520';
  return {
    apiKey: override.apiKey || BUILD_DEFAULTS.apiKey || '',
    authDomain: override.authDomain || BUILD_DEFAULTS.authDomain || `${projectId}.firebaseapp.com`,
    projectId,
    storageBucket: override.storageBucket || BUILD_DEFAULTS.storageBucket || `${projectId}.firebasestorage.app`,
    messagingSenderId: override.messagingSenderId || BUILD_DEFAULTS.messagingSenderId || '324526157499',
    appId: override.appId || BUILD_DEFAULTS.appId || '',
    firestoreDatabaseId:
      override.firestoreDatabaseId ||
      BUILD_DEFAULTS.firestoreDatabaseId ||
      'ai-studio-android-unicore-9b169dd7-0f78-40f7-9411-0587832d12de'
  };
}

export function saveFirebaseOverrideConfig(partial) {
  localStorage.setItem(OVERRIDE_KEY, JSON.stringify(partial || {}));
}

export function clearFirebaseOverrideConfig() {
  localStorage.removeItem(OVERRIDE_KEY);
}

let cachedClients = null;

export function getFirebaseClients() {
  if (cachedClients) return cachedClients;
  const cfg = getFirebaseRuntimeConfig();
  if (!cfg.apiKey || !cfg.projectId) {
    return null;
  }

  const firebaseOptions = {
    apiKey: cfg.apiKey,
    authDomain: cfg.authDomain,
    projectId: cfg.projectId,
    storageBucket: cfg.storageBucket,
    messagingSenderId: cfg.messagingSenderId
  };
  if (cfg.appId) {
    firebaseOptions.appId = cfg.appId;
  }

  const app = getApps().length > 0 ? getApps()[0] : initializeApp(firebaseOptions);
  const auth = getAuth(app);
  setPersistence(auth, browserLocalPersistence).catch(() => {});

  let db;
  try {
    db = initializeFirestore(
      app,
      {
        localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() })
      },
      cfg.firestoreDatabaseId
    );
  } catch {
    db = getFirestore(app, cfg.firestoreDatabaseId);
  }

  cachedClients = { app, auth, db, config: cfg };
  return cachedClients;
}

export async function reinitializeFirebaseClients() {
  const existing = getApps();
  await Promise.all(existing.map((a) => deleteApp(a).catch(() => {})));
  cachedClients = null;
  return getFirebaseClients();
}

export async function signInWithGoogleUnified(auth) {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: 'select_account' });
  try {
    return await signInWithPopup(auth, provider);
  } catch (err) {
    if (err?.code === 'auth/popup-blocked') {
      await signInWithRedirect(auth, provider);
      return null;
    }
    throw err;
  }
}

export async function signOutUnified(auth) {
  await firebaseSignOut(auth);
}

export function formatAuthError(err) {
  const code = err?.code || '';
  switch (code) {
    case 'auth/popup-closed-by-user':
    case 'auth/cancelled-popup-request':
      return 'تم إلغاء نافذة تسجيل الدخول بـ Google.';
    case 'auth/unauthorized-domain': {
      const host = typeof window !== 'undefined' && window.location?.hostname ? window.location.hostname : 'localhost';
      return `النطاق (${host}) غير مضاف في Firebase Console -> Authentication -> Settings -> Authorized domains. أضف "${host}" ثم أعد المحاولة.`;
    }
    case 'auth/operation-not-allowed':
      return 'تسجيل الدخول بـ Google غير مفعّل في Firebase Authentication -> Sign-in method.';
    case 'auth/user-disabled':
      return 'تم تعطيل هذا الحساب.';
    case 'auth/network-request-failed':
      return 'تعذر الاتصال بالإنترنت. تحقق من الشبكة ثم حاول مجدداً.';
    default:
      return err?.message || 'حدث خطأ أثناء تسجيل الدخول بحساب Google.';
  }
}
