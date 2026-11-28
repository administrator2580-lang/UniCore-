import React, { useMemo, useState } from 'react';
import { GraduationCap, X } from 'lucide-react';
import { saveUserAcademicPlacement } from '../data/firestoreService';

const DEFAULT_YEARS = ['الفرقة الأولى', 'الفرقة الثانية', 'الفرقة الثالثة', 'الفرقة الرابعة'];
const DEFAULT_SECTIONS = ['سكشن 1', 'سكشن 2', 'سكشن 3', 'سكشن 4'];

export default function PlacementModal({
  strings,
  db,
  auth,
  departments = [],
  userProfile,
  canDismiss = true,
  onClose,
  onShowToast
}) {
  const [selectedDeptId, setSelectedDeptId] = useState(userProfile?.departmentId || departments[0]?.id || '');
  const [academicYear, setAcademicYear] = useState(userProfile?.academicYear || '');
  const [sectionName, setSectionName] = useState(userProfile?.sectionName || '');
  const [saving, setSaving] = useState(false);

  const selectedDept = useMemo(
    () => departments.find((d) => d.id === selectedDeptId) || null,
    [departments, selectedDeptId]
  );

  const yearOptions = useMemo(() => {
    if (selectedDept?.years?.length) return selectedDept.years;
    return DEFAULT_YEARS;
  }, [selectedDept]);

  const handleSave = async (e) => {
    e.preventDefault();
    const chosenYear = (academicYear || yearOptions[0] || '').trim();
    const chosenSection = (sectionName || DEFAULT_SECTIONS[0]).trim();
    if (!chosenYear || !chosenSection) return;
    setSaving(true);
    try {
      await saveUserAcademicPlacement(db, auth, {
        departmentId: selectedDept?.id || null,
        departmentName: selectedDept?.name || strings.unassignedDept,
        academicYear: chosenYear,
        sectionName: chosenSection
      });
      onShowToast(strings.savePlacementAction);
      onClose?.();
    } catch (err) {
      onShowToast(err.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={() => (canDismiss ? onClose?.() : null)}>
      <form className="modal-panel" onClick={(e) => e.stopPropagation()} onSubmit={handleSave}>
        <div className="row-between">
          <div className="row-gap">
            <div className="pill pill-primary" style={{ padding: '8px', borderRadius: '10px' }}>
              <GraduationCap size={20} />
            </div>
            <h3 style={{ margin: 0 }}>{strings.academicPlacementTitle}</h3>
          </div>
          {canDismiss ? (
            <button type="button" className="icon-btn" onClick={onClose}>
              <X size={18} />
            </button>
          ) : null}
        </div>

        <p className="muted" style={{ margin: 0, lineHeight: 1.55 }}>
          {strings.academicPlacementSub}
        </p>

        {departments.length > 0 ? (
          <div className="field-group">
            <span className="field-label">{strings.selectDepartmentLabel}</span>
            <div className="chip-row">
              {departments.map((d) => (
                <button
                  key={d.id}
                  type="button"
                  className={`m3-chip ${selectedDeptId === d.id ? 'selected' : ''}`}
                  onClick={() => {
                    setSelectedDeptId(d.id);
                    if (d.years?.length && !d.years.includes(academicYear)) {
                      setAcademicYear(d.years[0]);
                    }
                  }}
                >
                  {d.name}
                </button>
              ))}
            </div>
          </div>
        ) : null}

        <div className="field-group">
          <span className="field-label">{strings.selectYearLabel}</span>
          <div className="chip-row">
            {yearOptions.map((yr) => (
              <button
                key={yr}
                type="button"
                className={`m3-chip ${academicYear === yr ? 'selected' : ''}`}
                onClick={() => setAcademicYear(yr)}
              >
                {yr}
              </button>
            ))}
          </div>
          <input
            className="m3-input"
            placeholder={strings.selectYearLabel}
            value={academicYear}
            onChange={(e) => setAcademicYear(e.target.value)}
            required
          />
        </div>

        <div className="field-group">
          <span className="field-label">{strings.sectionLabel}</span>
          <div className="chip-row">
            {DEFAULT_SECTIONS.map((sec) => (
              <button
                key={sec}
                type="button"
                className={`m3-chip ${sectionName === sec ? 'selected' : ''}`}
                onClick={() => setSectionName(sec)}
              >
                {sec}
              </button>
            ))}
          </div>
          <input
            className="m3-input"
            placeholder={strings.sectionHint}
            value={sectionName}
            onChange={(e) => setSectionName(e.target.value)}
            required
          />
        </div>

        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          {canDismiss ? (
            <button type="button" className="btn btn-text" onClick={onClose}>
              {strings.cancelAction}
            </button>
          ) : (
            <span />
          )}
          <button
            type="submit"
            className="btn btn-primary"
            disabled={saving || !academicYear.trim() || !sectionName.trim()}
          >
            {saving ? <span className="spinner" /> : strings.savePlacementAction}
          </button>
        </div>
      </form>
    </div>
  );
}
