import React, { useRef, useState } from 'react';
import { Copy, PauseCircle, Send, Sparkles, Square, X } from 'lucide-react';
import { askAiTutorStream } from '../data/aiTutorService';
import { incrementDailyCounter } from '../data/firestoreService';

export default function AiTutorView({
  strings,
  language,
  clients,
  userProfile,
  systemSettings,
  onShowToast
}) {
  const [prompt, setPrompt] = useState('');
  const [status, setStatus] = useState('IDLE'); // IDLE | LOADING | STREAMING | SUCCESS | ERROR | PAUSED
  const [answerText, setAnswerText] = useState('');
  const [errorMsg, setErrorMsg] = useState('');
  const abortRef = useRef(null);

  const aiUsedToday = Number(userProfile?.aiCallsToday || 0);
  const aiCap = Math.max(1, Number(systemSettings?.dailyAiLimitPerUser || 20));
  const aiDisabledGlobally = systemSettings?.aiEnabled === false || systemSettings?.zeroCostLockActive === true;
  const limitReached = aiUsedToday >= aiCap || aiDisabledGlobally;
  const pct = Math.min(100, Math.round((aiUsedToday / aiCap) * 100));
  const isBusy = status === 'LOADING' || status === 'STREAMING';

  const handleAsk = async () => {
    const clean = prompt.trim();
    if (!clean) return;
    if (limitReached) {
      setStatus('PAUSED');
      return;
    }

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setStatus('LOADING');
    setAnswerText('');
    setErrorMsg('');

    try {
      const finalAnswer = await askAiTutorStream({
        app: clients.app,
        config: clients.config,
        prompt: clean,
        language,
        signal: controller.signal,
        onPartial: (partial) => {
          setStatus('STREAMING');
          setAnswerText(partial);
        }
      });
      setAnswerText(finalAnswer);
      setStatus('SUCCESS');
      if (clients.auth.currentUser?.uid) {
        incrementDailyCounter(clients.db, clients.auth.currentUser.uid, true).catch(() => {});
      }
    } catch (err) {
      if (err?.name === 'AbortError') {
        setStatus(answerText ? 'SUCCESS' : 'IDLE');
        return;
      }
      setStatus('ERROR');
      setErrorMsg(err?.message || 'AI Tutor error');
    }
  };

  const handleCancel = () => {
    abortRef.current?.abort();
    setStatus(answerText ? 'SUCCESS' : 'IDLE');
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
      <div className="tutor-banner">
        <img src="/assets/img_ai_tutor_banner.jpg" alt={strings.tutorBannerTitle} />
        <div className="tutor-banner-overlay">
          <div style={{ color: 'var(--secondary)', fontSize: '0.82rem', fontWeight: 700 }}>
            {strings.tutorBannerSubtitle}
          </div>
          <div style={{ color: '#FFFFFF', fontSize: '1.45rem', fontWeight: 800 }}>
            {strings.tutorBannerTitle}
          </div>
        </div>
      </div>

      <div className="m3-card">
        <div className="row-between">
          <span style={{ fontWeight: 700 }}>{strings.dailyAiQuota}</span>
          <span
            style={{
              fontWeight: 700,
              color: limitReached
                ? 'var(--error)'
                : pct >= 80
                  ? 'var(--tertiary)'
                  : 'var(--secondary)'
            }}
          >
            {aiUsedToday} / {aiCap}
          </span>
        </div>
        <div className="m3-progress-track">
          <div
            className="m3-progress-fill"
            style={{
              width: `${pct}%`,
              background: limitReached
                ? 'var(--error)'
                : pct >= 80
                  ? 'var(--tertiary)'
                  : 'var(--secondary)'
            }}
          />
        </div>
      </div>

      <div className="chip-scroll">
        {[strings.quickPrompt1, strings.quickPrompt2, strings.quickPrompt3].map((q) => (
          <button key={q} type="button" className="m3-chip" onClick={() => setPrompt(q)}>
            {q}
          </button>
        ))}
      </div>

      <div className="field-group" style={{ position: 'relative' }}>
        <label className="field-label">{strings.askPromptLabel}</label>
        <textarea
          className="m3-textarea"
          rows={3}
          placeholder={strings.askPromptPlaceholder}
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
        />
        {prompt ? (
          <button
            type="button"
            className="icon-btn"
            style={{ position: 'absolute', bottom: '8px', insetInlineEnd: '8px' }}
            onClick={() => {
              setPrompt('');
              setStatus('IDLE');
              setAnswerText('');
            }}
            title={strings.clearLabel}
          >
            <X size={16} />
          </button>
        ) : null}
      </div>

      <div className="row-gap">
        <button
          type="button"
          className="btn btn-primary"
          style={{ flex: 1, minHeight: '48px' }}
          disabled={isBusy || limitReached || !prompt.trim()}
          onClick={handleAsk}
        >
          {isBusy ? (
            <>
              <span className="spinner" />
              <span>{strings.tutorConnecting}</span>
            </>
          ) : (
            <>
              <Send size={17} />
              <span>{strings.askTutorButton}</span>
            </>
          )}
        </button>

        {isBusy ? (
          <button
            type="button"
            className="btn btn-outlined"
            style={{ minHeight: '48px' }}
            onClick={handleCancel}
          >
            <Square size={16} />
            <span>{strings.stopAiAction}</span>
          </button>
        ) : null}
      </div>

      {status === 'IDLE' ? (
        <div className="m3-card" style={{ alignItems: 'center', textAlign: 'center', padding: '26px' }}>
          <Sparkles size={36} style={{ color: 'var(--primary)' }} />
          <div style={{ fontWeight: 700 }}>{strings.tutorIdleTitle}</div>
          <div className="muted">{strings.tutorIdleSub}</div>
        </div>
      ) : null}

      {status === 'PAUSED' ? (
        <div className="m3-card pill-tertiary" style={{ flexDirection: 'row', alignItems: 'center' }}>
          <PauseCircle size={20} />
          <span>{strings.dailyAiQuota}: {aiUsedToday}/{aiCap}</span>
        </div>
      ) : null}

      {status === 'ERROR' ? (
        <div className="m3-card accent-error" style={{ color: 'var(--on-error-container)' }}>
          {errorMsg}
        </div>
      ) : null}

      {(status === 'STREAMING' || status === 'SUCCESS') && answerText ? (
        <div className="m3-card" style={{ borderColor: 'color-mix(in srgb, var(--primary) 35%, transparent)' }}>
          <div className="row-between">
            <span className="pill pill-primary">{strings.tutorBannerTitle}</span>
            <button
              type="button"
              className="btn btn-text"
              onClick={() => {
                navigator.clipboard?.writeText(answerText);
                onShowToast(strings.copyAnswer);
              }}
            >
              <Copy size={15} />
              <span>{strings.copyAnswer}</span>
            </button>
          </div>
          <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.7 }}>{answerText}</div>
        </div>
      ) : null}
    </div>
  );
}
