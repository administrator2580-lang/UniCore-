import React, { useState } from 'react';
import {
  Globe,
  LogIn,
  Moon,
  School,
  Sun,
  SunMoon
} from 'lucide-react';
import {
  formatAuthError,
  getFirebaseRuntimeConfig,
  reinitializeFirebaseClients,
  saveFirebaseOverrideConfig,
  signInWithGoogleUnified
} from '../firebase/config';

export default function AuthView({
  strings,
  language,
  themeMode,
  onToggleLanguage,
  onCycleTheme,
  clients,
  onClientsUpdated,
  onShowToast
}) {
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');
  const [showConfigModal, setShowConfigModal] = useState(() => !clients);
  const initialCfg = getFirebaseRuntimeConfig();
  const [apiKeyInput, setApiKeyInput] = useState(initialCfg.apiKey);
  const [appIdInput, setAppIdInput] = useState(initialCfg.appId);
  const [projectIdInput, setProjectIdInput] = useState(initialCfg.projectId);
  const [dbIdInput, setDbIdInput] = useState(initialCfg.firestoreDatabaseId);
  const [appCheckTokenInput, setAppCheckTokenInput] = useState(initialCfg.appCheckDebugToken || '');
  const [webRecaptchaInput, setWebRecaptchaInput] = useState(initialCfg.webRecaptchaKey || '');

  const isDesktop = Boolean(window.unicoreDesktop?.isElectron);

  const handleGoogleSignIn = async () => {
    if (!clients?.auth) {
      setShowConfigModal(true);
      setErrorMsg(strings.firebaseSetupMissing);
      return;
    }
    setLoading(true);
    setErrorMsg('');
    try {
      await signInWithGoogleUnified(clients.auth);
    } catch (err) {
      setErrorMsg(formatAuthError(err));
    } finally {
      setLoading(false);
    }
  };

  const handleSaveConfig = async (e) => {
    e.preventDefault();
    saveFirebaseOverrideConfig({
      apiKey: apiKeyInput.trim(),
      appId: appIdInput.trim(),
      projectId: projectIdInput.trim() || 'gen-lang-client-0870704520',
      firestoreDatabaseId:
        dbIdInput.trim() || 'ai-studio-android-unicore-9b169dd7-0f78-40f7-9411-0587832d12de',
      appCheckDebugToken: appCheckTokenInput.trim(),
      webRecaptchaKey: webRecaptchaInput.trim()
    });
    const nextClients = await reinitializeFirebaseClients();
    onClientsUpdated(nextClients);
    setShowConfigModal(false);
    onShowToast(language === 'ARABIC' ? 'تم حفظ إعدادات الربط' : 'Connection settings saved');
  };

  return (
    <div className="auth-page">
      <div className="auth-container">
        <div className="row-between">
          <button
            type="button"
            className="btn btn-outlined"
            onClick={() => onToggleLanguage(language === 'ARABIC' ? 'ENGLISH' : 'ARABIC')}
          >
            <Globe size={18} />
            <span>{strings.switchLanguageShort}</span>
          </button>

          <div className="row-gap">
            <span className="pill pill-primary">
              {isDesktop ? strings.desktopModeBadge : strings.webModeBadge}
            </span>
            <button
              type="button"
              className="icon-btn tonal"
              onClick={onCycleTheme}
              title={strings.themeLabel}
              aria-label={strings.themeLabel}
            >
              {themeMode === 'SYSTEM' ? (
                <SunMoon size={19} />
              ) : themeMode === 'DARK' ? (
                <Moon size={19} />
              ) : (
                <Sun size={19} />
              )}
            </button>
          </div>
        </div>

        <div className="auth-hero-card">
          <img src="/assets/img_unicore_hero.jpg" alt={strings.appName} />
          <div className="auth-hero-overlay" />
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '12px', textAlign: 'center' }}>
          <div className="pill pill-primary" style={{ padding: '8px 16px', fontSize: '1rem', borderRadius: '14px' }}>
            <School size={20} />
            <span>{strings.appName}</span>
          </div>
          <h1 style={{ margin: 0, fontSize: '1.35rem', fontWeight: 700, lineHeight: 1.45 }}>
            {strings.appSubtitle}
          </h1>
        </div>

        {errorMsg ? (
          <div className="m3-card accent-error" style={{ padding: '14px', color: 'var(--on-error-container)' }}>
            {errorMsg}
          </div>
        ) : null}

        <button
          type="button"
          className="btn btn-primary"
          disabled={loading}
          onClick={handleGoogleSignIn}
          style={{ width: '100%', minHeight: '54px', fontSize: '1rem' }}
        >
          {loading ? (
            <span className="spinner" />
          ) : (
            <>
              <LogIn size={20} />
              <span>{strings.continueWithGoogle}</span>
            </>
          )}
        </button>
      </div>

      {showConfigModal ? (
        <div className="modal-backdrop" onClick={() => setShowConfigModal(false)}>
          <form
            className="modal-panel"
            onClick={(e) => e.stopPropagation()}
            onSubmit={handleSaveConfig}
          >
            <h3 style={{ margin: 0 }}>{strings.firebaseSetupTitle}</h3>
            {!clients ? (
              <div className="pill pill-tertiary" style={{ padding: '10px', borderRadius: '10px' }}>
                {strings.firebaseSetupMissing}
              </div>
            ) : null}
            <div className="field-group">
              <label className="field-label">Firebase Web API Key</label>
              <input
                className="m3-input"
                value={apiKeyInput}
                onChange={(e) => setApiKeyInput(e.target.value)}
                placeholder="AIza..."
                required
              />
            </div>
            <div className="field-group">
              <label className="field-label">Firebase Web App ID (AI Tutor)</label>
              <input
                className="m3-input"
                value={appIdInput}
                onChange={(e) => setAppIdInput(e.target.value)}
                placeholder="1:324526157499:web:..."
              />
            </div>
            <div className="field-group">
              <label className="field-label">Project ID</label>
              <input
                className="m3-input"
                value={projectIdInput}
                onChange={(e) => setProjectIdInput(e.target.value)}
              />
            </div>
            <div className="field-group">
              <label className="field-label">Firestore Database ID</label>
              <input
                className="m3-input"
                value={dbIdInput}
                onChange={(e) => setDbIdInput(e.target.value)}
              />
            </div>
            <div className="field-group">
              <label className="field-label">App Check Debug Token (اختياري)</label>
              <input
                className="m3-input"
                value={appCheckTokenInput}
                onChange={(e) => setAppCheckTokenInput(e.target.value)}
                placeholder="UUID من Firebase App Check -> Manage debug tokens"
              />
            </div>
            <div className="field-group">
              <label className="field-label">Web reCAPTCHA v3 Site Key (اختياري)</label>
              <input
                className="m3-input"
                value={webRecaptchaInput}
                onChange={(e) => setWebRecaptchaInput(e.target.value)}
                placeholder="6L..."
              />
            </div>
            <div className="row-between" style={{ justifyContent: 'flex-end' }}>
              <button type="button" className="btn btn-text" onClick={() => setShowConfigModal(false)}>
                {strings.cancelAction}
              </button>
              <button type="submit" className="btn btn-primary">
                {strings.savePolicyButton}
              </button>
            </div>
          </form>
        </div>
      ) : null}
    </div>
  );
}
