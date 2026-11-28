import { deleteApp, getApps, initializeApp } from 'firebase/app';
import { initializeAppCheck, ReCaptchaV3Provider } from 'firebase/app-check';
import {
  browserLocalPersistence,
  browserPopupRedirectResolver,
  getAuth,
  GoogleAuthProvider,
  indexedDBLocalPersistence,
  initializeAuth,
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
  const projectId = String(override.projectId || BUILD_DEFAULTS.projectId || 'gen-lang-client-0870704520').trim();
  return {
    apiKey: String(override.apiKey || BUILD_DEFAULTS.apiKey || '').trim(),
    authDomain: String(override.authDomain || BUILD_DEFAULTS.authDomain || `${projectId}.firebaseapp.com`).trim(),
    projectId,
    storageBucket: String(override.storageBucket || BUILD_DEFAULTS.storageBucket || `${projectId}.firebasestorage.app`).trim(),
    messagingSenderId: String(override.messagingSenderId || BUILD_DEFAULTS.messagingSenderId || '324526157499').trim(),
    appId: String(override.appId || BUILD_DEFAULTS.appId || '').trim(),
    firestoreDatabaseId: String(
      override.firestoreDatabaseId ||
      BUILD_DEFAULTS.firestoreDatabaseId ||
      'ai-studio-android-unicore-9b169dd7-0f78-40f7-9411-0587832d12de'
    ).trim(),
    webRecaptchaKey: String(override.webRecaptchaKey || BUILD_DEFAULTS.webRecaptchaKey || '').trim(),
    appCheckDebugToken: String(override.appCheckDebugToken || BUILD_DEFAULTS.appCheckDebugToken || '').trim()
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

  if (cfg.appCheckDebugToken || cfg.webRecaptchaKey) {
    try {
      if (cfg.appCheckDebugToken && typeof window !== 'undefined') {
        window.FIREBASE_APPCHECK_DEBUG_TOKEN = cfg.appCheckDebugToken;
      }
      initializeAppCheck(app, {
        provider: new ReCaptchaV3Provider(
          cfg.webRecaptchaKey || '6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI'
        ),
        isTokenAutoRefreshEnabled: true
      });
    } catch {
      // Ignore if App Check already initialized
    }
  }

  let auth;
  try {
    auth = initializeAuth(app, {
      persistence: [indexedDBLocalPersistence, browserLocalPersistence],
      popupRedirectResolver: browserPopupRedirectResolver
    });
  } catch {
    auth = getAuth(app);
    setPersistence(auth, browserLocalPersistence).catch(() => {});
  }

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
    return await signInWithPopup(auth, provider, browserPopupRedirectResolver);
  } catch (err) {
    if (err?.code === 'auth/popup-blocked') {
      await signInWithRedirect(auth, provider, browserPopupRedirectResolver);
      return null;
    }
    throw err;
  }
}

export async function signOutUnified(auth) {
  await firebaseSignOut(auth);
}

export async function requestClassroomAccessToken(auth) {
  const provider = new GoogleAuthProvider();
  provider.addScope('https://www.googleapis.com/auth/classroom.courses.readonly');
  provider.addScope('https://www.googleapis.com/auth/classroom.coursework.me.readonly');
  provider.setCustomParameters({ prompt: 'consent', include_granted_scopes: 'true' });
  const result = await signInWithPopup(auth, provider, browserPopupRedirectResolver);
  const credential = GoogleAuthProvider.credentialFromResult(result);
  return credential?.accessToken || null;
}

export async function fetchGoogleClassroomAssignments(accessToken) {
  if (!accessToken) throw new Error('Google Classroom access token unavailable.');
  const headers = { Authorization: `Bearer ${accessToken}` };
  const coursesRes = await fetch(
    'https://classroom.googleapis.com/v1/courses?courseStates=ACTIVE&pageSize=12',
    { headers }
  );
  if (!coursesRes.ok) {
    const errText = await coursesRes.text().catch(() => '');
    throw new Error(`Classroom API (${coursesRes.status}): ${errText.slice(0, 140) || 'Unable to load courses.'}`);
  }
  const coursesJson = await coursesRes.json();
  const activeCourses = Array.isArray(coursesJson.courses) ? coursesJson.courses.slice(0, 8) : [];
  const collected = [];

  for (const course of activeCourses) {
    try {
      const cwRes = await fetch(
        `https://classroom.googleapis.com/v1/courses/${encodeURIComponent(course.id)}/courseWork?pageSize=20`,
        { headers }
      );
      if (!cwRes.ok) continue;
      const cwJson = await cwRes.json();
      const works = Array.isArray(cwJson.courseWork) ? cwJson.courseWork : [];
      for (const cw of works) {
        let dueAtMillis = 0;
        if (cw.dueDate?.year && cw.dueDate?.month && cw.dueDate?.day) {
          const hrs = Number(cw.dueTime?.hours ?? 23);
          const mins = Number(cw.dueTime?.minutes ?? 59);
          dueAtMillis = Date.UTC(cw.dueDate.year, cw.dueDate.month - 1, cw.dueDate.day, hrs, mins, 0);
        } else {
          dueAtMillis = Date.now() + 7 * 24 * 60 * 60 * 1000;
        }
        collected.push({
          externalId: `gc_${course.id}_${cw.id}`,
          title: cw.title || 'Classroom Assignment',
          description: cw.description || `Imported from Google Classroom (${course.name || 'Course'})`,
          courseTitle: course.name || 'Google Classroom',
          dueAtMillis,
          classroomLink: cw.alternateLink || 'https://classroom.google.com'
        });
      }
    } catch {
      // Skip individual course fetch errors gracefully
    }
  }
  return collected;
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
    case 'auth/argument-error':
      return 'تعذر فتح نافذة تسجيل الدخول بـ Google بسبب إعدادات المصادقة أو النافذة المنبثقة. أعد المحاولة أو حدّث البرنامج.';
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
