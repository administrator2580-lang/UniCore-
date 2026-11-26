import React, { useEffect, useRef, useState } from 'react';
import {
  AlertTriangle,
  CalendarClock,
  CheckCircle2,
  ClipboardCheck,
  Image as ImageIcon,
  Plus,
  RotateCcw,
  ShieldAlert,
  Trash2,
  X
} from 'lucide-react';
import {
  cancelQuizForViolation,
  createQuizWithAnswerKey,
  deleteQuiz,
  gradeQuizAttemptAsAdmin,
  loadQuizImageDataUrl,
  resetCancelledAttemptAsSuperAdmin,
  submitQuizAttempt
} from '../data/firestoreService';

const LETTERS = ['A', 'B', 'C', 'D', 'E', 'F'];

function formatDateTime(ms) {
  if (!ms) return '';
  try {
    return new Date(ms).toLocaleString([], {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  } catch {
    return '';
  }
}

export default function QuizzesView({
  strings,
  db,
  auth,
  userRole,
  courses,
  quizzes,
  attempts,
  onShowToast
}) {
  const canManage = userRole !== 'STUDENT';
  const [activeQuiz, setActiveQuiz] = useState(null);
  const [showCreateQuiz, setShowCreateQuiz] = useState(false);
  const [violationBanner, setViolationBanner] = useState(null);
  const [nowMs, setNowMs] = useState(() => Date.now());

  useEffect(() => {
    const id = setInterval(() => setNowMs(Date.now()), 15_000);
    return () => clearInterval(id);
  }, []);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
      <div className="row-between" style={{ flexWrap: 'wrap' }}>
        <div>
          <h2 style={{ margin: 0 }}>{strings.quizzesTitle}</h2>
          <div className="muted">{strings.quizzesSubtitle}</div>
        </div>
        {canManage ? (
          <button
            type="button"
            className="btn btn-primary"
            disabled={courses.length === 0}
            onClick={() => setShowCreateQuiz(true)}
          >
            <Plus size={18} />
            <span>{strings.createQuizAction}</span>
          </button>
        ) : null}
      </div>

      {violationBanner ? (
        <div className="m3-card accent-error" style={{ color: 'var(--on-error-container)', gap: '8px' }}>
          <div className="row-between">
            <div className="row-gap" style={{ fontWeight: 800 }}>
              <ShieldAlert size={20} />
              <span>{violationBanner.title}</span>
            </div>
            <button type="button" className="icon-btn" onClick={() => setViolationBanner(null)}>
              <X size={16} />
            </button>
          </div>
          <div>{violationBanner.reason}</div>
        </div>
      ) : null}

      {quizzes.length === 0 ? (
        <div className="m3-card" style={{ alignItems: 'center', textAlign: 'center', padding: '28px 18px' }}>
          <ClipboardCheck size={40} style={{ color: 'var(--primary)' }} />
          <div style={{ fontWeight: 700 }}>{strings.noQuizzesTitle}</div>
          <div className="muted">
            {userRole === 'STUDENT' ? strings.noQuizzesStudentSub : strings.noQuizzesAdminSub}
          </div>
        </div>
      ) : (
        quizzes.map((quiz) => {
          const matchingAttempt = attempts.find((a) => a.quizId === quiz.id);
          const alreadyAttempted = Boolean(matchingAttempt);
          const isViolation = matchingAttempt?.status === 'CANCELLED_VIOLATION';
          const startMs = Number(quiz.startAtMillis || 0);
          const endMs = Number(quiz.endAtMillis || 0);
          const notStarted = !canManage && startMs > 0 && nowMs < startMs;
          const closed = !canManage && endMs > 0 && nowMs > endMs;

          let actionLabel = strings.takeQuizAction;
          if (isViolation) actionLabel = strings.quizViolationTitle;
          else if (alreadyAttempted) actionLabel = strings.submittedLocked;
          else if (notStarted) actionLabel = `${strings.quizNotStartedYet}: ${formatDateTime(startMs)}`;
          else if (closed) actionLabel = strings.quizEndedLabel;

          return (
            <div key={quiz.id} className="m3-card">
              <div className="row-between" style={{ flexWrap: 'wrap' }}>
                <h3 style={{ margin: 0 }}>{quiz.title}</h3>
                <div className="row-gap">
                  {quiz.hasImage ? (
                    <span className="pill pill-secondary">
                      <ImageIcon size={13} />
                    </span>
                  ) : null}
                  <span className="pill pill-primary">
                    {quiz.durationMinutes} {strings.minutesShort} • {(quiz.questionPrompts || []).length}Q
                  </span>
                  {canManage ? (
                    <button
                      type="button"
                      className="icon-btn"
                      style={{ color: 'var(--error)' }}
                      onClick={() => deleteQuiz(db, quiz.id).catch((e) => onShowToast(e.message))}
                      title={strings.deleteLabel}
                    >
                      <Trash2 size={16} />
                    </button>
                  ) : null}
                </div>
              </div>

              {(startMs > 0 || endMs > 0) ? (
                <div className="row-gap muted" style={{ fontSize: '0.82rem' }}>
                  <CalendarClock size={14} />
                  <span>
                    {startMs > 0 ? `${strings.quizStartLabel}: ${formatDateTime(startMs)}` : ''}
                    {startMs > 0 && endMs > 0 ? ' • ' : ''}
                    {endMs > 0 ? `${strings.quizEndLabel}: ${formatDateTime(endMs)}` : ''}
                  </span>
                </div>
              ) : null}

              <div className="muted">{quiz.questionPrompts?.[0] || ''}</div>

              {isViolation && matchingAttempt?.cancellationReason ? (
                <div className="pill pill-error" style={{ borderRadius: '10px', padding: '8px 12px', alignSelf: 'flex-start' }}>
                  <AlertTriangle size={15} />
                  <span>{matchingAttempt.cancellationReason}</span>
                </div>
              ) : null}

              <div>
                <button
                  type="button"
                  className={`btn ${isViolation ? 'btn-danger-outlined' : 'btn-primary'}`}
                  disabled={alreadyAttempted || notStarted || closed}
                  onClick={() => setActiveQuiz(quiz)}
                >
                  {actionLabel}
                </button>
              </div>
            </div>
          );
        })
      )}

      <h2 style={{ margin: '8px 0 0' }}>
        {userRole === 'STUDENT' ? strings.mySubmissionsTitle : strings.adminGradingTitle}
      </h2>

      {attempts.length === 0 ? (
        <div className="muted">{strings.noAttemptsYet}</div>
      ) : (
        attempts.map((attempt) => {
          const isViolation = attempt.status === 'CANCELLED_VIOLATION';
          return (
            <div key={attempt.id} className="m3-card">
              <div className="row-between" style={{ flexWrap: 'wrap' }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                  <div style={{ fontWeight: 700, fontSize: '1.05rem' }}>{attempt.quizTitle}</div>
                  <div className="muted">
                    {attempt.studentName} • {attempt.status}
                  </div>
                  {isViolation && attempt.cancellationReason ? (
                    <div style={{ color: 'var(--error)', fontWeight: 600, fontSize: '0.86rem' }}>
                      {attempt.cancellationReason}
                    </div>
                  ) : null}
                  {attempt.graded && !isViolation ? (
                    <div className="row-gap" style={{ color: 'var(--secondary)', fontWeight: 700, fontSize: '0.88rem' }}>
                      <CheckCircle2 size={16} />
                      <span>
                        {strings.verifiedScoreLabel}: {attempt.score}%
                      </span>
                    </div>
                  ) : null}
                </div>
                <div className="row-gap">
                  {canManage && !attempt.graded ? (
                    <button
                      type="button"
                      className="btn btn-outlined"
                      onClick={async () => {
                        try {
                          const score = await gradeQuizAttemptAsAdmin(db, auth, attempt);
                          onShowToast(`${strings.verifiedScoreLabel}: ${score}%`);
                        } catch (err) {
                          onShowToast(err.message);
                        }
                      }}
                    >
                      {strings.autoGradeAction}
                    </button>
                  ) : null}
                  {userRole === 'SUPER_ADMIN' && isViolation ? (
                    <button
                      type="button"
                      className="btn btn-outlined"
                      onClick={async () => {
                        try {
                          await resetCancelledAttemptAsSuperAdmin(db, auth, attempt);
                          onShowToast(strings.resetAttemptAction);
                        } catch (err) {
                          onShowToast(err.message);
                        }
                      }}
                    >
                      <RotateCcw size={14} />
                      <span>{strings.resetAttemptAction}</span>
                    </button>
                  ) : null}
                </div>
              </div>
            </div>
          );
        })
      )}

      {activeQuiz ? (
        <TakeQuizModal
          strings={strings}
          db={db}
          quiz={activeQuiz}
          isStudent={userRole === 'STUDENT'}
          onClose={() => setActiveQuiz(null)}
          onSubmit={async (selectedIndices) => {
            try {
              await submitQuizAttempt(db, auth, { quiz: activeQuiz, selectedIndices });
              setActiveQuiz(null);
              onShowToast(strings.submittedLocked);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
          onViolation={async (selectedIndices, reason) => {
            const quizRef = activeQuiz;
            setActiveQuiz(null);
            setViolationBanner({
              title: `${strings.quizViolationTitle}: ${quizRef.title}`,
              reason
            });
            try {
              await cancelQuizForViolation(db, auth, {
                quiz: quizRef,
                selectedIndices,
                reason
              });
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}

      {showCreateQuiz && courses.length > 0 ? (
        <CreateQuizModal
          strings={strings}
          courses={courses}
          onClose={() => setShowCreateQuiz(false)}
          onConfirm={async (payload) => {
            try {
              await createQuizWithAnswerKey(db, auth, payload);
              setShowCreateQuiz(false);
              onShowToast(strings.saveQuizAction);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}
    </div>
  );
}

function TakeQuizModal({ strings, db, quiz, isStudent, onClose, onSubmit, onViolation }) {
  const prompts = quiz.questionPrompts || [];
  const total = Math.max(1, prompts.length);
  const [index, setIndex] = useState(0);
  const [selected, setSelected] = useState(() => Array(total).fill(0));
  const [quizImgUrl, setQuizImgUrl] = useState(null);
  const [zoomed, setZoomed] = useState(false);

  const selectedRef = useRef(selected);
  selectedRef.current = selected;
  const submittedOrCancelledRef = useRef(false);

  useEffect(() => {
    let active = true;
    if (quiz.hasImage) {
      loadQuizImageDataUrl(db, quiz)
        .then((url) => {
          if (active && url) setQuizImgUrl(url);
        })
        .catch(() => {});
    }
    return () => {
      active = false;
    };
  }, [db, quiz]);

  // Strict Anti-Cheat: Leaving app/tab/window immediately cancels active student quiz
  useEffect(() => {
    if (!isStudent) return undefined;
    let armed = false;
    const armTimer = setTimeout(() => {
      armed = true;
    }, 650);

    const triggerViolation = (reason) => {
      if (!armed || submittedOrCancelledRef.current) return;
      submittedOrCancelledRef.current = true;
      onViolation(selectedRef.current, reason);
    };

    const onVisibility = () => {
      if (document.hidden) {
        triggerViolation(strings.quizViolationReasonDefault);
      }
    };
    const onBlur = () => {
      triggerViolation(strings.quizViolationReasonDefault);
    };

    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('blur', onBlur);
    return () => {
      clearTimeout(armTimer);
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('blur', onBlur);
    };
  }, [isStudent]);

  const options = String(quiz.optionBlocks?.[index] || 'A|B').split('|');

  return (
    <div className="modal-backdrop">
      <div className="modal-panel" style={{ maxWidth: '640px' }} onClick={(e) => e.stopPropagation()}>
        <div>
          <h3 style={{ margin: 0 }}>{quiz.title}</h3>
          <div style={{ color: 'var(--primary)', fontWeight: 600, fontSize: '0.88rem' }}>
            {strings.questionWord} {index + 1} {strings.ofWord} {total}
          </div>
        </div>

        {isStudent ? (
          <div className="pill pill-tertiary" style={{ borderRadius: '10px', padding: '8px 12px', fontSize: '0.82rem' }}>
            <AlertTriangle size={15} />
            <span>{strings.quizAntiCheatNotice}</span>
          </div>
        ) : null}

        {quizImgUrl ? (
          <img
            src={quizImgUrl}
            alt={quiz.imageName || quiz.title}
            onClick={() => setZoomed(!zoomed)}
            style={{
              width: '100%',
              maxHeight: zoomed ? '420px' : '210px',
              objectFit: 'contain',
              borderRadius: '10px',
              background: 'var(--surface-variant)',
              cursor: 'pointer'
            }}
          />
        ) : null}

        <div style={{ fontSize: '1.05rem', fontWeight: 600 }}>{prompts[index] || ''}</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
          {options.map((opt, idx) => (
            <label
              key={idx}
              className="m3-subsurface"
              style={{ flexDirection: 'row', alignItems: 'center', cursor: 'pointer' }}
            >
              <input
                type="radio"
                name={`q-${index}`}
                checked={selected[index] === idx}
                onChange={() => {
                  const next = [...selected];
                  next[index] = idx;
                  setSelected(next);
                }}
              />
              <span>{opt}</span>
            </label>
          ))}
        </div>
        <div className="row-between">
          {index > 0 ? (
            <button type="button" className="btn btn-text" onClick={() => setIndex(index - 1)}>
              {strings.prevQuestion}
            </button>
          ) : (
            <button
              type="button"
              className="btn btn-text"
              onClick={() => {
                if (isStudent) {
                  submittedOrCancelledRef.current = true;
                  onViolation(selected, strings.quizViolationReasonDefault);
                } else {
                  onClose();
                }
              }}
            >
              {strings.cancelAction}
            </button>
          )}
          {index < total - 1 ? (
            <button type="button" className="btn btn-primary" onClick={() => setIndex(index + 1)}>
              {strings.nextQuestion}
            </button>
          ) : (
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                submittedOrCancelledRef.current = true;
                onSubmit(selected);
              }}
            >
              {strings.submitQuizAction}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

function CreateQuizModal({ strings, courses, onClose, onConfirm }) {
  const [courseId, setCourseId] = useState(courses[0]?.id || '');
  const [title, setTitle] = useState('');
  const [durationMinutes, setDurationMinutes] = useState(15);
  const [startLocal, setStartLocal] = useState('');
  const [endLocal, setEndLocal] = useState('');
  const [imageFile, setImageFile] = useState(null);
  const [saving, setSaving] = useState(false);
  const [drafts, setDrafts] = useState([
    { prompt: '', options: ['', '', '', ''], correctIdx: 0 }
  ]);

  const updateDraft = (idx, patch) => {
    setDrafts((prev) => prev.map((d, i) => (i === idx ? { ...d, ...patch } : d)));
  };

  const updateOption = (qIdx, optIdx, value) => {
    setDrafts((prev) =>
      prev.map((d, i) => {
        if (i !== qIdx) return d;
        const nextOpts = [...d.options];
        nextOpts[optIdx] = value;
        return { ...d, options: nextOpts };
      })
    );
  };

  const addOptionSlot = (qIdx) => {
    setDrafts((prev) =>
      prev.map((d, i) => {
        if (i !== qIdx || d.options.length >= 6) return d;
        return { ...d, options: [...d.options, ''] };
      })
    );
  };

  const removeOptionSlot = (qIdx, optIdx) => {
    setDrafts((prev) =>
      prev.map((d, i) => {
        if (i !== qIdx || d.options.length <= 2) return d;
        const nextOpts = d.options.filter((_, oIdx) => oIdx !== optIdx);
        const nextCorrect = Math.min(d.correctIdx, nextOpts.length - 1);
        return { ...d, options: nextOpts, correctIdx: Math.max(0, nextCorrect) };
      })
    );
  };

  const pickImage = () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/png,image/jpeg';
    input.onchange = () => {
      const f = input.files?.[0];
      if (f) setImageFile(f);
    };
    input.click();
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form
        className="modal-panel"
        style={{ maxWidth: '640px' }}
        onClick={(e) => e.stopPropagation()}
        onSubmit={async (e) => {
          e.preventDefault();
          setSaving(true);
          const startAtMillis = startLocal ? new Date(startLocal).getTime() || 0 : 0;
          const endAtMillis = endLocal ? new Date(endLocal).getTime() || 0 : 0;
          await onConfirm({
            courseId,
            title,
            durationMinutes,
            startAtMillis,
            endAtMillis,
            imageFile,
            prompts: drafts.map((d) => d.prompt),
            optionBlocks: drafts.map((d) => d.options.map((x) => x.trim()).filter(Boolean).join('|')),
            correctIndices: drafts.map((d) => d.correctIdx)
          });
          setSaving(false);
        }}
      >
        <h3 style={{ margin: 0 }}>{strings.createQuizAction}</h3>
        <span className="field-label">{strings.selectCourseLabel}</span>
        <div className="chip-scroll">
          {courses.map((c) => (
            <button
              key={c.id}
              type="button"
              className={`m3-chip ${courseId === c.id ? 'selected' : ''}`}
              onClick={() => setCourseId(c.id)}
            >
              {c.title}
            </button>
          ))}
        </div>

        <div className="field-group">
          <label className="field-label">{strings.courseTitleLabel}</label>
          <input className="m3-input" value={title} onChange={(e) => setTitle(e.target.value)} required />
        </div>

        <span className="field-label">{strings.durationLabel}</span>
        <div className="chip-row">
          {[10, 15, 30, 60].map((mins) => (
            <button
              key={mins}
              type="button"
              className={`m3-chip ${durationMinutes === mins ? 'selected' : ''}`}
              onClick={() => setDurationMinutes(mins)}
            >
              {mins} {strings.minutesShort}
            </button>
          ))}
        </div>

        <span className="field-label">{strings.quizScheduleLabel}</span>
        <div className="row-gap" style={{ flexWrap: 'wrap' }}>
          <div className="field-group" style={{ flex: '1 1 200px' }}>
            <label className="field-label">{strings.quizStartLabel}</label>
            <input
              className="m3-input"
              type="datetime-local"
              value={startLocal}
              onChange={(e) => setStartLocal(e.target.value)}
            />
          </div>
          <div className="field-group" style={{ flex: '1 1 200px' }}>
            <label className="field-label">{strings.quizEndLabel}</label>
            <input
              className="m3-input"
              type="datetime-local"
              value={endLocal}
              onChange={(e) => setEndLocal(e.target.value)}
            />
          </div>
        </div>

        <span className="field-label">{strings.quizAttachImageLabel}</span>
        <div className="row-gap" style={{ flexWrap: 'wrap' }}>
          <button type="button" className="btn btn-outlined" onClick={pickImage}>
            <ImageIcon size={16} />
            <span>{strings.attachPhotoAction}</span>
          </button>
          {imageFile ? (
            <div className="row-gap pill pill-secondary" style={{ padding: '6px 10px' }}>
              <span>{imageFile.name}</span>
              <button
                type="button"
                className="icon-btn"
                style={{ width: '24px', height: '24px' }}
                onClick={() => setImageFile(null)}
              >
                <X size={13} />
              </button>
            </div>
          ) : null}
        </div>

        {drafts.map((draft, qIdx) => (
          <div key={qIdx} className="m3-subsurface">
            <div className="row-between">
              <span style={{ fontWeight: 700, color: 'var(--primary)', fontSize: '0.85rem' }}>
                {strings.questionWord} {qIdx + 1}
              </span>
              {draft.options.length < 6 ? (
                <button
                  type="button"
                  className="btn btn-text"
                  style={{ minHeight: '30px', padding: '4px 10px', fontSize: '0.8rem' }}
                  onClick={() => addOptionSlot(qIdx)}
                >
                  {strings.addOptionAction}
                </button>
              ) : null}
            </div>
            <input
              className="m3-input"
              placeholder={`${strings.questionWord} ${qIdx + 1}`}
              value={draft.prompt}
              onChange={(e) => updateDraft(qIdx, { prompt: e.target.value })}
              required
            />
            {draft.options.map((optVal, optIdx) => (
              <div key={optIdx} className="row-gap">
                <input
                  className="m3-input"
                  style={{ flex: 1 }}
                  placeholder={LETTERS[optIdx] || String(optIdx + 1)}
                  value={optVal}
                  onChange={(e) => updateOption(qIdx, optIdx, e.target.value)}
                  required={optIdx < 2}
                />
                {draft.options.length > 2 ? (
                  <button
                    type="button"
                    className="icon-btn"
                    style={{ width: '32px', height: '32px', color: 'var(--error)' }}
                    onClick={() => removeOptionSlot(qIdx, optIdx)}
                  >
                    <X size={14} />
                  </button>
                ) : null}
              </div>
            ))}
            <div className="row-gap" style={{ flexWrap: 'wrap' }}>
              {draft.options.map((_, optIdx) => (
                <label key={optIdx} className="row-gap" style={{ cursor: 'pointer', fontSize: '0.85rem' }}>
                  <input
                    type="radio"
                    name={`correct-${qIdx}`}
                    checked={draft.correctIdx === optIdx}
                    onChange={() => updateDraft(qIdx, { correctIdx: optIdx })}
                  />
                  <span>{LETTERS[optIdx] || optIdx + 1}</span>
                </label>
              ))}
            </div>
          </div>
        ))}

        {drafts.length < 15 ? (
          <button
            type="button"
            className="btn btn-outlined"
            onClick={() =>
              setDrafts((prev) => [
                ...prev,
                { prompt: '', options: ['', '', '', ''], correctIdx: 0 }
              ])
            }
          >
            {strings.addQuestionAction}
          </button>
        ) : null}

        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>
            {strings.cancelAction}
          </button>
          <button type="submit" className="btn btn-primary" disabled={saving}>
            {saving ? <span className="spinner" /> : strings.saveQuizAction}
          </button>
        </div>
      </form>
    </div>
  );
}
