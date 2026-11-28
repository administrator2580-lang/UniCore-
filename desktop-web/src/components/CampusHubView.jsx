import React, { useEffect, useMemo, useState } from 'react';
import {
  BookOpen,
  CalendarDays,
  CheckCircle2,
  Clock,
  Coffee,
  ExternalLink,
  Megaphone,
  Pause,
  Pin,
  Play,
  PlayCircle,
  Plus,
  RotateCcw,
  Sparkles,
  Trash2,
  Trophy,
  Video,
  X
} from 'lucide-react';
import {
  createInstruction,
  createScheduleItem,
  createZoomSession,
  deleteInstruction,
  deleteScheduleItem,
  deleteZoomSession,
  matchesStudentScope
} from '../data/firestoreService';
import { validateOptionalHttpsUrl } from '../security/validators';

const DAYS_LIST = [
  { id: 1, ar: 'السبت', en: 'Saturday' },
  { id: 2, ar: 'الأحد', en: 'Sunday' },
  { id: 3, ar: 'الاثنين', en: 'Monday' },
  { id: 4, ar: 'الثلاثاء', en: 'Tuesday' },
  { id: 5, ar: 'الأربعاء', en: 'Wednesday' },
  { id: 6, ar: 'الخميس', en: 'Thursday' },
  { id: 7, ar: 'الجمعة', en: 'Friday' }
];

const TRIVIA_BANK = [
  {
    q: 'ما هي وحدة قياس الطاقة الكهربائية المستهلكة منزلياً؟',
    options: ['كيلوواط ساعة (kWh)', 'فولت أمبير (VA)', 'أوم (Ohm)'],
    correct: 0
  },
  {
    q: 'أي بروتوكول يُستخدم لتأمين اتصال الويب عبر التشفير؟',
    options: ['FTP', 'HTTPS', 'Telnet'],
    correct: 1
  },
  {
    q: 'ما اسم تقنية تحويل أشعة الشمس مباشرة إلى كهرباء؟',
    options: ['الخلايا الكهروضوئية (PV)', 'التوربينات البخارية', 'المحولات الحثية'],
    correct: 0
  },
  {
    q: 'في أنظمة قواعد البيانات، ما معنى ACID؟',
    options: [
      'معايير موثوقية المعاملات (Atomicity, Consistency, Isolation, Durability)',
      'لغة استعلام رسومية',
      'بروتوكول شبكة محلية'
    ],
    correct: 0
  },
  {
    q: 'أي مما يلي يعبر عن تعقيد البحث الثنائي (Binary Search)؟',
    options: ['O(n)', 'O(log n)', 'O(n²)'],
    correct: 1
  }
];

export default function CampusHubView({
  strings,
  language,
  db,
  auth,
  userRole,
  userProfile,
  systemSettings,
  departments,
  scheduleItems,
  zoomSessions,
  instructions,
  onShowToast
}) {
  const canManage = userRole !== 'STUDENT';
  const [subTab, setSubTab] = useState('SCHEDULE'); // SCHEDULE | ZOOM | INSTRUCTIONS | GUIDE | FUN
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [showAddSchedule, setShowAddSchedule] = useState(false);
  const [showAddZoom, setShowAddZoom] = useState(false);
  const [showAddInstruction, setShowAddInstruction] = useState(false);

  useEffect(() => {
    const id = setInterval(() => setNowMs(Date.now()), 30_000);
    return () => clearInterval(id);
  }, []);

  const openExternal = async (url) => {
    const res = validateOptionalHttpsUrl(url);
    if (!res.valid || !res.value) {
      onShowToast(res.reason || 'Invalid HTTPS URL');
      return;
    }
    if (window.unicoreDesktop?.openExternalUrl) {
      await window.unicoreDesktop.openExternalUrl(res.value);
    } else {
      window.open(res.value, '_blank', 'noopener,noreferrer');
    }
  };

  const scopedSchedule = useMemo(
    () => scheduleItems.filter((s) => matchesStudentScope(userProfile, userRole, s)),
    [scheduleItems, userProfile, userRole]
  );

  const scopedZoom = useMemo(
    () => zoomSessions.filter((z) => matchesStudentScope(userProfile, userRole, z)),
    [zoomSessions, userProfile, userRole]
  );

  const scopedInstructions = useMemo(
    () => instructions.filter((i) => matchesStudentScope(userProfile, userRole, i)),
    [instructions, userProfile, userRole]
  );

  const dayLabel = (dayNum) => {
    const found = DAYS_LIST.find((d) => d.id === Number(dayNum));
    if (!found) return '';
    return language === 'ENGLISH' ? found.en : found.ar;
  };

  const scheduleTypeLabel = (type) => {
    switch (type) {
      case 'SECTION':
        return strings.scheduleTypeSection;
      case 'ASSIGNMENT':
        return strings.scheduleTypeAssignment;
      case 'EXAM':
        return strings.scheduleTypeExam;
      default:
        return strings.scheduleTypeLecture;
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
      {/* Campus Navigation Chips */}
      <div className="chip-scroll">
        {[
          { id: 'SCHEDULE', label: strings.campusScheduleTab, icon: CalendarDays },
          { id: 'ZOOM', label: strings.campusZoomTab, icon: Video },
          { id: 'INSTRUCTIONS', label: strings.campusInstructionsTab, icon: Megaphone },
          { id: 'GUIDE', label: strings.campusGuideTab, icon: BookOpen },
          { id: 'FUN', label: strings.campusFunTab, icon: Coffee }
        ].map((item) => {
          const IconComp = item.icon;
          return (
            <button
              key={item.id}
              type="button"
              className={`m3-chip ${subTab === item.id ? 'selected' : ''}`}
              onClick={() => setSubTab(item.id)}
            >
              <IconComp size={15} />
              <span>{item.label}</span>
            </button>
          );
        })}
      </div>

      {/* 1. PER-SECTION WEEKLY SCHEDULE */}
      {subTab === 'SCHEDULE' ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
          <div className="row-between">
            <h3 style={{ margin: 0 }}>{strings.campusScheduleTab}</h3>
            {canManage ? (
              <button type="button" className="btn btn-primary" onClick={() => setShowAddSchedule(true)}>
                <Plus size={16} />
                <span>{strings.addScheduleAction}</span>
              </button>
            ) : null}
          </div>

          {scopedSchedule.length === 0 ? (
            <div className="m3-card" style={{ alignItems: 'center', textAlign: 'center', padding: '28px' }}>
              <CalendarDays size={38} style={{ color: 'var(--primary)' }} />
              <div style={{ fontWeight: 700 }}>{strings.noScheduleTitle}</div>
            </div>
          ) : (
            scopedSchedule.map((slot) => (
              <div key={slot.id} className="m3-card">
                <div className="row-between" style={{ flexWrap: 'wrap' }}>
                  <div className="chip-row">
                    <span className="pill pill-primary">{scheduleTypeLabel(slot.itemType)}</span>
                    <span className="pill pill-secondary">{dayLabel(slot.dayOfWeek)}</span>
                    <span className="pill pill-tertiary">
                      {slot.startTimeText}
                      {slot.endTimeText ? ` - ${slot.endTimeText}` : ''}
                    </span>
                  </div>
                  {canManage ? (
                    <button
                      type="button"
                      className="icon-btn"
                      style={{ color: 'var(--error)' }}
                      onClick={() => deleteScheduleItem(db, slot.id).catch((e) => onShowToast(e.message))}
                    >
                      <Trash2 size={16} />
                    </button>
                  ) : null}
                </div>
                <div style={{ fontWeight: 700, fontSize: '1.05rem' }}>{slot.title}</div>
                <div className="muted" style={{ fontSize: '0.86rem' }}>
                  {[slot.locationOrMode, slot.instructorName, slot.academicYear, slot.sectionName]
                    .filter(Boolean)
                    .join(' • ')}
                </div>
                {slot.videoUrl ? (
                  <div>
                    <button type="button" className="btn btn-tonal" onClick={() => openExternal(slot.videoUrl)}>
                      <PlayCircle size={15} />
                      <span>{strings.watchVideoAction}</span>
                    </button>
                  </div>
                ) : null}
              </div>
            ))
          )}
        </div>
      ) : null}

      {/* 2. LIVE ZOOM LECTURES */}
      {subTab === 'ZOOM' ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
          <div className="row-between">
            <h3 style={{ margin: 0 }}>{strings.campusZoomTab}</h3>
            {canManage ? (
              <button type="button" className="btn btn-primary" onClick={() => setShowAddZoom(true)}>
                <Plus size={16} />
                <span>{strings.addZoomAction}</span>
              </button>
            ) : null}
          </div>

          {scopedZoom.length === 0 ? (
            <div className="m3-card" style={{ alignItems: 'center', textAlign: 'center', padding: '28px' }}>
              <Video size={38} style={{ color: 'var(--primary)' }} />
              <div style={{ fontWeight: 700 }}>{strings.noZoomTitle}</div>
            </div>
          ) : (
            scopedZoom.map((sess) => {
              const start = Number(sess.scheduledAtMillis) || 0;
              const end = start + (Number(sess.durationMinutes) || 60) * 60_000;
              const isLive = nowMs >= start - 10 * 60_000 && nowMs <= end;
              const isEnded = nowMs > end;
              return (
                <div key={sess.id} className={`m3-card ${isLive ? 'accent-secondary' : ''}`}>
                  <div className="row-between" style={{ flexWrap: 'wrap' }}>
                    <div className="chip-row">
                      <span
                        className={`pill ${
                          isLive ? 'pill-error' : isEnded ? 'pill-tertiary' : 'pill-primary'
                        }`}
                      >
                        {isLive
                          ? strings.zoomLiveBadge
                          : isEnded
                            ? strings.zoomEndedBadge
                            : strings.zoomUpcomingBadge}
                      </span>
                      <span className="muted" style={{ fontSize: '0.82rem' }}>
                        {new Date(start).toLocaleString()} ({sess.durationMinutes} {strings.minutesShort})
                      </span>
                    </div>
                    {canManage ? (
                      <button
                        type="button"
                        className="icon-btn"
                        style={{ color: 'var(--error)' }}
                        onClick={() => deleteZoomSession(db, sess.id).catch((e) => onShowToast(e.message))}
                      >
                        <Trash2 size={16} />
                      </button>
                    ) : null}
                  </div>
                  <div style={{ fontWeight: 700, fontSize: '1.08rem' }}>{sess.title}</div>
                  {sess.topic ? <div className="muted">{sess.topic}</div> : null}
                  <div className="muted" style={{ fontSize: '0.8rem' }}>
                    {[sess.hostName, sess.departmentName, sess.academicYear, sess.sectionName]
                      .filter(Boolean)
                      .join(' • ')}
                  </div>
                  <div>
                    <button
                      type="button"
                      className="btn btn-primary"
                      onClick={() => openExternal(sess.zoomUrl)}
                    >
                      <ExternalLink size={15} />
                      <span>{strings.joinZoomAction}</span>
                    </button>
                  </div>
                </div>
              );
            })
          )}
        </div>
      ) : null}

      {/* 3. INSTRUCTIONS FOR EVERYONE ("تعليمات للكل") */}
      {subTab === 'INSTRUCTIONS' ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
          <div className="row-between">
            <h3 style={{ margin: 0 }}>{strings.instructionsTitle}</h3>
            {canManage ? (
              <button type="button" className="btn btn-primary" onClick={() => setShowAddInstruction(true)}>
                <Plus size={16} />
                <span>{strings.addInstructionAction}</span>
              </button>
            ) : null}
          </div>

          {scopedInstructions.length === 0 ? (
            <div className="m3-card" style={{ alignItems: 'center', textAlign: 'center', padding: '28px' }}>
              <Megaphone size={38} style={{ color: 'var(--primary)' }} />
              <div style={{ fontWeight: 700 }}>{strings.noInstructionsTitle}</div>
            </div>
          ) : (
            scopedInstructions.map((inst) => (
              <div key={inst.id} className={`m3-card ${inst.priority === 'PINNED' ? 'accent-secondary' : ''}`}>
                <div className="row-between">
                  <div className="chip-row">
                    <span
                      className={`pill ${
                        inst.priority === 'PINNED'
                          ? 'pill-primary'
                          : inst.priority === 'IMPORTANT'
                            ? 'pill-tertiary'
                            : 'pill-secondary'
                      }`}
                    >
                      {inst.priority === 'PINNED' ? <Pin size={12} /> : null}
                      <span>
                        {inst.priority === 'PINNED'
                          ? strings.instructionPriorityPinned
                          : inst.priority === 'IMPORTANT'
                            ? strings.instructionPriorityImportant
                            : strings.instructionPriorityNormal}
                      </span>
                    </span>
                    <span className="muted" style={{ fontSize: '0.8rem' }}>
                      {[inst.createdByName, inst.academicYear, inst.sectionName].filter(Boolean).join(' • ')}
                    </span>
                  </div>
                  {canManage ? (
                    <button
                      type="button"
                      className="icon-btn"
                      style={{ color: 'var(--error)' }}
                      onClick={() => deleteInstruction(db, inst.id).catch((e) => onShowToast(e.message))}
                    >
                      <Trash2 size={16} />
                    </button>
                  ) : null}
                </div>
                <div style={{ fontWeight: 700, fontSize: '1.06rem' }}>{inst.title}</div>
                <div style={{ lineHeight: 1.6, whiteSpace: 'pre-wrap' }}>{inst.body}</div>
              </div>
            ))
          )}
        </div>
      ) : null}

      {/* 4. HOW TO USE THE APP TUTORIAL */}
      {subTab === 'GUIDE' ? (
        <div className="m3-card">
          <div className="row-gap">
            <BookOpen size={22} style={{ color: 'var(--primary)' }} />
            <div>
              <h3 style={{ margin: 0 }}>{systemSettings?.tutorialVideoTitle || strings.tutorialTitle}</h3>
              <div className="muted" style={{ fontSize: '0.84rem' }}>{strings.tutorialSubtitle}</div>
            </div>
          </div>

          {systemSettings?.tutorialVideoUrl ? (
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => openExternal(systemSettings.tutorialVideoUrl)}
            >
              <PlayCircle size={18} />
              <span>{strings.watchTutorialAction}</span>
            </button>
          ) : (
            <div className="m3-subsurface muted">{strings.tutorialNotConfigured}</div>
          )}

          <div className="m3-subsurface" style={{ gap: '8px' }}>
            <div style={{ fontWeight: 700 }}>UniCore Quick Guide</div>
            <div>1. اختر القسم والفرقة والسكشن من نافذة البيانات الأكاديمية لتظهر موادك وجدولك فقط.</div>
            <div>2. افتح تبويب «اللي عليا» لمتابعة الأسينمنتات أو مزامنة مهام Google Classroom.</div>
            <div>3. احفظ المواد المهمة بالمفضلة (القلب) أو نزّل ملفات الدراسة للعرض بدون إنترنت.</div>
            <div>4. راجع «الخدمات الجامعية» لمتابعة الجدول، روابط Zoom المباشرة، وتعليمات الدفعة.</div>
          </div>
        </div>
      ) : null}

      {/* 5. FOR FUN: POMODORO TIMER + ACADEMIC/TECH TRIVIA */}
      {subTab === 'FUN' ? <ForFunZone strings={strings} /> : null}

      {showAddSchedule ? (
        <AddScheduleModal
          strings={strings}
          language={language}
          departments={departments}
          onClose={() => setShowAddSchedule(false)}
          onSubmit={async (payload) => {
            try {
              await createScheduleItem(db, auth, payload);
              setShowAddSchedule(false);
              onShowToast(strings.addScheduleAction);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}

      {showAddZoom ? (
        <AddZoomModal
          strings={strings}
          departments={departments}
          onClose={() => setShowAddZoom(false)}
          onSubmit={async (payload) => {
            try {
              await createZoomSession(db, auth, payload);
              setShowAddZoom(false);
              onShowToast(strings.addZoomAction);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}

      {showAddInstruction ? (
        <AddInstructionModal
          strings={strings}
          departments={departments}
          onClose={() => setShowAddInstruction(false)}
          onSubmit={async (payload) => {
            try {
              await createInstruction(db, auth, payload);
              setShowAddInstruction(false);
              onShowToast(strings.addInstructionAction);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}
    </div>
  );
}

function ForFunZone({ strings }) {
  const [focusMode, setFocusMode] = useState(true);
  const [secondsLeft, setSecondsLeft] = useState(25 * 60);
  const [running, setRunning] = useState(false);

  const [qIdx, setQIdx] = useState(0);
  const [selectedOpt, setSelectedOpt] = useState(null);
  const [score, setScore] = useState(0);

  useEffect(() => {
    if (!running) return undefined;
    const id = setInterval(() => {
      setSecondsLeft((prev) => {
        if (prev <= 1) {
          const nextFocus = !focusMode;
          setFocusMode(nextFocus);
          setRunning(false);
          return nextFocus ? 25 * 60 : 5 * 60;
        }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(id);
  }, [running, focusMode]);

  const mins = String(Math.floor(secondsLeft / 60)).padStart(2, '0');
  const secs = String(secondsLeft % 60).padStart(2, '0');
  const question = TRIVIA_BANK[qIdx % TRIVIA_BANK.length];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
      <div className="m3-card accent-secondary">
        <div className="row-between">
          <div className="row-gap">
            <Clock size={20} style={{ color: 'var(--primary)' }} />
            <h3 style={{ margin: 0 }}>{strings.pomodoroTitle}</h3>
          </div>
          <span className="pill pill-primary">
            {focusMode ? strings.pomodoroFocusPhase : strings.pomodoroBreakPhase}
          </span>
        </div>

        <div style={{ fontSize: '2.6rem', fontWeight: 800, textAlign: 'center', letterSpacing: '0.04em' }}>
          {mins}:{secs}
        </div>

        <div className="row-gap" style={{ justifyContent: 'center' }}>
          <button type="button" className="btn btn-primary" onClick={() => setRunning(!running)}>
            {running ? <Pause size={16} /> : <Play size={16} />}
            <span>{running ? strings.pauseTimerAction : strings.startTimerAction}</span>
          </button>
          <button
            type="button"
            className="btn btn-outlined"
            onClick={() => {
              setRunning(false);
              setFocusMode(true);
              setSecondsLeft(25 * 60);
            }}
          >
            <RotateCcw size={16} />
            <span>{strings.resetTimerAction}</span>
          </button>
        </div>
      </div>

      <div className="m3-card">
        <div className="row-between">
          <div className="row-gap">
            <Sparkles size={20} style={{ color: 'var(--tertiary)' }} />
            <h3 style={{ margin: 0 }}>{strings.triviaTitle}</h3>
          </div>
          <span className="pill pill-tertiary">
            <Trophy size={13} />
            <span>
              {strings.triviaScoreLabel}: {score}
            </span>
          </span>
        </div>

        <div style={{ fontWeight: 700, fontSize: '1.02rem' }}>{question.q}</div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
          {question.options.map((opt, idx) => {
            const answered = selectedOpt !== null;
            const isCorrect = idx === question.correct;
            const isChosen = idx === selectedOpt;
            return (
              <button
                key={opt}
                type="button"
                className={`btn ${
                  answered
                    ? isCorrect
                      ? 'btn-secondary'
                      : isChosen
                        ? 'btn-danger-outlined'
                        : 'btn-outlined'
                    : 'btn-outlined'
                }`}
                style={{ justifyContent: 'space-between' }}
                onClick={() => {
                  if (answered) return;
                  setSelectedOpt(idx);
                  if (idx === question.correct) setScore((s) => s + 1);
                }}
              >
                <span>{opt}</span>
                {answered && isCorrect ? <CheckCircle2 size={16} /> : null}
              </button>
            );
          })}
        </div>

        {selectedOpt !== null ? (
          <button
            type="button"
            className="btn btn-tonal"
            onClick={() => {
              setSelectedOpt(null);
              setQIdx((i) => i + 1);
            }}
          >
            {strings.triviaNextQuestion}
          </button>
        ) : null}
      </div>
    </div>
  );
}

function AddScheduleModal({ strings, language, departments, onClose, onSubmit }) {
  const [title, setTitle] = useState('');
  const [itemType, setItemType] = useState('LECTURE');
  const [dayOfWeek, setDayOfWeek] = useState(1);
  const [startTimeText, setStartTimeText] = useState('09:00 AM');
  const [endTimeText, setEndTimeText] = useState('11:00 AM');
  const [locationOrMode, setLocationOrMode] = useState('');
  const [instructorName, setInstructorName] = useState('');
  const [deptId, setDeptId] = useState('');
  const [year, setYear] = useState('');
  const [sectionName, setSectionName] = useState('');
  const [videoUrl, setVideoUrl] = useState('');
  const chosenDept = departments.find((d) => d.id === deptId) || null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form
        className="modal-panel"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          onSubmit({
            title,
            itemType,
            dayOfWeek,
            startTimeText,
            endTimeText,
            locationOrMode,
            instructorName,
            departmentId: chosenDept?.id || null,
            departmentName: chosenDept?.name || null,
            academicYear: year || null,
            sectionName: sectionName || null,
            videoUrl
          });
        }}
      >
        <div className="row-between">
          <h3 style={{ margin: 0 }}>{strings.addScheduleAction}</h3>
          <button type="button" className="icon-btn" onClick={onClose}><X size={18} /></button>
        </div>

        <div className="chip-scroll">
          {['LECTURE', 'SECTION', 'ASSIGNMENT', 'EXAM'].map((t) => (
            <button key={t} type="button" className={`m3-chip ${itemType === t ? 'selected' : ''}`} onClick={() => setItemType(t)}>
              {t === 'SECTION' ? strings.scheduleTypeSection : t === 'ASSIGNMENT' ? strings.scheduleTypeAssignment : t === 'EXAM' ? strings.scheduleTypeExam : strings.scheduleTypeLecture}
            </button>
          ))}
        </div>

        <div className="field-group">
          <label className="field-label">{strings.courseTitleLabel}</label>
          <input className="m3-input" value={title} onChange={(e) => setTitle(e.target.value)} required />
        </div>

        <div className="field-group">
          <label className="field-label">{strings.dayOfWeekLabel}</label>
          <select className="m3-input" value={dayOfWeek} onChange={(e) => setDayOfWeek(Number(e.target.value))}>
            {DAYS_LIST.map((d) => (
              <option key={d.id} value={d.id}>{language === 'ENGLISH' ? d.en : d.ar}</option>
            ))}
          </select>
        </div>

        <div className="row-gap">
          <div className="field-group">
            <label className="field-label">{strings.startTimeLabel}</label>
            <input className="m3-input" value={startTimeText} onChange={(e) => setStartTimeText(e.target.value)} required />
          </div>
          <div className="field-group">
            <label className="field-label">{strings.endTimeLabel}</label>
            <input className="m3-input" value={endTimeText} onChange={(e) => setEndTimeText(e.target.value)} />
          </div>
        </div>

        <div className="row-gap">
          <div className="field-group">
            <label className="field-label">{strings.locationOrHallLabel}</label>
            <input className="m3-input" value={locationOrMode} onChange={(e) => setLocationOrMode(e.target.value)} required />
          </div>
          <div className="field-group">
            <label className="field-label">{strings.instructorLabel}</label>
            <input className="m3-input" value={instructorName} onChange={(e) => setInstructorName(e.target.value)} />
          </div>
        </div>

        {departments.length > 0 ? (
          <select className="m3-input" value={deptId} onChange={(e) => setDeptId(e.target.value)}>
            <option value="">{strings.allDepartments}</option>
            {departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        ) : null}

        <div className="row-gap">
          <input className="m3-input" placeholder={strings.allYears} value={year} onChange={(e) => setYear(e.target.value)} />
          <input className="m3-input" placeholder={strings.allSectionsOption} value={sectionName} onChange={(e) => setSectionName(e.target.value)} />
        </div>

        <input className="m3-input" placeholder={strings.lectureVideoUrlLabel} value={videoUrl} onChange={(e) => setVideoUrl(e.target.value)} />

        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>{strings.cancelAction}</button>
          <button type="submit" className="btn btn-primary">{strings.publishAction}</button>
        </div>
      </form>
    </div>
  );
}

function AddZoomModal({ strings, departments, onClose, onSubmit }) {
  const [title, setTitle] = useState('');
  const [topic, setTopic] = useState('');
  const [zoomUrl, setZoomUrl] = useState('');
  const [scheduledStr, setScheduledStr] = useState('');
  const [durationMinutes, setDurationMinutes] = useState(60);
  const [deptId, setDeptId] = useState('');
  const [year, setYear] = useState('');
  const [sectionName, setSectionName] = useState('');
  const chosenDept = departments.find((d) => d.id === deptId) || null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form
        className="modal-panel"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          const whenMs = scheduledStr ? new Date(scheduledStr).getTime() : Date.now();
          onSubmit({
            title,
            topic,
            zoomUrl,
            scheduledAtMillis: Number.isNaN(whenMs) ? Date.now() : whenMs,
            durationMinutes,
            departmentId: chosenDept?.id || null,
            departmentName: chosenDept?.name || null,
            academicYear: year || null,
            sectionName: sectionName || null
          });
        }}
      >
        <div className="row-between">
          <h3 style={{ margin: 0 }}>{strings.addZoomAction}</h3>
          <button type="button" className="icon-btn" onClick={onClose}><X size={18} /></button>
        </div>
        <input className="m3-input" placeholder={strings.courseTitleLabel} value={title} onChange={(e) => setTitle(e.target.value)} required />
        <input className="m3-input" placeholder={strings.zoomTopicLabel} value={topic} onChange={(e) => setTopic(e.target.value)} />
        <input className="m3-input" placeholder={strings.zoomUrlLabel} value={zoomUrl} onChange={(e) => setZoomUrl(e.target.value)} required />
        <div className="row-gap">
          <input type="datetime-local" className="m3-input" value={scheduledStr} onChange={(e) => setScheduledStr(e.target.value)} />
          <input type="number" min={10} max={360} className="m3-input" value={durationMinutes} onChange={(e) => setDurationMinutes(e.target.value)} />
        </div>
        {departments.length > 0 ? (
          <select className="m3-input" value={deptId} onChange={(e) => setDeptId(e.target.value)}>
            <option value="">{strings.allDepartments}</option>
            {departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        ) : null}
        <div className="row-gap">
          <input className="m3-input" placeholder={strings.allYears} value={year} onChange={(e) => setYear(e.target.value)} />
          <input className="m3-input" placeholder={strings.allSectionsOption} value={sectionName} onChange={(e) => setSectionName(e.target.value)} />
        </div>
        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>{strings.cancelAction}</button>
          <button type="submit" className="btn btn-primary">{strings.publishAction}</button>
        </div>
      </form>
    </div>
  );
}

function AddInstructionModal({ strings, departments, onClose, onSubmit }) {
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [priority, setPriority] = useState('NORMAL');
  const [deptId, setDeptId] = useState('');
  const [year, setYear] = useState('');
  const [sectionName, setSectionName] = useState('');
  const chosenDept = departments.find((d) => d.id === deptId) || null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form
        className="modal-panel"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          onSubmit({
            title,
            body,
            priority,
            departmentId: chosenDept?.id || null,
            departmentName: chosenDept?.name || null,
            academicYear: year || null,
            sectionName: sectionName || null
          });
        }}
      >
        <div className="row-between">
          <h3 style={{ margin: 0 }}>{strings.addInstructionAction}</h3>
          <button type="button" className="icon-btn" onClick={onClose}><X size={18} /></button>
        </div>
        <div className="chip-scroll">
          {[
            { id: 'NORMAL', label: strings.instructionPriorityNormal },
            { id: 'IMPORTANT', label: strings.instructionPriorityImportant },
            { id: 'PINNED', label: strings.instructionPriorityPinned }
          ].map((p) => (
            <button key={p.id} type="button" className={`m3-chip ${priority === p.id ? 'selected' : ''}`} onClick={() => setPriority(p.id)}>
              {p.label}
            </button>
          ))}
        </div>
        <input className="m3-input" placeholder={strings.notificationTitleLabel} value={title} onChange={(e) => setTitle(e.target.value)} required />
        <textarea className="m3-textarea" rows={4} placeholder={strings.notificationBodyLabel} value={body} onChange={(e) => setBody(e.target.value)} required />
        {departments.length > 0 ? (
          <select className="m3-input" value={deptId} onChange={(e) => setDeptId(e.target.value)}>
            <option value="">{strings.allDepartments}</option>
            {departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        ) : null}
        <div className="row-gap">
          <input className="m3-input" placeholder={strings.allYears} value={year} onChange={(e) => setYear(e.target.value)} />
          <input className="m3-input" placeholder={strings.allSectionsOption} value={sectionName} onChange={(e) => setSectionName(e.target.value)} />
        </div>
        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>{strings.cancelAction}</button>
          <button type="submit" className="btn btn-primary">{strings.publishAction}</button>
        </div>
      </form>
    </div>
  );
}
