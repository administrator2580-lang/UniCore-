import React, { useMemo, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  ClipboardList,
  Clock,
  ExternalLink,
  Link2,
  PlayCircle,
  Plus,
  RefreshCw,
  Trash2,
  X
} from 'lucide-react';
import {
  createAssignment,
  deleteAssignment,
  importClassroomAssignments,
  matchesStudentScope,
  toggleAssignmentCompleted
} from '../data/firestoreService';
import { fetchGoogleClassroomAssignments, requestClassroomAccessToken } from '../firebase/config';
import { validateOptionalHttpsUrl } from '../security/validators';

export default function AssignmentsView({
  strings,
  db,
  auth,
  userRole,
  userProfile,
  departments,
  assignments,
  onShowToast
}) {
  const canManage = userRole !== 'STUDENT';
  const completedIds = Array.isArray(userProfile?.completedAssignmentIds)
    ? userProfile.completedAssignmentIds
    : [];

  const [filter, setFilter] = useState('PENDING');
  const [selectedDeptId, setSelectedDeptId] = useState(null);
  const [selectedYear, setSelectedYear] = useState(null);
  const [syncingClassroom, setSyncingClassroom] = useState(false);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [createMode, setCreateMode] = useState('APP'); // 'APP' or 'CLASSROOM'

  const scopedAssignments = useMemo(() => {
    const now = Date.now();
    return assignments
      .filter((a) => {
        if (userRole === 'STUDENT') {
          return matchesStudentScope(userProfile, userRole, a);
        }
        const matchesDept =
          !selectedDeptId ||
          a.departmentId === selectedDeptId ||
          a.departmentId === 'ALL';
        const matchesYear = !selectedYear || a.academicYear === selectedYear || !a.academicYear;
        return matchesDept && matchesYear;
      })
      .map((a) => {
        const isDone = completedIds.includes(a.id);
        const dueMs = Number(a.dueAtMillis) || 0;
        const isOverdue = !isDone && dueMs > 0 && now > dueMs;
        const isDueSoon = !isDone && dueMs >= now && dueMs - now <= 24 * 60 * 60 * 1000;
        return { ...a, isDone, isOverdue, isDueSoon };
      });
  }, [assignments, userRole, userProfile, selectedDeptId, selectedYear, completedIds]);

  const filteredList = useMemo(() => {
    return scopedAssignments.filter((a) => {
      if (filter === 'PENDING') return !a.isDone;
      if (filter === 'DONE') return a.isDone;
      if (filter === 'CLASSROOM') return a.sourceType === 'CLASSROOM';
      return true;
    });
  }, [scopedAssignments, filter]);

  const selectedDept = departments.find((d) => d.id === selectedDeptId) || null;

  const openExternal = async (url) => {
    const res = validateOptionalHttpsUrl(url);
    if (!res.valid || !res.value) {
      onShowToast(res.reason || 'Invalid HTTPS link');
      return;
    }
    if (window.unicoreDesktop?.openExternalUrl) {
      await window.unicoreDesktop.openExternalUrl(res.value);
    } else {
      window.open(res.value, '_blank', 'noopener,noreferrer');
    }
  };

  const handleSyncClassroom = async () => {
    setSyncingClassroom(true);
    try {
      const token = await requestClassroomAccessToken(auth);
      if (!token) {
        throw new Error('Could not obtain Google Classroom permission token.');
      }
      const fetched = await fetchGoogleClassroomAssignments(token);
      if (fetched.length === 0) {
        onShowToast('Google Classroom connected — 0 active tasks found.');
      } else {
        const savedCount = await importClassroomAssignments(db, auth, fetched, userProfile);
        onShowToast(`${strings.classroomSyncedToast} ${savedCount}`);
      }
    } catch (err) {
      onShowToast(err.message || 'Classroom sync failed.');
    } finally {
      setSyncingClassroom(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
      {/* Hero & Classroom Integration Bar */}
      <div className="m3-card accent-secondary">
        <div className="row-between" style={{ flexWrap: 'wrap' }}>
          <div className="row-gap">
            <ClipboardList size={22} style={{ color: 'var(--primary)' }} />
            <div>
              <div style={{ fontWeight: 800, fontSize: '1.1rem' }}>{strings.myDueTitle}</div>
              <div className="muted" style={{ fontSize: '0.82rem' }}>{strings.myDueSubtitle}</div>
            </div>
          </div>
          <div className="row-gap" style={{ flexWrap: 'wrap' }}>
            <button
              type="button"
              className="btn btn-tonal"
              disabled={syncingClassroom}
              onClick={handleSyncClassroom}
            >
              <RefreshCw size={15} className={syncingClassroom ? 'spin' : ''} />
              <span>{syncingClassroom ? strings.syncingClassroomLabel : strings.syncClassroomAction}</span>
            </button>
            <button
              type="button"
              className="btn btn-outlined"
              onClick={() => {
                setCreateMode('CLASSROOM');
                setShowCreateModal(true);
              }}
            >
              <Link2 size={15} />
              <span>{strings.addManualClassroomTask}</span>
            </button>
            {canManage ? (
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => {
                  setCreateMode('APP');
                  setShowCreateModal(true);
                }}
              >
                <Plus size={16} />
                <span>{strings.addAssignmentAction}</span>
              </button>
            ) : null}
          </div>
        </div>
      </div>

      {/* Admin Scope Filters */}
      {canManage && departments.length > 0 ? (
        <div className="chip-scroll">
          <button
            type="button"
            className={`m3-chip ${selectedDeptId === null ? 'selected' : ''}`}
            onClick={() => {
              setSelectedDeptId(null);
              setSelectedYear(null);
            }}
          >
            {strings.allDepartments}
          </button>
          {departments.map((d) => (
            <button
              key={d.id}
              type="button"
              className={`m3-chip ${selectedDeptId === d.id ? 'selected' : ''}`}
              onClick={() => {
                setSelectedDeptId(selectedDeptId === d.id ? null : d.id);
                setSelectedYear(null);
              }}
            >
              {d.name}
            </button>
          ))}
          {selectedDept?.years?.map((yr) => (
            <button
              key={yr}
              type="button"
              className={`m3-chip ${selectedYear === yr ? 'selected' : ''}`}
              onClick={() => setSelectedYear(selectedYear === yr ? null : yr)}
            >
              {yr}
            </button>
          ))}
        </div>
      ) : null}

      {/* Status Filter Chips */}
      <div className="chip-scroll">
        {[
          { id: 'PENDING', label: strings.dueFilterPending },
          { id: 'ALL', label: strings.dueFilterAll },
          { id: 'DONE', label: strings.dueFilterDone },
          { id: 'CLASSROOM', label: strings.dueFilterClassroom }
        ].map((chip) => (
          <button
            key={chip.id}
            type="button"
            className={`m3-chip ${filter === chip.id ? 'selected' : ''}`}
            onClick={() => setFilter(chip.id)}
          >
            {chip.label}
          </button>
        ))}
      </div>

      {/* Assignments List */}
      {filteredList.length === 0 ? (
        <div className="m3-card" style={{ alignItems: 'center', textAlign: 'center', padding: '32px 20px' }}>
          <CheckCircle2 size={42} style={{ color: 'var(--secondary)' }} />
          <div style={{ fontWeight: 700, fontSize: '1.08rem' }}>{strings.noAssignmentsTitle}</div>
          <div className="muted">{strings.noAssignmentsSub}</div>
        </div>
      ) : (
        filteredList.map((item) => {
          const canDelete = canManage || item.createdBy === auth.currentUser?.uid;
          const dueText = item.dueAtMillis ? new Date(item.dueAtMillis).toLocaleString() : '—';
          return (
            <div
              key={item.id}
              className={`m3-card ${item.isOverdue ? 'accent-error' : ''}`}
              style={item.isDone ? { opacity: 0.78 } : undefined}
            >
              <div className="row-between" style={{ flexWrap: 'wrap' }}>
                <div className="chip-row">
                  <span
                    className={`pill ${
                      item.isDone
                        ? 'pill-secondary'
                        : item.isOverdue
                          ? 'pill-error'
                          : item.isDueSoon
                            ? 'pill-tertiary'
                            : 'pill-primary'
                    }`}
                  >
                    {item.isDone ? (
                      <CheckCircle2 size={13} />
                    ) : item.isOverdue || item.isDueSoon ? (
                      <AlertTriangle size={13} />
                    ) : (
                      <Clock size={13} />
                    )}
                    <span>
                      {item.isDone
                        ? strings.completedBadge
                        : item.isOverdue
                          ? strings.overdueBadge
                          : item.isDueSoon
                            ? strings.dueSoonBadge
                            : strings.upcomingBadge}
                    </span>
                  </span>

                  {item.sourceType === 'CLASSROOM' ? (
                    <span className="pill pill-tertiary">Google Classroom</span>
                  ) : null}

                  {item.courseTitle ? (
                    <span className="pill pill-secondary">{item.courseTitle}</span>
                  ) : null}

                  {item.academicYear || item.sectionName ? (
                    <span className="muted" style={{ fontSize: '0.78rem' }}>
                      {[item.departmentName, item.academicYear, item.sectionName].filter(Boolean).join(' • ')}
                    </span>
                  ) : null}
                </div>

                {canDelete ? (
                  <button
                    type="button"
                    className="icon-btn"
                    style={{ color: 'var(--error)' }}
                    title={strings.deleteLabel}
                    onClick={() => deleteAssignment(db, item.id).catch((e) => onShowToast(e.message))}
                  >
                    <Trash2 size={16} />
                  </button>
                ) : null}
              </div>

              <h3
                style={{
                  margin: 0,
                  fontSize: '1.1rem',
                  fontWeight: 700,
                  textDecoration: item.isDone ? 'line-through' : 'none'
                }}
              >
                {item.title}
              </h3>

              <p style={{ margin: 0, color: 'var(--text-secondary)', lineHeight: 1.5 }}>{item.description}</p>

              <div className="muted" style={{ fontSize: '0.84rem', fontWeight: 600 }}>
                <Clock size={14} style={{ verticalAlign: 'middle', marginInlineEnd: '6px' }} />
                {strings.dueDateTimeLabel}: {dueText}
              </div>

              <div className="row-gap" style={{ flexWrap: 'wrap' }}>
                <button
                  type="button"
                  className={`btn ${item.isDone ? 'btn-secondary' : 'btn-primary'}`}
                  onClick={() =>
                    toggleAssignmentCompleted(db, auth, item.id, completedIds).catch((e) =>
                      onShowToast(e.message)
                    )
                  }
                >
                  <CheckCircle2 size={15} />
                  <span>{item.isDone ? strings.completedBadge : strings.markDoneAction}</span>
                </button>

                {item.classroomLink ? (
                  <button
                    type="button"
                    className="btn btn-outlined"
                    onClick={() => openExternal(item.classroomLink)}
                  >
                    <ExternalLink size={15} />
                    <span>{strings.openClassroomAction}</span>
                  </button>
                ) : null}

                {item.videoUrl ? (
                  <button
                    type="button"
                    className="btn btn-tonal"
                    onClick={() => openExternal(item.videoUrl)}
                  >
                    <PlayCircle size={15} />
                    <span>{strings.watchVideoAction}</span>
                  </button>
                ) : null}
              </div>
            </div>
          );
        })
      )}

      {showCreateModal ? (
        <CreateAssignmentModal
          strings={strings}
          mode={createMode}
          departments={departments}
          userProfile={userProfile}
          onClose={() => setShowCreateModal(false)}
          onSubmit={async (payload) => {
            try {
              await createAssignment(db, auth, payload);
              setShowCreateModal(false);
              onShowToast(strings.addAssignmentAction);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}
    </div>
  );
}

function CreateAssignmentModal({ strings, mode, departments, userProfile, onClose, onSubmit }) {
  const defaultLocal = new Date(Date.now() + 2 * 24 * 60 * 60 * 1000);
  const pad = (n) => String(n).padStart(2, '0');
  const defaultDateTime = `${defaultLocal.getFullYear()}-${pad(defaultLocal.getMonth() + 1)}-${pad(defaultLocal.getDate())}T23:59`;

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [courseTitle, setCourseTitle] = useState('');
  const [dueDateStr, setDueDateStr] = useState(defaultDateTime);
  const [deptId, setDeptId] = useState(userProfile?.departmentId || '');
  const [year, setYear] = useState(userProfile?.academicYear || '');
  const [section, setSection] = useState(userProfile?.sectionName || '');
  const [classroomLink, setClassroomLink] = useState('');
  const [videoUrl, setVideoUrl] = useState('');
  const [saving, setSaving] = useState(false);

  const chosenDept = departments.find((d) => d.id === deptId) || null;

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    const parsedDue = dueDateStr ? new Date(dueDateStr).getTime() : Date.now() + 86_400_000;
    await onSubmit({
      title,
      description,
      courseTitle,
      departmentId: mode === 'CLASSROOM' ? userProfile?.departmentId || null : chosenDept?.id || null,
      departmentName: mode === 'CLASSROOM' ? userProfile?.departmentName || null : chosenDept?.name || null,
      academicYear: mode === 'CLASSROOM' ? userProfile?.academicYear || null : year || null,
      sectionName: mode === 'CLASSROOM' ? userProfile?.sectionName || null : section || null,
      dueAtMillis: Number.isNaN(parsedDue) ? Date.now() + 86_400_000 : parsedDue,
      classroomLink,
      videoUrl,
      sourceType: mode === 'CLASSROOM' ? 'CLASSROOM' : 'APP',
      ownerUid: mode === 'CLASSROOM' ? 'SELF' : 'ALL'
    });
    setSaving(false);
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form className="modal-panel" onClick={(e) => e.stopPropagation()} onSubmit={handleSubmit}>
        <div className="row-between">
          <h3 style={{ margin: 0 }}>
            {mode === 'CLASSROOM' ? strings.addManualClassroomTask : strings.addAssignmentAction}
          </h3>
          <button type="button" className="icon-btn" onClick={onClose}>
            <X size={18} />
          </button>
        </div>

        <div className="field-group">
          <label className="field-label">{strings.assignmentTitleLabel}</label>
          <input className="m3-input" value={title} onChange={(e) => setTitle(e.target.value)} required />
        </div>
        <div className="field-group">
          <label className="field-label">{strings.assignmentDescLabel}</label>
          <textarea className="m3-textarea" rows={2} value={description} onChange={(e) => setDescription(e.target.value)} required />
        </div>
        <div className="row-gap">
          <div className="field-group">
            <label className="field-label">{strings.selectCourseLabel}</label>
            <input className="m3-input" value={courseTitle} onChange={(e) => setCourseTitle(e.target.value)} />
          </div>
          <div className="field-group">
            <label className="field-label">{strings.dueDateTimeLabel}</label>
            <input
              type="datetime-local"
              className="m3-input"
              value={dueDateStr}
              onChange={(e) => setDueDateStr(e.target.value)}
              required
            />
          </div>
        </div>

        {mode !== 'CLASSROOM' ? (
          <>
            {departments.length > 0 ? (
              <div className="field-group">
                <span className="field-label">{strings.selectDepartmentLabel}</span>
                <div className="chip-scroll">
                  <button
                    type="button"
                    className={`m3-chip ${!deptId ? 'selected' : ''}`}
                    onClick={() => {
                      setDeptId('');
                      setYear('');
                    }}
                  >
                    {strings.allDepartments}
                  </button>
                  {departments.map((d) => (
                    <button
                      key={d.id}
                      type="button"
                      className={`m3-chip ${deptId === d.id ? 'selected' : ''}`}
                      onClick={() => {
                        setDeptId(d.id);
                        setYear('');
                      }}
                    >
                      {d.name}
                    </button>
                  ))}
                </div>
              </div>
            ) : null}

            <div className="row-gap">
              <div className="field-group">
                <label className="field-label">{strings.selectYearLabel}</label>
                <input
                  className="m3-input"
                  placeholder={strings.allYears}
                  value={year}
                  onChange={(e) => setYear(e.target.value)}
                />
              </div>
              <div className="field-group">
                <label className="field-label">{strings.sectionLabel}</label>
                <input
                  className="m3-input"
                  placeholder={strings.allSectionsOption}
                  value={section}
                  onChange={(e) => setSection(e.target.value)}
                />
              </div>
            </div>
          </>
        ) : null}

        <div className="field-group">
          <label className="field-label">{strings.classroomLinkLabel}</label>
          <input
            className="m3-input"
            placeholder="https://classroom.google.com/..."
            value={classroomLink}
            onChange={(e) => setClassroomLink(e.target.value)}
          />
        </div>
        <div className="field-group">
          <label className="field-label">{strings.lectureVideoUrlLabel}</label>
          <input
            className="m3-input"
            placeholder="https://..."
            value={videoUrl}
            onChange={(e) => setVideoUrl(e.target.value)}
          />
        </div>

        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>
            {strings.cancelAction}
          </button>
          <button type="submit" className="btn btn-primary" disabled={saving}>
            {saving ? <span className="spinner" /> : strings.publishAction}
          </button>
        </div>
      </form>
    </div>
  );
}
