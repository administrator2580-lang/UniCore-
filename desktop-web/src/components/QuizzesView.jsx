import React, { useState } from 'react';
import { CheckCircle2, ClipboardCheck, Plus, Trash2 } from 'lucide-react';
import {
  createQuizWithAnswerKey,
  deleteQuiz,
  gradeQuizAttemptAsAdmin,
  submitQuizAttempt
} from '../data/firestoreService';

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
          const alreadySubmitted = attempts.some((a) => a.quizId === quiz.id);
          return (
            <div key={quiz.id} className="m3-card">
              <div className="row-between" style={{ flexWrap: 'wrap' }}>
                <h3 style={{ margin: 0 }}>{quiz.title}</h3>
                <div className="row-gap">
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
              <div className="muted">{quiz.questionPrompts?.[0] || ''}</div>
              <div>
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={alreadySubmitted}
                  onClick={() => setActiveQuiz(quiz)}
                >
                  {alreadySubmitted ? strings.submittedLocked : strings.takeQuizAction}
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
        attempts.map((attempt) => (
          <div key={attempt.id} className="m3-card">
            <div className="row-between" style={{ flexWrap: 'wrap' }}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                <div style={{ fontWeight: 700, fontSize: '1.05rem' }}>{attempt.quizTitle}</div>
                <div className="muted">
                  {attempt.studentName} • {attempt.status}
                </div>
                {attempt.graded ? (
                  <div className="row-gap" style={{ color: 'var(--secondary)', fontWeight: 700, fontSize: '0.88rem' }}>
                    <CheckCircle2 size={16} />
                    <span>
                      {strings.verifiedScoreLabel}: {attempt.score}%
                    </span>
                  </div>
                ) : null}
              </div>
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
            </div>
          </div>
        ))
      )}

      {activeQuiz ? (
        <TakeQuizModal
          strings={strings}
          quiz={activeQuiz}
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

function TakeQuizModal({ strings, quiz, onClose, onSubmit }) {
  const prompts = quiz.questionPrompts || [];
  const total = Math.max(1, prompts.length);
  const [index, setIndex] = useState(0);
  const [selected, setSelected] = useState(() => Array(total).fill(0));
  const options = String(quiz.optionBlocks?.[index] || 'A|B').split('|');

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
        <div>
          <h3 style={{ margin: 0 }}>{quiz.title}</h3>
          <div style={{ color: 'var(--primary)', fontWeight: 600, fontSize: '0.88rem' }}>
            {strings.questionWord} {index + 1} {strings.ofWord} {total}
          </div>
        </div>
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
            <button type="button" className="btn btn-text" onClick={onClose}>
              {strings.cancelAction}
            </button>
          )}
          {index < total - 1 ? (
            <button type="button" className="btn btn-primary" onClick={() => setIndex(index + 1)}>
              {strings.nextQuestion}
            </button>
          ) : (
            <button type="button" className="btn btn-primary" onClick={() => onSubmit(selected)}>
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
  const [drafts, setDrafts] = useState([{ prompt: '', optA: '', optB: '', optC: '', correctIdx: 0 }]);

  const updateDraft = (idx, patch) => {
    setDrafts((prev) => prev.map((d, i) => (i === idx ? { ...d, ...patch } : d)));
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form
        className="modal-panel"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          onConfirm({
            courseId,
            title,
            durationMinutes,
            prompts: drafts.map((d) => d.prompt),
            optionBlocks: drafts.map((d) => [d.optA, d.optB, d.optC].filter((x) => x.trim()).join('|')),
            correctIndices: drafts.map((d) => d.correctIdx)
          });
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

        {drafts.map((draft, qIdx) => (
          <div key={qIdx} className="m3-subsurface">
            <div style={{ fontWeight: 700, color: 'var(--primary)', fontSize: '0.85rem' }}>
              {strings.questionWord} {qIdx + 1}
            </div>
            <input
              className="m3-input"
              placeholder={`${strings.questionWord} ${qIdx + 1}`}
              value={draft.prompt}
              onChange={(e) => updateDraft(qIdx, { prompt: e.target.value })}
              required
            />
            <input
              className="m3-input"
              placeholder="A"
              value={draft.optA}
              onChange={(e) => updateDraft(qIdx, { optA: e.target.value })}
              required
            />
            <input
              className="m3-input"
              placeholder="B"
              value={draft.optB}
              onChange={(e) => updateDraft(qIdx, { optB: e.target.value })}
              required
            />
            <input
              className="m3-input"
              placeholder="C"
              value={draft.optC}
              onChange={(e) => updateDraft(qIdx, { optC: e.target.value })}
            />
            <div className="row-gap">
              {[0, 1, 2].map((optIdx) => (
                <label key={optIdx} className="row-gap" style={{ cursor: 'pointer', fontSize: '0.85rem' }}>
                  <input
                    type="radio"
                    name={`correct-${qIdx}`}
                    checked={draft.correctIdx === optIdx}
                    onChange={() => updateDraft(qIdx, { correctIdx: optIdx })}
                  />
                  <span>{['A', 'B', 'C'][optIdx]}</span>
                </label>
              ))}
            </div>
          </div>
        ))}

        {drafts.length < 5 ? (
          <button
            type="button"
            className="btn btn-outlined"
            onClick={() =>
              setDrafts((prev) => [...prev, { prompt: '', optA: '', optB: '', optC: '', correctIdx: 0 }])
            }
          >
            {strings.addQuestionAction}
          </button>
        ) : null}

        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>
            {strings.cancelAction}
          </button>
          <button type="submit" className="btn btn-primary">
            {strings.saveQuizAction}
          </button>
        </div>
      </form>
    </div>
  );
}
