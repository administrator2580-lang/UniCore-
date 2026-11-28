import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Image as ImageIcon, MessageSquare, Pin, PlayCircle, Send, Shield, Trash2, Users, X } from 'lucide-react';
import {
  buildChatRoomId,
  deleteChatMessage,
  deleteSectionMessage,
  matchesStudentScope,
  sendChatMessage,
  sendSectionMessage,
  subscribeChatMessages,
  subscribeSectionMessages
} from '../data/firestoreService';
import { validateOptionalHttpsUrl } from '../security/validators';

export default function ChatView({
  strings,
  db,
  auth,
  currentUserId,
  userRole,
  userProfile,
  departments,
  onShowToast
}) {
  const [chatTab, setChatTab] = useState('ROOMS'); // 'ROOMS' | 'SECTION'
  const [selectedDeptId, setSelectedDeptId] = useState(null);
  const [selectedYearIdx, setSelectedYearIdx] = useState(null);
  const [messages, setMessages] = useState([]);
  const [sectionMessages, setSectionMessages] = useState([]);
  const [text, setText] = useState('');
  const [sectionText, setSectionText] = useState('');
  const [sectionVideoUrl, setSectionVideoUrl] = useState('');
  const [sectionPinned, setSectionPinned] = useState(false);
  const [targetYear, setTargetYear] = useState(userProfile?.academicYear || 'الفرقة الأولى');
  const [targetSection, setTargetSection] = useState(userProfile?.sectionName || 'سكشن 1');
  const [imageFile, setImageFile] = useState(null);
  const [sending, setSending] = useState(false);
  const [zoomImageUrl, setZoomImageUrl] = useState(null);
  const bottomRef = useRef(null);

  const activeDept = useMemo(
    () => departments.find((d) => d.id === selectedDeptId) || null,
    [departments, selectedDeptId]
  );

  const roomId = useMemo(() => {
    if (!activeDept) return 'general';
    const yrIdx = selectedYearIdx ?? 0;
    return buildChatRoomId(activeDept.id, yrIdx);
  }, [activeDept, selectedYearIdx]);

  const activeYearName = useMemo(() => {
    if (!activeDept) return '';
    const yrIdx = selectedYearIdx ?? 0;
    return activeDept.years?.[yrIdx] || '';
  }, [activeDept, selectedYearIdx]);

  useEffect(() => {
    if (chatTab !== 'ROOMS') return undefined;
    const unsub = subscribeChatMessages(
      db,
      roomId,
      (list) => setMessages(list),
      (err) => onShowToast(err.message)
    );
    return () => unsub();
  }, [db, roomId, chatTab]);

  useEffect(() => {
    if (chatTab !== 'SECTION') return undefined;
    const unsub = subscribeSectionMessages(
      db,
      (list) => setSectionMessages(list),
      (err) => onShowToast(err.message)
    );
    return () => unsub();
  }, [db, chatTab]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages.length]);

  const isAdmin = userRole !== 'STUDENT';

  const scopedSectionMessages = useMemo(
    () => sectionMessages.filter((m) => matchesStudentScope(userProfile, userRole, m)),
    [sectionMessages, userProfile, userRole]
  );

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

  const handleSend = async (e) => {
    e.preventDefault();
    if (!text.trim() && !imageFile) return;
    setSending(true);
    try {
      await sendChatMessage(db, auth, {
        roomId,
        departmentName: activeDept?.name || '',
        academicYear: activeYearName,
        userRole,
        text,
        imageFile
      });
      setText('');
      setImageFile(null);
    } catch (err) {
      onShowToast(err.message);
    } finally {
      setSending(false);
    }
  };

  const handleSendSectionMsg = async (e) => {
    e.preventDefault();
    if (!sectionText.trim()) return;
    setSending(true);
    try {
      await sendSectionMessage(db, auth, {
        departmentId: activeDept?.id || userProfile?.departmentId || null,
        departmentName: activeDept?.name || userProfile?.departmentName || 'General',
        academicYear: isAdmin ? targetYear : userProfile?.academicYear || targetYear,
        sectionName: isAdmin ? targetSection : userProfile?.sectionName || targetSection,
        userRole,
        text: sectionText,
        isPinned: sectionPinned,
        videoUrl: sectionVideoUrl
      });
      setSectionText('');
      setSectionVideoUrl('');
      setSectionPinned(false);
    } catch (err) {
      onShowToast(err.message);
    } finally {
      setSending(false);
    }
  };

  const openVideoLink = async (url) => {
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

  const roleBadgeLabel = (role) =>
    role === 'SUPER_ADMIN'
      ? strings.roleSuperAdmin
      : role === 'ADMIN'
        ? strings.roleAdmin
        : strings.roleStudent;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
      <div className="chip-row">
        <button
          type="button"
          className={`m3-chip ${chatTab === 'ROOMS' ? 'selected' : ''}`}
          onClick={() => setChatTab('ROOMS')}
        >
          <MessageSquare size={15} />
          <span>{strings.chatRoomsSubTab}</span>
        </button>
        <button
          type="button"
          className={`m3-chip ${chatTab === 'SECTION' ? 'selected' : ''}`}
          onClick={() => setChatTab('SECTION')}
        >
          <Users size={15} />
          <span>{strings.sectionFeedSubTab}</span>
        </button>
      </div>

      {chatTab === 'ROOMS' ? (
        <>
          <div className="m3-card accent-secondary" style={{ gap: '10px' }}>
            <div className="row-gap">
              <MessageSquare size={22} style={{ color: 'var(--primary)' }} />
              <div>
                <div style={{ fontWeight: 800, fontSize: '1.08rem' }}>{strings.chatTitle}</div>
                <div className="muted" style={{ fontSize: '0.82rem' }}>
                  {strings.chatSubtitle}
                </div>
              </div>
            </div>

            <div className="chip-scroll">
              <button
                type="button"
                className={`m3-chip ${!selectedDeptId ? 'selected' : ''}`}
                onClick={() => {
                  setSelectedDeptId(null);
                  setSelectedYearIdx(null);
                }}
              >
                {strings.chatGeneralRoom}
              </button>
              {departments.map((dept) => (
                <button
                  key={dept.id}
                  type="button"
                  className={`m3-chip ${selectedDeptId === dept.id ? 'selected' : ''}`}
                  onClick={() => {
                    setSelectedDeptId(dept.id);
                    setSelectedYearIdx(0);
                  }}
                >
                  {dept.name}
                </button>
              ))}
            </div>

            {activeDept?.years?.length ? (
              <div className="chip-scroll">
                {activeDept.years.map((yr, idx) => (
                  <button
                    key={yr}
                    type="button"
                    className={`m3-chip ${(selectedYearIdx ?? 0) === idx ? 'selected' : ''}`}
                    onClick={() => setSelectedYearIdx(idx)}
                  >
                    {yr}
                  </button>
                ))}
              </div>
            ) : null}
          </div>

          <div
            className="m3-card"
            style={{ minHeight: '360px', maxHeight: '520px', overflowY: 'auto', gap: '10px' }}
          >
            {messages.length === 0 ? (
              <div style={{ margin: 'auto', textAlign: 'center', padding: '24px' }}>
                <MessageSquare size={38} style={{ color: 'var(--primary)' }} />
                <div style={{ fontWeight: 700, marginTop: '8px' }}>{strings.chatEmptyTitle}</div>
                <div className="muted">{strings.chatEmptySub}</div>
              </div>
            ) : (
              messages.map((msg) => {
                const isMine = msg.senderUid === currentUserId;
                const canDelete = isMine || isAdmin;
                const imgDataUrl = msg.attachmentBase64
                  ? `data:${msg.attachmentMimeType || 'image/jpeg'};base64,${msg.attachmentBase64}`
                  : null;

                return (
                  <div
                    key={msg.id}
                    className="m3-subsurface"
                    style={{
                      alignSelf: isMine ? 'flex-end' : 'flex-start',
                      maxWidth: '86%',
                      minWidth: '210px',
                      background: isMine
                        ? 'color-mix(in srgb, var(--primary-container) 55%, var(--surface))'
                        : 'var(--surface-variant)'
                    }}
                  >
                    <div className="row-between" style={{ gap: '8px' }}>
                      <div className="row-gap" style={{ gap: '6px' }}>
                        {msg.senderRole !== 'STUDENT' ? (
                          <Shield size={13} style={{ color: 'var(--primary)' }} />
                        ) : null}
                        <span style={{ fontWeight: 700, fontSize: '0.84rem' }}>{msg.senderName}</span>
                        <span
                          className={`pill ${msg.senderRole !== 'STUDENT' ? 'pill-primary' : 'pill-secondary'}`}
                          style={{ fontSize: '0.72rem', padding: '2px 7px' }}
                        >
                          {roleBadgeLabel(msg.senderRole)}
                        </span>
                      </div>
                      {canDelete ? (
                        <button
                          type="button"
                          className="icon-btn"
                          style={{ width: '26px', height: '26px', color: 'var(--error)' }}
                          onClick={() => deleteChatMessage(db, auth, roomId, msg.id).catch((e) => onShowToast(e.message))}
                          title={strings.deleteLabel}
                        >
                          <Trash2 size={14} />
                        </button>
                      ) : null}
                    </div>

                    {msg.text ? (
                      <div style={{ fontSize: '0.94rem', lineHeight: 1.5, whiteSpace: 'pre-wrap' }}>{msg.text}</div>
                    ) : null}

                    {imgDataUrl ? (
                      <img
                        src={imgDataUrl}
                        alt={msg.attachmentName || 'Chat attachment'}
                        onClick={() => setZoomImageUrl(imgDataUrl)}
                        style={{
                          maxWidth: '100%',
                          maxHeight: '240px',
                          borderRadius: '10px',
                          objectFit: 'contain',
                          cursor: 'pointer'
                        }}
                      />
                    ) : null}
                  </div>
                );
              })
            )}
            <div ref={bottomRef} />
          </div>

          <form className="m3-card" onSubmit={handleSend} style={{ gap: '10px' }}>
            {imageFile ? (
              <div className="row-between pill pill-secondary" style={{ padding: '6px 12px', borderRadius: '10px' }}>
                <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{imageFile.name}</span>
                <button
                  type="button"
                  className="icon-btn"
                  style={{ width: '24px', height: '24px' }}
                  onClick={() => setImageFile(null)}
                >
                  <X size={14} />
                </button>
              </div>
            ) : null}

            <div className="row-gap">
              <button
                type="button"
                className="icon-btn tonal"
                onClick={pickImage}
                title={strings.attachPhotoAction}
              >
                <ImageIcon size={19} />
              </button>
              <input
                className="m3-input"
                style={{ flex: 1 }}
                placeholder={strings.chatInputHint}
                value={text}
                onChange={(e) => setText(e.target.value)}
              />
              <button
                type="submit"
                className="btn btn-primary"
                disabled={sending || (!text.trim() && !imageFile)}
              >
                {sending ? <span className="spinner" /> : <Send size={17} />}
                <span>{strings.sendAction}</span>
              </button>
            </div>
          </form>
        </>
      ) : (
        /* Dedicated Section Messages Feed */
        <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
          <form className="m3-card" onSubmit={handleSendSectionMsg}>
            <div className="row-gap">
              <Users size={20} style={{ color: 'var(--primary)' }} />
              <div style={{ fontWeight: 800 }}>
                {strings.sectionFeedSubTab}
                {userProfile?.sectionName ? ` • ${userProfile.academicYear || ''} • ${userProfile.sectionName}` : ''}
              </div>
            </div>

            {isAdmin ? (
              <div className="row-gap">
                <input
                  className="m3-input"
                  placeholder={strings.selectYearLabel}
                  value={targetYear}
                  onChange={(e) => setTargetYear(e.target.value)}
                  required
                />
                <input
                  className="m3-input"
                  placeholder={strings.sectionLabel}
                  value={targetSection}
                  onChange={(e) => setTargetSection(e.target.value)}
                  required
                />
              </div>
            ) : null}

            <textarea
              className="m3-textarea"
              rows={2}
              placeholder={strings.chatInputHint}
              value={sectionText}
              onChange={(e) => setSectionText(e.target.value)}
              required
            />

            <div className="row-gap" style={{ flexWrap: 'wrap' }}>
              <input
                className="m3-input"
                style={{ flex: '1 1 220px' }}
                placeholder={strings.lectureVideoUrlLabel}
                value={sectionVideoUrl}
                onChange={(e) => setSectionVideoUrl(e.target.value)}
              />
              {isAdmin ? (
                <label className="row-gap" style={{ cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={sectionPinned}
                    onChange={(e) => setSectionPinned(e.target.checked)}
                    style={{ width: '17px', height: '17px' }}
                  />
                  <span>{strings.pinMessageLabel}</span>
                </label>
              ) : null}
              <button type="submit" className="btn btn-primary" disabled={sending || !sectionText.trim()}>
                {sending ? <span className="spinner" /> : <Send size={16} />}
                <span>{strings.postSectionNoticeAction}</span>
              </button>
            </div>
          </form>

          {scopedSectionMessages.length === 0 ? (
            <div className="m3-card" style={{ alignItems: 'center', textAlign: 'center', padding: '26px' }}>
              <Users size={36} style={{ color: 'var(--primary)' }} />
              <div style={{ fontWeight: 700 }}>{strings.chatEmptyTitle}</div>
            </div>
          ) : (
            scopedSectionMessages.map((msg) => {
              const canDelete = isAdmin || msg.senderUid === currentUserId;
              return (
                <div key={msg.id} className={`m3-card ${msg.isPinned ? 'accent-secondary' : ''}`}>
                  <div className="row-between" style={{ flexWrap: 'wrap' }}>
                    <div className="chip-row">
                      {msg.isPinned ? (
                        <span className="pill pill-primary">
                          <Pin size={12} />
                          <span>{strings.pinnedBadge}</span>
                        </span>
                      ) : null}
                      {msg.messageType === 'NOTICE' ? (
                        <span className="pill pill-tertiary">{strings.sectionNoticeBadge}</span>
                      ) : null}
                      <span style={{ fontWeight: 700 }}>{msg.senderName}</span>
                      <span className="muted" style={{ fontSize: '0.8rem' }}>
                        {[msg.departmentName, msg.academicYear, msg.sectionName].filter(Boolean).join(' • ')}
                      </span>
                    </div>
                    {canDelete ? (
                      <button
                        type="button"
                        className="icon-btn"
                        style={{ color: 'var(--error)' }}
                        onClick={() => deleteSectionMessage(db, msg.id).catch((e) => onShowToast(e.message))}
                      >
                        <Trash2 size={15} />
                      </button>
                    ) : null}
                  </div>
                  <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.55 }}>{msg.text}</div>
                  {msg.videoUrl ? (
                    <div>
                      <button type="button" className="btn btn-tonal" onClick={() => openVideoLink(msg.videoUrl)}>
                        <PlayCircle size={15} />
                        <span>{strings.watchVideoAction}</span>
                      </button>
                    </div>
                  ) : null}
                </div>
              );
            })
          )}
        </div>
      )}

      {zoomImageUrl ? (
        <div className="modal-backdrop" onClick={() => setZoomImageUrl(null)}>
          <div className="modal-panel" style={{ maxWidth: '820px' }} onClick={(e) => e.stopPropagation()}>
            <div className="row-between">
              <span style={{ fontWeight: 700 }}>{strings.chatTitle}</span>
              <button type="button" className="icon-btn" onClick={() => setZoomImageUrl(null)}>
                <X size={18} />
              </button>
            </div>
            <img
              src={zoomImageUrl}
              alt="Preview"
              style={{ width: '100%', maxHeight: '72vh', objectFit: 'contain', borderRadius: '10px' }}
            />
          </div>
        </div>
      ) : null}
    </div>
  );
}
