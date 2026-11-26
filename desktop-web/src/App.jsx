import React, { useEffect, useMemo, useRef, useState } from 'react';
import { onAuthStateChanged } from 'firebase/auth';
import {
  Ban,
  Bell,
  ClipboardCheck,
  FolderKanban,
  LogOut,
  Moon,
  School,
  Sparkles,
  Sun,
  SunMoon,
  UserCheck
} from 'lucide-react';
import { getFirebaseClients, signOutUnified } from './firebase/config';
import {
  ensureUserSessionProfile,
  subscribeAuditLogs,
  subscribeCourses,
  subscribeDepartments,
  subscribeDriveFolders,
  subscribeNotifications,
  subscribeQuizAttempts,
  subscribeQuizzes,
  subscribeRoleInvites,
  subscribeSupportMessages,
  subscribeSystemSettings,
  subscribeUserProfile,
  subscribeUsersDirectory,
  subscribeVerifiedFiles
} from './data/firestoreService';
import { stringsFor } from './i18n/strings';
import AuthView from './components/AuthView';
import CoursesView from './components/CoursesView';
import QuizzesView from './components/QuizzesView';
import AiTutorView from './components/AiTutorView';
import FilesVaultView from './components/FilesVaultView';
import AccountConsoleView from './components/AccountConsoleView';
import NotificationsDrawer from './components/NotificationsDrawer';

const PREFS_KEY = 'unicore_prefs_v1';

function loadPrefs() {
  try {
    const raw = localStorage.getItem(PREFS_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      return {
        language: parsed.language === 'ENGLISH' ? 'ENGLISH' : 'ARABIC',
        themeMode: ['SYSTEM', 'DARK', 'LIGHT'].includes(parsed.themeMode) ? parsed.themeMode : 'SYSTEM',
        desktopNotificationsEnabled: parsed.desktopNotificationsEnabled !== false,
        readNotificationIds: Array.isArray(parsed.readNotificationIds) ? parsed.readNotificationIds : []
      };
    }
  } catch {
    // Default below
  }
  return {
    language: 'ARABIC',
    themeMode: 'SYSTEM',
    desktopNotificationsEnabled: true,
    readNotificationIds: []
  };
}

export default function App() {
  const [clients, setClients] = useState(() => getFirebaseClients());
  const [prefs, setPrefs] = useState(loadPrefs);
  const [authReady, setAuthReady] = useState(false);
  const [currentUser, setCurrentUser] = useState(null);
  const [toast, setToast] = useState('');

  const strings = useMemo(() => stringsFor(prefs.language), [prefs.language]);

  // Persist prefs & apply dir/theme to documentElement
  useEffect(() => {
    try {
      localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));
    } catch {
      // Non-fatal
    }
    document.documentElement.lang = prefs.language === 'ENGLISH' ? 'en' : 'ar';
    document.documentElement.dir = prefs.language === 'ENGLISH' ? 'ltr' : 'rtl';
  }, [prefs]);

  useEffect(() => {
    const media = window.matchMedia?.('(prefers-color-scheme: dark)');
    const applyTheme = () => {
      const isDark =
        prefs.themeMode === 'DARK' || (prefs.themeMode === 'SYSTEM' && media?.matches);
      document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light');
    };
    applyTheme();
    media?.addEventListener?.('change', applyTheme);
    return () => media?.removeEventListener?.('change', applyTheme);
  }, [prefs.themeMode]);

  useEffect(() => {
    if (!clients?.auth) {
      setAuthReady(true);
      setCurrentUser(null);
      return;
    }
    const unsub = onAuthStateChanged(clients.auth, (user) => {
      setCurrentUser(user);
      setAuthReady(true);
    });
    return unsub;
  }, [clients]);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(''), 4000);
    return () => clearTimeout(t);
  }, [toast]);

  const cycleTheme = () => {
    setPrefs((prev) => ({
      ...prev,
      themeMode:
        prev.themeMode === 'SYSTEM' ? 'DARK' : prev.themeMode === 'DARK' ? 'LIGHT' : 'SYSTEM'
    }));
  };

  if (!authReady) {
    return (
      <div className="auth-page">
        <div className="spinner" style={{ width: '36px', height: '36px', color: 'var(--primary)' }} />
      </div>
    );
  }

  return (
    <>
      {!currentUser || !clients ? (
        <AuthView
          strings={strings}
          language={prefs.language}
          themeMode={prefs.themeMode}
          onToggleLanguage={(language) => setPrefs((p) => ({ ...p, language }))}
          onCycleTheme={cycleTheme}
          clients={clients}
          onClientsUpdated={setClients}
          onShowToast={setToast}
        />
      ) : (
        <AuthenticatedShell
          key={currentUser.uid}
          clients={clients}
          currentUser={currentUser}
          strings={strings}
          prefs={prefs}
          setPrefs={setPrefs}
          onCycleTheme={cycleTheme}
          onShowToast={setToast}
        />
      )}

      {toast ? (
        <div className="snackbar" role="status">
          <span>{toast}</span>
        </div>
      ) : null}
    </>
  );
}

function AuthenticatedShell({ clients, currentUser, strings, prefs, setPrefs, onCycleTheme, onShowToast }) {
  const { auth, db } = clients;
  const uid = currentUser.uid;

  const [tab, setTab] = useState('COURSES');
  const [showNotifications, setShowNotifications] = useState(false);

  const [userRole, setUserRole] = useState('STUDENT');
  const [userProfile, setUserProfile] = useState(null);
  const [systemSettings, setSystemSettings] = useState({
    dailyAiLimitPerUser: 20,
    dailyWriteLimitPerUser: 100,
    aiEnabled: true,
    zeroCostLockActive: false
  });
  const [courses, setCourses] = useState([]);
  const [departments, setDepartments] = useState([]);
  const [driveFolders, setDriveFolders] = useState([]);
  const [driveFiles, setDriveFiles] = useState([]);
  const [isDriveRefreshing, setIsDriveRefreshing] = useState(false);
  const [quizzes, setQuizzes] = useState([]);
  const [attempts, setAttempts] = useState([]);
  const [files, setFiles] = useState([]);
  const [notifications, setNotifications] = useState([]);
  const [supportMessages, setSupportMessages] = useState([]);
  const [usersDirectory, setUsersDirectory] = useState([]);
  const [roleInvites, setRoleInvites] = useState([]);
  const [auditLogs, setAuditLogs] = useState([]);

  const seenNotifsRef = useRef({ initialized: false, ids: new Set() });

  useEffect(() => {
    let active = true;
    ensureUserSessionProfile(db, auth)
      .then((role) => {
        if (active) setUserRole(role);
      })
      .catch((err) => onShowToast(err.message));
    return () => {
      active = false;
    };
  }, [db, auth, uid]);

  useEffect(() => {
    const unsubProfile = subscribeUserProfile(
      db,
      uid,
      (profile) => {
        setUserProfile(profile);
        if (profile?.role && ['STUDENT', 'ADMIN', 'SUPER_ADMIN'].includes(profile.role)) {
          setUserRole(profile.role);
        }
      },
      (err) => onShowToast(err.message)
    );
    const unsubSettings = subscribeSystemSettings(db, setSystemSettings);
    return () => {
      unsubProfile();
      unsubSettings();
    };
  }, [db, uid]);

  const isDisabledNonSuper = Boolean(userProfile?.accountDisabled) && userRole !== 'SUPER_ADMIN';
  const isAdmin = userRole !== 'STUDENT';

  useEffect(() => {
    if (isDisabledNonSuper) return undefined;
    const unsubs = [
      subscribeCourses(db, isAdmin, setCourses, (e) => onShowToast(e.message)),
      subscribeDepartments(db, setDepartments, (e) => onShowToast(e.message)),
      subscribeDriveFolders(db, setDriveFolders, (e) => onShowToast(e.message)),
      subscribeQuizzes(db, isAdmin, setQuizzes, (e) => onShowToast(e.message)),
      subscribeQuizAttempts(db, uid, isAdmin, setAttempts, (e) => onShowToast(e.message)),
      subscribeVerifiedFiles(db, uid, setFiles, (e) => onShowToast(e.message)),
      subscribeNotifications(db, uid, isAdmin, setNotifications, (e) => onShowToast(e.message)),
      subscribeSupportMessages(db, uid, userRole, setSupportMessages, (e) => onShowToast(e.message))
    ];
    if (isAdmin) {
      unsubs.push(subscribeUsersDirectory(db, setUsersDirectory, (e) => onShowToast(e.message)));
      unsubs.push(subscribeRoleInvites(db, setRoleInvites, (e) => onShowToast(e.message)));
    } else {
      setUsersDirectory([]);
      setRoleInvites([]);
    }
    if (userRole === 'SUPER_ADMIN') {
      unsubs.push(subscribeAuditLogs(db, setAuditLogs, (e) => onShowToast(e.message)));
    } else {
      setAuditLogs([]);
    }
    return () => unsubs.forEach((u) => u && u());
  }, [db, uid, userRole, isAdmin, isDisabledNonSuper]);

  const refreshDriveNow = async (foldersToSync = driveFolders) => {
    if (!window.unicoreDesktop?.syncDriveFolders) return;
    if (!foldersToSync.length) {
      setDriveFiles([]);
      return;
    }
    setIsDriveRefreshing(true);
    try {
      const res = await window.unicoreDesktop.syncDriveFolders(foldersToSync);
      if (res?.error && (!res.files || res.files.length === 0)) {
        onShowToast(res.error);
      }
      setDriveFiles(res?.files || []);
    } catch (err) {
      onShowToast(err.message);
    } finally {
      setIsDriveRefreshing(false);
    }
  };

  useEffect(() => {
    refreshDriveNow(driveFolders);
  }, [driveFolders]);

  // Desktop Notification toast on newly arriving notifications
  useEffect(() => {
    if (!notifications.length) return;
    const state = seenNotifsRef.current;
    if (!state.initialized) {
      notifications.forEach((n) => state.ids.add(n.id));
      state.initialized = true;
      return;
    }
    const fresh = notifications.filter((n) => !state.ids.has(n.id));
    fresh.forEach((n) => state.ids.add(n.id));
    if (prefs.desktopNotificationsEnabled && typeof Notification !== 'undefined' && Notification.permission === 'granted') {
      fresh
        .filter((n) => !prefs.readNotificationIds.includes(n.id) && n.senderUid !== uid)
        .slice(0, 3)
        .forEach((n) => {
          new Notification(n.title, { body: n.body, icon: '/assets/icon.png' });
        });
    }
  }, [notifications, prefs.desktopNotificationsEnabled, prefs.readNotificationIds, uid]);

  if (isDisabledNonSuper) {
    return (
      <div className="auth-page">
        <div className="m3-card accent-error" style={{ maxWidth: '460px', alignItems: 'center', textAlign: 'center', padding: '28px' }}>
          <Ban size={48} style={{ color: 'var(--error)' }} />
          <h2 style={{ margin: 0, color: 'var(--on-error-container)' }}>{strings.accountSuspendedTitle}</h2>
          <p style={{ margin: 0, color: 'var(--on-error-container)', lineHeight: 1.55 }}>
            {strings.accountSuspendedBody}
          </p>
          <button type="button" className="btn btn-primary" style={{ width: '100%' }} onClick={() => signOutUnified(auth)}>
            <LogOut size={17} />
            <span>{strings.signOutAction}</span>
          </button>
        </div>
      </div>
    );
  }

  const unreadCount = notifications.filter((n) => !prefs.readNotificationIds.includes(n.id)).length;
  const roleLabel =
    userRole === 'SUPER_ADMIN'
      ? strings.roleSuperAdmin
      : userRole === 'ADMIN'
        ? strings.roleAdmin
        : strings.roleStudent;

  const navItems = [
    { id: 'COURSES', label: strings.navCourses, icon: School },
    { id: 'QUIZZES', label: strings.navQuizzes, icon: ClipboardCheck },
    { id: 'AI_TUTOR', label: strings.navTutor, icon: Sparkles },
    { id: 'VAULT', label: strings.navFiles, icon: FolderKanban },
    { id: 'ACCOUNT', label: strings.navAccount, icon: UserCheck }
  ];

  return (
    <div className="unicore-app-shell">
      <header className="unicore-topbar">
        <div className="topbar-brand">
          <img src="/assets/icon.png" alt="UniCore" className="topbar-logo" />
          <span className="topbar-title">
            {strings.appName} • {roleLabel}
          </span>
        </div>

        <div className="topbar-actions">
          <button
            type="button"
            className="btn btn-text"
            style={{ fontWeight: 700 }}
            onClick={() =>
              setPrefs((p) => ({
                ...p,
                language: p.language === 'ARABIC' ? 'ENGLISH' : 'ARABIC'
              }))
            }
          >
            {strings.switchLanguageShort}
          </button>

          <button type="button" className="icon-btn" onClick={onCycleTheme} title={strings.themeLabel}>
            {prefs.themeMode === 'SYSTEM' ? (
              <SunMoon size={20} />
            ) : prefs.themeMode === 'DARK' ? (
              <Moon size={20} />
            ) : (
              <Sun size={20} />
            )}
          </button>

          <button
            type="button"
            className="icon-btn"
            onClick={() => setShowNotifications(true)}
            title={strings.notificationsTitle}
          >
            <Bell size={20} />
            {unreadCount > 0 ? <span className="badge-dot">{unreadCount > 9 ? '9+' : unreadCount}</span> : null}
          </button>
        </div>
      </header>

      <div className="unicore-body">
        <aside className="unicore-rail">
          {navItems.map((item) => {
            const IconComp = item.icon;
            return (
              <button
                key={item.id}
                type="button"
                className={`rail-item ${tab === item.id ? 'active' : ''}`}
                onClick={() => setTab(item.id)}
              >
                <IconComp size={20} />
                <span>{item.label}</span>
              </button>
            );
          })}
        </aside>

        <main className="unicore-content-wrap">
          <div className="unicore-content">
            {tab === 'COURSES' ? (
              <CoursesView
                strings={strings}
                db={db}
                auth={auth}
                userRole={userRole}
                courses={courses}
                departments={departments}
                driveFolders={driveFolders}
                driveFiles={driveFiles}
                isDriveRefreshing={isDriveRefreshing}
                onRefreshDrive={() => refreshDriveNow(driveFolders)}
                onShowToast={onShowToast}
              />
            ) : null}

            {tab === 'QUIZZES' ? (
              <QuizzesView
                strings={strings}
                db={db}
                auth={auth}
                userRole={userRole}
                courses={courses}
                quizzes={quizzes}
                attempts={attempts}
                onShowToast={onShowToast}
              />
            ) : null}

            {tab === 'AI_TUTOR' ? (
              <AiTutorView
                strings={strings}
                language={prefs.language}
                clients={clients}
                userProfile={userProfile}
                systemSettings={systemSettings}
                onShowToast={onShowToast}
              />
            ) : null}

            {tab === 'VAULT' ? (
              <FilesVaultView
                strings={strings}
                db={db}
                auth={auth}
                files={files}
                onShowToast={onShowToast}
              />
            ) : null}

            {tab === 'ACCOUNT' ? (
              <AccountConsoleView
                strings={strings}
                db={db}
                auth={auth}
                currentUserId={uid}
                userRole={userRole}
                userProfile={userProfile}
                systemSettings={systemSettings}
                language={prefs.language}
                themeMode={prefs.themeMode}
                desktopNotificationsEnabled={prefs.desktopNotificationsEnabled}
                onSetLanguage={(language) => setPrefs((p) => ({ ...p, language }))}
                onSetThemeMode={(themeMode) => setPrefs((p) => ({ ...p, themeMode }))}
                onSetDesktopNotifications={(desktopNotificationsEnabled) =>
                  setPrefs((p) => ({ ...p, desktopNotificationsEnabled }))
                }
                usersDirectory={usersDirectory}
                roleInvites={roleInvites}
                supportMessages={supportMessages}
                auditLogs={auditLogs}
                onSignOut={() => signOutUnified(auth)}
                onShowToast={onShowToast}
              />
            ) : null}
          </div>
        </main>
      </div>

      <nav className="unicore-bottom-nav">
        {navItems.map((item) => {
          const IconComp = item.icon;
          return (
            <button
              key={item.id}
              type="button"
              className={`bottom-nav-item ${tab === item.id ? 'active' : ''}`}
              onClick={() => setTab(item.id)}
            >
              <IconComp size={19} />
              <span>{item.label}</span>
            </button>
          );
        })}
      </nav>

      {showNotifications ? (
        <NotificationsDrawer
          strings={strings}
          db={db}
          auth={auth}
          userRole={userRole}
          notifications={notifications}
          readIds={prefs.readNotificationIds}
          onMarkRead={(id) =>
            setPrefs((p) => ({
              ...p,
              readNotificationIds: p.readNotificationIds.includes(id)
                ? p.readNotificationIds
                : [...p.readNotificationIds, id]
            }))
          }
          onMarkAllRead={(ids) =>
            setPrefs((p) => ({
              ...p,
              readNotificationIds: Array.from(new Set([...p.readNotificationIds, ...ids]))
            }))
          }
          onClose={() => setShowNotifications(false)}
          onShowToast={onShowToast}
        />
      ) : null}
    </div>
  );
}
