import React, { useEffect, useMemo, useState } from 'react';
import {
  Cloud,
  Download,
  ExternalLink,
  Eye,
  FileText,
  Folder,
  Image as ImageIcon,
  Pencil,
  Plus,
  RefreshCw,
  School,
  Search,
  Settings,
  Trash2,
  Upload,
  X
} from 'lucide-react';
import {
  createCourseWithOptionalFile,
  deleteCourse,
  deleteDepartment,
  downloadCourseAttachmentBytes,
  linkDriveFolder,
  saveDepartment,
  unlinkDriveFolder,
  updateCourseDetails,
  updateDriveFolder
} from '../data/firestoreService';
import { triggerBrowserFileOpen } from '../data/vaultStorage';
import { validateOptionalHttpsUrl } from '../security/validators';

const MATERIAL_TYPES = ['FILE', 'NOTES', 'PDF_LINK', 'ONEDRIVE_LINK'];

export default function CoursesView({
  strings,
  db,
  auth,
  userRole,
  courses,
  departments,
  driveFolders,
  driveFiles,
  isDriveRefreshing,
  onRefreshDrive,
  onShowToast
}) {
  const canManage = userRole !== 'STUDENT';
  const isSuperAdmin = userRole === 'SUPER_ADMIN';
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedFilter, setSelectedFilter] = useState(null);
  const [selectedDepartmentId, setSelectedDepartmentId] = useState(null);
  const [selectedYear, setSelectedYear] = useState(null);
  const [selectedLibrarySection, setSelectedLibrarySection] = useState(null);
  const [openingCourseId, setOpeningCourseId] = useState(null);
  const [openingDriveId, setOpeningDriveId] = useState(null);
  const [inAppPreview, setInAppPreview] = useState(null); // { title, url, mimeType, isIframe, revokeUrl }

  const [showCreateCourse, setShowCreateCourse] = useState(false);
  const [editingCourse, setEditingCourse] = useState(null);
  const [showStorageManagerModal, setShowStorageManagerModal] = useState(false);
  const [showDriveModal, setShowDriveModal] = useState(false);
  const [editingDriveFolder, setEditingDriveFolder] = useState(null);
  const [showDeptModal, setShowDeptModal] = useState(false);
  const [editingDept, setEditingDept] = useState(null);

  useEffect(() => {
    return () => {
      if (inAppPreview?.revokeUrl) URL.revokeObjectURL(inAppPreview.revokeUrl);
    };
  }, [inAppPreview]);

  const selectedDept = useMemo(
    () => departments.find((d) => d.id === selectedDepartmentId) || null,
    [departments, selectedDepartmentId]
  );

  const visibleDriveFolders = useMemo(
    () =>
      driveFolders.filter((f) => {
        const matchesDept =
          !selectedDept ||
          f.departmentId === selectedDept.id ||
          (f.departmentName || '').toLowerCase() === (selectedDept.name || '').toLowerCase();
        const matchesYear = !selectedYear || f.academicYear === selectedYear;
        return matchesDept && matchesYear;
      }),
    [driveFolders, selectedDept, selectedYear]
  );

  const scopedDriveFiles = useMemo(() => {
    const actualFiles = driveFiles.filter((file) => !file.isSubFolder);
    if (!selectedDept && !selectedYear) return actualFiles;
    const allowedIds = new Set(visibleDriveFolders.map((f) => f.folderId));
    return actualFiles.filter((file) => allowedIds.has(file.folderId));
  }, [driveFiles, visibleDriveFolders, selectedDept, selectedYear]);

  const librarySections = useMemo(() => {
    const set = new Set();
    for (const file of scopedDriveFiles) {
      const label = (file.subFolderPath || file.rootFolderTitle || file.folderTitle || '').trim();
      if (label) set.add(label);
    }
    return Array.from(set);
  }, [scopedDriveFiles]);

  useEffect(() => {
    if (selectedLibrarySection && !librarySections.includes(selectedLibrarySection)) {
      setSelectedLibrarySection(null);
    }
  }, [librarySections, selectedLibrarySection]);

  const filteredDriveFiles = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    return scopedDriveFiles.filter((f) => {
      const sectionLabel = (f.subFolderPath || f.rootFolderTitle || f.folderTitle || '').trim();
      const matchesSection = !selectedLibrarySection || sectionLabel === selectedLibrarySection;
      const matchesQuery =
        !q ||
        (f.name || '').toLowerCase().includes(q) ||
        (f.subFolderPath || '').toLowerCase().includes(q) ||
        (f.rootFolderTitle || '').toLowerCase().includes(q) ||
        (f.folderTitle || '').toLowerCase().includes(q);
      return matchesSection && matchesQuery;
    });
  }, [scopedDriveFiles, selectedLibrarySection, searchQuery]);

  const filteredCourses = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    return courses.filter((course) => {
      const matchesFilter = !selectedFilter || course.materialType === selectedFilter;
      const matchesDept =
        !selectedDept ||
        course.departmentId === selectedDept.id ||
        (course.departmentName || '').toLowerCase() === (selectedDept.name || '').toLowerCase();
      const matchesYear = !selectedYear || course.academicYear === selectedYear;
      const matchesQuery =
        !q ||
        (course.title || '').toLowerCase().includes(q) ||
        (course.description || '').toLowerCase().includes(q) ||
        (course.attachmentName || '').toLowerCase().includes(q) ||
        (course.departmentName || '').toLowerCase().includes(q) ||
        (course.academicYear || '').toLowerCase().includes(q);
      return matchesFilter && matchesDept && matchesYear && matchesQuery;
    });
  }, [courses, selectedFilter, selectedDept, selectedYear, searchQuery]);

  const openSafeExternalUrl = async (url) => {
    const check = validateOptionalHttpsUrl(url);
    if (!check.valid || !check.value) {
      onShowToast(check.reason || 'Invalid URL');
      return;
    }
    if (window.unicoreDesktop?.openExternalUrl) {
      await window.unicoreDesktop.openExternalUrl(check.value);
    } else {
      window.open(check.value, '_blank', 'noopener,noreferrer');
    }
  };

  const showBase64InModal = (title, base64, mimeType) => {
    const clean = atob(base64);
    const bytes = new Uint8Array(clean.length);
    for (let i = 0; i < clean.length; i++) bytes[i] = clean.charCodeAt(i);
    const blobUrl = URL.createObjectURL(new Blob([bytes], { type: mimeType || 'application/pdf' }));
    setInAppPreview({ title, url: blobUrl, mimeType, isIframe: mimeType === 'application/pdf', revokeUrl: blobUrl });
  };

  const handleOpenCourseFile = async (course) => {
    setOpeningCourseId(course.id);
    try {
      const res = await downloadCourseAttachmentBytes(db, course);
      if (window.unicoreDesktop?.openLocalCourseFile) {
        const saved = await window.unicoreDesktop.openLocalCourseFile({
          courseId: course.id,
          fileName: res.fileName,
          base64: res.base64
        });
        if (saved?.downloadsPath) {
          onShowToast(`${strings.downloadedSavedToast} ${saved.fileName || res.fileName}`);
        }
      } else {
        showBase64InModal(res.fileName, res.base64, res.mimeType);
      }
    } catch (err) {
      onShowToast(err.message || 'Failed to open attachment');
    } finally {
      setOpeningCourseId(null);
    }
  };

  const handleOpenDriveFile = async (item, previewInsideApp = false) => {
    setOpeningDriveId(item.fileId);
    try {
      if (window.unicoreDesktop?.downloadAndOpenDriveFile) {
        const res = await window.unicoreDesktop.downloadAndOpenDriveFile({
          ...item,
          openExternally: !previewInsideApp
        });
        if (res?.downloadsPath) {
          onShowToast(`${strings.downloadedSavedToast} ${res.fileName || item.name}`);
        }
        if (previewInsideApp && res?.base64) {
          showBase64InModal(res.fileName || item.name, res.base64, res.mimeType);
        } else if (previewInsideApp && res?.textContent) {
          const blobUrl = URL.createObjectURL(new Blob([res.textContent], { type: 'text/plain;charset=utf-8' }));
          setInAppPreview({
            title: res.fileName || item.name,
            url: blobUrl,
            mimeType: 'text/plain',
            isIframe: true,
            revokeUrl: blobUrl
          });
        } else if (res?.openedInBrowser) {
          onShowToast(item.name);
        }
      } else if (previewInsideApp && !item.isSubFolder) {
        setInAppPreview({
          title: item.name,
          url: `https://drive.google.com/file/d/${item.fileId}/preview`,
          mimeType: 'application/pdf',
          isIframe: true,
          revokeUrl: null
        });
      } else {
        const a = document.createElement('a');
        a.href = item.downloadUrl || item.viewUrl;
        a.download = item.name || 'material';
        a.target = '_blank';
        a.rel = 'noopener noreferrer';
        document.body.appendChild(a);
        a.click();
        a.remove();
      }
    } catch (err) {
      onShowToast(err.message || 'Failed to open study file');
    } finally {
      setOpeningDriveId(null);
    }
  };

  const handleQuickRenewable = async () => {
    try {
      await saveDepartment(db, auth, {
        existingDeptId: null,
        name: 'طاقة متجددة',
        years: ['الفرقة الأولى', 'الفرقة الثانية', 'الفرقة الثالثة', 'الفرقة الرابعة']
      });
      onShowToast('تم إضافة قسم «طاقة متجددة»');
    } catch (err) {
      onShowToast(err.message);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
      {/* Top Search & Add Course */}
      <div className="row-between" style={{ flexWrap: 'wrap' }}>
        <div style={{ position: 'relative', flex: '1 1 280px' }}>
          <Search
            size={18}
            style={{
              position: 'absolute',
              top: '50%',
              insetInlineStart: '12px',
              transform: 'translateY(-50%)',
              color: 'var(--text-secondary)'
            }}
          />
          <input
            className="m3-input"
            style={{ paddingInlineStart: '38px' }}
            placeholder={strings.searchCoursesHint}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </div>
        {canManage ? (
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => setShowCreateCourse(true)}
          >
            <Plus size={18} />
            <span>{strings.publishCourse}</span>
          </button>
        ) : null}
      </div>

      {/* Departments & Academic Years */}
      {(departments.length > 0 || canManage) ? (
        <div className="m3-card" style={{ gap: '10px', padding: '14px' }}>
          <div className="row-between">
            <span style={{ fontWeight: 700, color: 'var(--primary)', fontSize: '0.92rem' }}>
              {strings.departmentsTitle}
            </span>
            {canManage ? (
              <div className="row-gap">
                {selectedDept ? (
                  <button
                    type="button"
                    className="btn btn-text"
                    onClick={() => {
                      setEditingDept(selectedDept);
                      setShowDeptModal(true);
                    }}
                  >
                    <Pencil size={15} />
                    <span>{strings.editCourseAction}</span>
                  </button>
                ) : null}
                <button
                  type="button"
                  className="btn btn-tonal"
                  style={{ minHeight: '36px', padding: '6px 12px', fontSize: '0.84rem' }}
                  onClick={() => {
                    setEditingDept(null);
                    setShowDeptModal(true);
                  }}
                >
                  <Plus size={15} />
                  <span>{strings.addDepartmentAction}</span>
                </button>
              </div>
            ) : null}
          </div>

          {departments.length === 0 && canManage ? (
            <button type="button" className="btn btn-outlined" onClick={handleQuickRenewable}>
              <Plus size={16} />
              <span>{strings.quickRenewablePreset}</span>
            </button>
          ) : null}

          {departments.length > 0 ? (
            <div className="chip-scroll">
              <button
                type="button"
                className={`m3-chip ${selectedDepartmentId === null ? 'selected' : ''}`}
                onClick={() => {
                  setSelectedDepartmentId(null);
                  setSelectedYear(null);
                }}
              >
                {strings.allDepartments}
              </button>
              {departments.map((dept) => (
                <button
                  key={dept.id}
                  type="button"
                  className={`m3-chip ${selectedDepartmentId === dept.id ? 'selected' : ''}`}
                  onClick={() => {
                    if (selectedDepartmentId === dept.id) {
                      setSelectedDepartmentId(null);
                      setSelectedYear(null);
                    } else {
                      setSelectedDepartmentId(dept.id);
                      setSelectedYear(null);
                    }
                  }}
                >
                  {dept.name}
                </button>
              ))}
            </div>
          ) : null}

          {selectedDept?.years?.length > 0 ? (
            <div className="chip-scroll">
              <button
                type="button"
                className={`m3-chip ${selectedYear === null ? 'selected' : ''}`}
                onClick={() => setSelectedYear(null)}
              >
                {strings.allYears}
              </button>
              {selectedDept.years.map((yr) => (
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
        </div>
      ) : null}

      {/* Material Type Chips */}
      <div className="chip-scroll">
        <button
          type="button"
          className={`m3-chip ${selectedFilter === null ? 'selected' : ''}`}
          onClick={() => setSelectedFilter(null)}
        >
          {strings.allTypes}
        </button>
        {MATERIAL_TYPES.map((type) => (
          <button
            key={type}
            type="button"
            className={`m3-chip ${selectedFilter === type ? 'selected' : ''}`}
            onClick={() => setSelectedFilter(selectedFilter === type ? null : type)}
          >
            {type.replace('_', ' ')}
          </button>
        ))}
      </div>

      {/* Study Files Library Card (Drive acts purely as hidden backend storage; only Super Admin manages links) */}
      {(isSuperAdmin || isDriveRefreshing || filteredDriveFiles.length > 0) ? (
        <div className="m3-card accent-secondary">
          <div className="row-between" style={{ flexWrap: 'wrap' }}>
            <div className="row-gap">
              <Cloud size={22} style={{ color: 'var(--primary)' }} />
              <div>
                <div style={{ fontWeight: 700 }}>{strings.driveSyncTitle}</div>
                <div className="muted" style={{ fontSize: '0.82rem' }}>
                  {strings.driveSyncSubtitle}
                </div>
              </div>
            </div>
            <div className="row-gap">
              {visibleDriveFolders.length > 0 && window.unicoreDesktop?.syncDriveFolders ? (
                <button
                  type="button"
                  className="icon-btn tonal"
                  onClick={onRefreshDrive}
                  disabled={isDriveRefreshing}
                  title={strings.refreshDrive}
                >
                  <RefreshCw size={18} className={isDriveRefreshing ? 'spin' : ''} />
                </button>
              ) : null}
              {isSuperAdmin ? (
                <button
                  type="button"
                  className="btn btn-tonal"
                  onClick={() => setShowStorageManagerModal(true)}
                >
                  <Settings size={16} />
                  <span>{strings.linkDriveFolder}</span>
                </button>
              ) : null}
            </div>
          </div>

          {librarySections.length > 1 ? (
            <div className="chip-scroll">
              <button
                type="button"
                className={`m3-chip ${selectedLibrarySection === null ? 'selected' : ''}`}
                onClick={() => setSelectedLibrarySection(null)}
              >
                {strings.allSections}
              </button>
              {librarySections.map((section) => (
                <button
                  key={section}
                  type="button"
                  className={`m3-chip ${selectedLibrarySection === section ? 'selected' : ''}`}
                  onClick={() =>
                    setSelectedLibrarySection(selectedLibrarySection === section ? null : section)
                  }
                >
                  {section}
                </button>
              ))}
            </div>
          ) : null}

          {filteredDriveFiles.length === 0 && !isDriveRefreshing ? (
            isSuperAdmin ? <div className="muted">{strings.noDriveFilesYet}</div> : null
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {filteredDriveFiles.map((item) => {
                const subtitle = [item.subFolderPath, item.rootFolderTitle]
                  .filter(Boolean)
                  .join(' • ') || item.folderTitle;
                return (
                  <div
                    key={item.fileId}
                    className="m3-subsurface"
                    style={{
                      flexDirection: 'row',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      flexWrap: 'wrap',
                      gap: '8px',
                      background: 'var(--surface)'
                    }}
                  >
                    <div className="row-gap" style={{ minWidth: 0, flex: '1 1 200px' }}>
                      <FileText size={18} style={{ color: 'var(--primary)', flexShrink: 0 }} />
                      <div style={{ minWidth: 0 }}>
                        <div
                          style={{
                            fontWeight: 600,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap'
                          }}
                        >
                          {item.name}
                        </div>
                        {subtitle ? (
                          <div className="muted" style={{ fontSize: '0.78rem' }}>
                            {subtitle}
                          </div>
                        ) : null}
                      </div>
                    </div>
                    <div className="row-gap">
                      <button
                        type="button"
                        className="btn btn-tonal"
                        disabled={openingDriveId === item.fileId}
                        onClick={() => handleOpenDriveFile(item, true)}
                      >
                        <Eye size={15} />
                        <span>{strings.previewInAppAction}</span>
                      </button>
                      <button
                        type="button"
                        className="btn btn-outlined"
                        disabled={openingDriveId === item.fileId}
                        onClick={() => handleOpenDriveFile(item, false)}
                      >
                        {openingDriveId === item.fileId ? (
                          <span className="spinner" />
                        ) : (
                          <>
                            <Download size={15} />
                            <span>{strings.downloadAndOpenFile}</span>
                          </>
                        )}
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      ) : null}

      {/* Courses Feed */}
      {filteredCourses.length === 0 ? (
        <div className="m3-card" style={{ alignItems: 'center', textAlign: 'center', padding: '32px 20px' }}>
          <School size={42} style={{ color: 'var(--primary)' }} />
          <div style={{ fontWeight: 700, fontSize: '1.08rem' }}>{strings.noCoursesTitle}</div>
          <div className="muted">{userRole === 'STUDENT' ? strings.noCoursesStudentSub : strings.noCoursesAdminSub}</div>
        </div>
      ) : (
        filteredCourses.map((course) => {
          const kb = Math.max(1, Math.floor((course.attachmentSizeBytes || 0) / 1024));
          return (
            <div key={course.id} className="m3-card">
              <div className="row-between" style={{ flexWrap: 'wrap' }}>
                <div className="chip-row">
                  <span className="pill pill-primary">{String(course.materialType || 'NOTES').replace('_', ' ')}</span>
                  {course.departmentName ? (
                    <span className="pill pill-secondary">
                      {course.departmentName}
                      {course.academicYear ? ` • ${course.academicYear}` : ''}
                    </span>
                  ) : null}
                </div>
                <div className="row-gap">
                  <span className="muted" style={{ fontSize: '0.82rem' }}>
                    {strings.byInstructor} {course.instructorName}
                  </span>
                  {canManage ? (
                    <>
                      <button
                        type="button"
                        className="icon-btn"
                        style={{ color: 'var(--primary)' }}
                        onClick={() => setEditingCourse(course)}
                        title={strings.editCourseAction}
                      >
                        <Pencil size={16} />
                      </button>
                      <button
                        type="button"
                        className="icon-btn"
                        style={{ color: 'var(--error)' }}
                        onClick={() => deleteCourse(db, course.id).catch((e) => onShowToast(e.message))}
                        title={strings.deleteLabel}
                      >
                        <Trash2 size={16} />
                      </button>
                    </>
                  ) : null}
                </div>
              </div>

              <h3 style={{ margin: 0, fontSize: '1.15rem', fontWeight: 700 }}>{course.title}</h3>
              <p style={{ margin: 0, color: 'var(--text-secondary)', lineHeight: 1.5 }}>{course.description}</p>

              {course.hasAttachment && course.attachmentName ? (
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={openingCourseId === course.id}
                  onClick={() => handleOpenCourseFile(course)}
                >
                  {openingCourseId === course.id ? (
                    <>
                      <span className="spinner" />
                      <span>{strings.downloadingLabel}</span>
                    </>
                  ) : (
                    <>
                      <Download size={17} />
                      <span>
                        {strings.downloadAndOpenFile}: {course.attachmentName} ({kb} KB)
                      </span>
                    </>
                  )}
                </button>
              ) : null}

              {course.materialLink ? (
                <div>
                  <button
                    type="button"
                    className="btn btn-outlined"
                    onClick={() => openSafeExternalUrl(course.materialLink)}
                  >
                    <ExternalLink size={16} />
                    <span>{strings.openResource}</span>
                  </button>
                </div>
              ) : null}
            </div>
          );
        })
      )}

      {/* Modals */}
      {showCreateCourse ? (
        <CreateCourseModal
          strings={strings}
          departments={departments}
          initialDeptId={selectedDept?.id || ''}
          initialYear={selectedYear || ''}
          onClose={() => setShowCreateCourse(false)}
          onSubmit={async (payload) => {
            try {
              await createCourseWithOptionalFile(db, auth, payload);
              onShowToast(strings.publishCourse);
              setShowCreateCourse(false);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}

      {editingCourse ? (
        <EditCourseModal
          strings={strings}
          course={editingCourse}
          departments={departments}
          onClose={() => setEditingCourse(null)}
          onSubmit={async (payload) => {
            try {
              await updateCourseDetails(db, payload);
              setEditingCourse(null);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}

      {showStorageManagerModal && isSuperAdmin ? (
        <StorageVaultManagerModal
          strings={strings}
          folders={driveFolders}
          onClose={() => setShowStorageManagerModal(false)}
          onAddNew={() => {
            setEditingDriveFolder(null);
            setShowDriveModal(true);
          }}
          onEdit={(folder) => {
            setEditingDriveFolder(folder);
            setShowDriveModal(true);
          }}
          onDelete={async (folderId) => {
            try {
              await unlinkDriveFolder(db, folderId, auth);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}

      {showDriveModal && isSuperAdmin ? (
        <LinkDriveModal
          strings={strings}
          departments={departments}
          existing={editingDriveFolder}
          initialDeptId={editingDriveFolder?.departmentId || selectedDept?.id || ''}
          initialYear={editingDriveFolder?.academicYear || selectedYear || ''}
          onClose={() => {
            setShowDriveModal(false);
            setEditingDriveFolder(null);
          }}
          onSubmit={async (payload) => {
            try {
              if (editingDriveFolder) {
                await updateDriveFolder(db, auth, {
                  existingFolderId: editingDriveFolder.folderId,
                  ...payload
                });
              } else {
                await linkDriveFolder(db, auth, payload);
              }
              setShowDriveModal(false);
              setEditingDriveFolder(null);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}

      {showDeptModal ? (
        <DepartmentEditorModal
          strings={strings}
          existing={editingDept}
          onClose={() => {
            setShowDeptModal(false);
            setEditingDept(null);
          }}
          onSave={async (name, years) => {
            try {
              await saveDepartment(db, auth, {
                existingDeptId: editingDept?.id || null,
                name,
                years
              });
              setShowDeptModal(false);
              setEditingDept(null);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
          onDelete={
            editingDept
              ? async () => {
                  try {
                    if (selectedDepartmentId === editingDept.id) {
                      setSelectedDepartmentId(null);
                      setSelectedYear(null);
                    }
                    await deleteDepartment(db, auth, editingDept.id);
                    setShowDeptModal(false);
                    setEditingDept(null);
                  } catch (err) {
                    onShowToast(err.message);
                  }
                }
              : null
          }
        />
      ) : null}
      {inAppPreview ? (
        <div
          className="modal-backdrop"
          onClick={() => {
            if (inAppPreview.revokeUrl) URL.revokeObjectURL(inAppPreview.revokeUrl);
            setInAppPreview(null);
          }}
        >
          <div
            className="modal-panel"
            style={{ maxWidth: '920px', width: '94vw', height: '84vh' }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="row-between">
              <strong style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {inAppPreview.title}
              </strong>
              <button
                type="button"
                className="icon-btn"
                onClick={() => {
                  if (inAppPreview.revokeUrl) URL.revokeObjectURL(inAppPreview.revokeUrl);
                  setInAppPreview(null);
                }}
              >
                <X size={18} />
              </button>
            </div>
            {String(inAppPreview.mimeType || '').startsWith('image/') && !inAppPreview.isIframe ? (
              <img
                src={inAppPreview.url}
                alt={inAppPreview.title}
                style={{ width: '100%', flex: 1, minHeight: 0, objectFit: 'contain', borderRadius: '10px' }}
              />
            ) : (
              <iframe
                src={inAppPreview.url}
                title={inAppPreview.title}
                style={{ width: '100%', flex: 1, minHeight: '420px', border: '1px solid var(--outline-variant)', borderRadius: '10px' }}
              />
            )}
          </div>
        </div>
      ) : null}
    </div>
  );
}

function DeptYearPicker({ strings, departments, deptId, year, onChangeDept, onChangeYear }) {
  if (!departments?.length) return null;
  const activeDept = departments.find((d) => d.id === deptId) || null;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
      <span className="field-label">{strings.selectDepartmentLabel}</span>
      <div className="chip-scroll">
        <button
          type="button"
          className={`m3-chip ${!deptId ? 'selected' : ''}`}
          onClick={() => {
            onChangeDept(null);
            onChangeYear('');
          }}
        >
          {strings.unassignedDept}
        </button>
        {departments.map((d) => (
          <button
            key={d.id}
            type="button"
            className={`m3-chip ${deptId === d.id ? 'selected' : ''}`}
            onClick={() => {
              onChangeDept(d);
              if (!(d.years || []).includes(year)) onChangeYear('');
            }}
          >
            {d.name}
          </button>
        ))}
      </div>
      {activeDept?.years?.length ? (
        <>
          <span className="field-label">{strings.selectYearLabel}</span>
          <div className="chip-scroll">
            <button
              type="button"
              className={`m3-chip ${!year ? 'selected' : ''}`}
              onClick={() => onChangeYear('')}
            >
              {strings.allYears}
            </button>
            {activeDept.years.map((y) => (
              <button
                key={y}
                type="button"
                className={`m3-chip ${year === y ? 'selected' : ''}`}
                onClick={() => onChangeYear(y)}
              >
                {y}
              </button>
            ))}
          </div>
        </>
      ) : null}
    </div>
  );
}

function CreateCourseModal({ strings, departments, initialDeptId, initialYear, onClose, onSubmit }) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [materialLink, setMaterialLink] = useState('');
  const [materialType, setMaterialType] = useState('FILE');
  const [file, setFile] = useState(null);
  const [deptId, setDeptId] = useState(initialDeptId);
  const [year, setYear] = useState(initialYear);
  const [saving, setSaving] = useState(false);

  const chosenDept = departments.find((d) => d.id === deptId) || null;

  const pickFile = (accept) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.onchange = () => {
      const f = input.files?.[0];
      if (!f) return;
      setFile(f);
      setMaterialType('FILE');
      if (!title.trim()) {
        setTitle(f.name.replace(/\.[^.]+$/, '').slice(0, 60));
      }
    };
    input.click();
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    await onSubmit({
      title,
      description,
      materialLink,
      materialType,
      file,
      departmentId: chosenDept?.id || null,
      departmentName: chosenDept?.name || null,
      academicYear: year || null
    });
    setSaving(false);
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form className="modal-panel" onClick={(e) => e.stopPropagation()} onSubmit={handleSubmit}>
        <h3 style={{ margin: 0 }}>{strings.publishCourse}</h3>
        <div className="muted" style={{ fontSize: '0.82rem' }}>
          {strings.uploadFileHint}
        </div>
        <div className="row-gap">
          <button
            type="button"
            className="btn btn-tonal"
            style={{ flex: 1 }}
            onClick={() => pickFile('application/pdf')}
          >
            <Upload size={16} />
            <span>{strings.attachPdfButton}</span>
          </button>
          <button
            type="button"
            className="btn btn-outlined"
            style={{ flex: 1 }}
            onClick={() => pickFile('image/png,image/jpeg')}
          >
            <ImageIcon size={16} />
            <span>{strings.attachImageButton}</span>
          </button>
        </div>

        {file ? (
          <div className="row-between pill pill-secondary" style={{ padding: '8px 12px', borderRadius: '10px' }}>
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{file.name}</span>
            <button
              type="button"
              className="icon-btn"
              style={{ width: '26px', height: '26px' }}
              onClick={() => setFile(null)}
            >
              <X size={14} />
            </button>
          </div>
        ) : null}

        <div className="field-group">
          <label className="field-label">{strings.courseTitleLabel}</label>
          <input className="m3-input" value={title} onChange={(e) => setTitle(e.target.value)} required />
        </div>
        <div className="field-group">
          <label className="field-label">{strings.courseDescLabel}</label>
          <textarea className="m3-textarea" rows={2} value={description} onChange={(e) => setDescription(e.target.value)} required />
        </div>

        <DeptYearPicker
          strings={strings}
          departments={departments}
          deptId={deptId}
          year={year}
          onChangeDept={(d) => setDeptId(d?.id || '')}
          onChangeYear={setYear}
        />

        <div className="field-group">
          <label className="field-label">{strings.optionalLinkAndNotes}</label>
          <input
            className="m3-input"
            placeholder="https://..."
            value={materialLink}
            onChange={(e) => setMaterialLink(e.target.value)}
          />
        </div>

        <div className="chip-scroll">
          {MATERIAL_TYPES.map((t) => (
            <button
              key={t}
              type="button"
              className={`m3-chip ${materialType === t ? 'selected' : ''}`}
              onClick={() => setMaterialType(t)}
            >
              {t.replace('_', ' ')}
            </button>
          ))}
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

function EditCourseModal({ strings, course, departments, onClose, onSubmit }) {
  const [title, setTitle] = useState(course.title || '');
  const [description, setDescription] = useState(course.description || '');
  const [deptId, setDeptId] = useState(course.departmentId || '');
  const [year, setYear] = useState(course.academicYear || '');
  const chosenDept = departments.find((d) => d.id === deptId) || null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form
        className="modal-panel"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          onSubmit({
            courseId: course.id,
            title,
            description,
            departmentId: chosenDept?.id || null,
            departmentName: chosenDept?.name || null,
            academicYear: year || null
          });
        }}
      >
        <h3 style={{ margin: 0 }}>{strings.editCourseAction}</h3>
        <div className="field-group">
          <label className="field-label">{strings.courseTitleLabel}</label>
          <input className="m3-input" value={title} onChange={(e) => setTitle(e.target.value)} required />
        </div>
        <div className="field-group">
          <label className="field-label">{strings.courseDescLabel}</label>
          <textarea className="m3-textarea" rows={3} value={description} onChange={(e) => setDescription(e.target.value)} required />
        </div>
        <DeptYearPicker
          strings={strings}
          departments={departments}
          deptId={deptId}
          year={year}
          onChangeDept={(d) => setDeptId(d?.id || '')}
          onChangeYear={setYear}
        />
        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>
            {strings.cancelAction}
          </button>
          <button type="submit" className="btn btn-primary">
            {strings.publishAction}
          </button>
        </div>
      </form>
    </div>
  );
}

function StorageVaultManagerModal({ strings, folders, onClose, onAddNew, onEdit, onDelete }) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
        <div className="row-between">
          <h3 style={{ margin: 0 }}>{strings.manageStorageVault}</h3>
          <button type="button" className="icon-btn" onClick={onClose}>
            <X size={18} />
          </button>
        </div>
        <div className="muted" style={{ fontSize: '0.84rem' }}>
          {strings.storageVaultSubtitle}
        </div>

        {folders.length === 0 ? (
          <div className="muted" style={{ padding: '12px 0' }}>
            {strings.noStorageSourcesYet}
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', maxHeight: '320px', overflowY: 'auto' }}>
            {folders.map((folder) => {
              const scopeLabel = [folder.departmentName, folder.academicYear].filter(Boolean).join(' • ');
              return (
                <div
                  key={folder.folderId}
                  className="m3-subsurface"
                  style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: '8px' }}
                >
                  <div className="row-gap" style={{ minWidth: 0, flex: 1 }}>
                    <Folder size={18} style={{ color: 'var(--primary)', flexShrink: 0 }} />
                    <div style={{ minWidth: 0 }}>
                      <div style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {folder.title}
                      </div>
                      {scopeLabel ? (
                        <div className="muted" style={{ fontSize: '0.78rem' }}>
                          {scopeLabel}
                        </div>
                      ) : null}
                    </div>
                  </div>
                  <div className="row-gap">
                    <button
                      type="button"
                      className="icon-btn"
                      style={{ color: 'var(--primary)' }}
                      onClick={() => onEdit(folder)}
                      title={strings.editStorageSource}
                    >
                      <Pencil size={15} />
                    </button>
                    <button
                      type="button"
                      className="icon-btn"
                      style={{ color: 'var(--error)' }}
                      onClick={() => onDelete(folder.folderId)}
                      title={strings.deleteLabel}
                    >
                      <Trash2 size={15} />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}

        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>
            {strings.cancelAction}
          </button>
          <button type="button" className="btn btn-primary" onClick={onAddNew}>
            <Plus size={16} />
            <span>{strings.addStorageSource}</span>
          </button>
        </div>
      </div>
    </div>
  );
}

function LinkDriveModal({ strings, departments, existing, initialDeptId, initialYear, onClose, onSubmit }) {
  const [title, setTitle] = useState(existing?.title || '');
  const [folderUrl, setFolderUrl] = useState(existing?.folderUrl || '');
  const [deptId, setDeptId] = useState(initialDeptId);
  const [year, setYear] = useState(initialYear);
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
            folderUrl,
            departmentId: chosenDept?.id || null,
            departmentName: chosenDept?.name || null,
            academicYear: year || null
          });
        }}
      >
        <h3 style={{ margin: 0 }}>{existing ? strings.editStorageSource : strings.addStorageSource}</h3>
        <div className="muted" style={{ fontSize: '0.84rem' }}>
          {strings.driveSharingNote}
        </div>
        <div className="field-group">
          <label className="field-label">{strings.driveFolderTitleLabel}</label>
          <input className="m3-input" value={title} onChange={(e) => setTitle(e.target.value)} required />
        </div>
        <div className="field-group">
          <label className="field-label">{strings.driveFolderUrlLabel}</label>
          <input
            className="m3-input"
            placeholder="https://drive.google.com/drive/folders/..."
            value={folderUrl}
            onChange={(e) => setFolderUrl(e.target.value)}
            required
          />
        </div>
        <DeptYearPicker
          strings={strings}
          departments={departments}
          deptId={deptId}
          year={year}
          onChangeDept={(d) => setDeptId(d?.id || '')}
          onChangeYear={setYear}
        />
        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>
            {strings.cancelAction}
          </button>
          <button type="submit" className="btn btn-primary" disabled={!title.trim() || !folderUrl.trim()}>
            {strings.publishAction}
          </button>
        </div>
      </form>
    </div>
  );
}

function DepartmentEditorModal({ strings, existing, onClose, onSave, onDelete }) {
  const [name, setName] = useState(existing?.name || '');
  const [yearsText, setYearsText] = useState(
    existing?.years?.join('\n') ||
      ['الفرقة الأولى', 'الفرقة الثانية', 'الفرقة الثالثة', 'الفرقة الرابعة'].join('\n')
  );

  const parsedYears = yearsText
    .split(/[,،\n]+/)
    .map((y) => y.trim())
    .filter(Boolean);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form
        className="modal-panel"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          onSave(name, parsedYears);
        }}
      >
        <h3 style={{ margin: 0 }}>{existing ? strings.editDepartmentAction : strings.addDepartmentAction}</h3>
        <div className="field-group">
          <label className="field-label">{strings.departmentNameLabel}</label>
          <input className="m3-input" value={name} onChange={(e) => setName(e.target.value)} required />
        </div>
        <div className="field-group">
          <label className="field-label">{strings.studyYearsLabel}</label>
          <textarea
            className="m3-textarea"
            rows={4}
            placeholder={strings.studyYearsHint}
            value={yearsText}
            onChange={(e) => setYearsText(e.target.value)}
          />
        </div>
        <div className="row-between">
          {onDelete ? (
            <button type="button" className="btn btn-danger-outlined" onClick={onDelete}>
              <Trash2 size={15} />
              <span>{strings.deleteLabel}</span>
            </button>
          ) : (
            <span />
          )}
          <div className="row-gap">
            <button type="button" className="btn btn-text" onClick={onClose}>
              {strings.cancelAction}
            </button>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={!name.trim() || parsedYears.length === 0}
            >
              {strings.publishAction}
            </button>
          </div>
        </div>
      </form>
    </div>
  );
}
