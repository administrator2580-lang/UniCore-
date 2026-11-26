import React, { useEffect, useMemo, useState } from 'react';
import {
  Ban,
  BellRing,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Clock,
  Globe,
  Headset,
  Image as ImageIcon,
  LogOut,
  Moon,
  Palette,
  Reply,
  Search,
  Send,
  Settings,
  Shield,
  ShieldAlert,
  Sun,
  SunMoon,
  Trash2,
  UserPlus,
  Users,
  X
} from 'lucide-react';
import {
  getFirebaseRuntimeConfig,
  reinitializeFirebaseClients,
  saveFirebaseOverrideConfig
} from '../firebase/config';
import {
  demoteAdminToStudent,
  inviteAccountByEmail,
  promoteUserToAdmin,
  replyToSupportMessage,
  revokeInvite,
  sendSupportMessage,
  setUserAccountDisabled,
  setUserTimedBan,
  updateSystemSettingsAsSuperAdmin
} from '../data/firestoreService';

export default function AccountConsoleView({
  strings,
  db,
  auth,
  currentUserId,
  userRole,
  userProfile,
  systemSettings,
  language,
  themeMode,
  desktopNotificationsEnabled,
  onSetLanguage,
  onSetThemeMode,
  onSetDesktopNotifications,
  usersDirectory,
  roleInvites,
  supportMessages,
  auditLogs,
  onClientsUpdated,
  onSignOut,
  onShowToast
}) {
  const [showAuditLogs, setShowAuditLogs] = useState(false);
  const [showInviteModal, setShowInviteModal] = useState(false);
  const [showComposeSupport, setShowComposeSupport] = useState(false);
  const [replyingTo, setReplyingTo] = useState(null);
  const [banTargetUser, setBanTargetUser] = useState(null);
  const [zoomImageUrl, setZoomImageUrl] = useState(null);
  const [showConnectionModal, setShowConnectionModal] = useState(false);
  const [userSearch, setUserSearch] = useState('');

  const [aiEnabled, setAiEnabled] = useState(Boolean(systemSettings?.aiEnabled ?? true));
  const [zeroCostLock, setZeroCostLock] = useState(Boolean(systemSettings?.zeroCostLockActive ?? false));
  const [aiCap, setAiCap] = useState(String(systemSettings?.dailyAiLimitPerUser || 20));
  const [writeCap, setWriteCap] = useState(String(systemSettings?.dailyWriteLimitPerUser || 100));

  useEffect(() => {
    setAiEnabled(Boolean(systemSettings?.aiEnabled ?? true));
    setZeroCostLock(Boolean(systemSettings?.zeroCostLockActive ?? false));
    setAiCap(String(systemSettings?.dailyAiLimitPerUser || 20));
    setWriteCap(String(systemSettings?.dailyWriteLimitPerUser || 100));
  }, [systemSettings]);

  const writesUsed = Number(userProfile?.writesToday || 0);
  const writesCap = Math.max(1, Number(systemSettings?.dailyWriteLimitPerUser || 100));
  const writesPct = Math.min(100, Math.round((writesUsed / writesCap) * 100));

  const roleLabel =
    userRole === 'SUPER_ADMIN'
      ? strings.roleSuperAdmin
      : userRole === 'ADMIN'
        ? strings.roleAdmin
        : strings.roleStudent;

  const filteredUsers = useMemo(() => {
    const q = userSearch.trim().toLowerCase();
    if (!q) return usersDirectory;
    return usersDirectory.filter((u) => String(u.displayName || '').toLowerCase().includes(q));
  }, [usersDirectory, userSearch]);

  const handleToggleDesktopNotifs = async (checked) => {
    if (checked && typeof Notification !== 'undefined' && Notification.permission === 'default') {
      const perm = await Notification.requestPermission().catch(() => 'denied');
      onSetDesktopNotifications(perm === 'granted');
    } else {
      onSetDesktopNotifications(checked);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
      {/* Profile & Session Card */}
      <div className="m3-card">
        <div className="row-between" style={{ flexWrap: 'wrap' }}>
          <div className="row-gap">
            <div className="pill pill-primary" style={{ padding: '10px', borderRadius: '12px' }}>
              <Shield size={22} />
            </div>
            <div>
              <div style={{ fontWeight: 800, fontSize: '1.15rem' }}>
                {userProfile?.displayName || strings.appName}
              </div>
              <div className="muted" style={{ fontSize: '0.82rem' }}>
                {auth.currentUser?.email || ''}
              </div>
            </div>
          </div>
          <span className="pill pill-secondary">{roleLabel}</span>
        </div>

        <div className="muted">
          {strings.dailyWritesLabel}: {writesUsed} / {writesCap}
        </div>
        <div className="m3-progress-track">
          <div className="m3-progress-fill" style={{ width: `${writesPct}%`, background: 'var(--primary)' }} />
        </div>

        <button type="button" className="btn btn-outlined" onClick={onSignOut}>
          <LogOut size={17} />
          <span>{strings.signOutAction}</span>
        </button>
      </div>

      {/* Appearance & Desktop Notifications */}
      <div className="m3-card">
        <div className="row-gap">
          <Palette size={20} style={{ color: 'var(--primary)' }} />
          <h3 style={{ margin: 0 }}>{strings.appearanceCardTitle}</h3>
        </div>

        <span className="field-label">{strings.languageLabel}</span>
        <div className="row-gap">
          <button
            type="button"
            className={`m3-chip ${language === 'ARABIC' ? 'selected' : ''}`}
            style={{ flex: 1, justifyContent: 'center' }}
            onClick={() => onSetLanguage('ARABIC')}
          >
            <Globe size={15} />
            <span>العربية</span>
          </button>
          <button
            type="button"
            className={`m3-chip ${language === 'ENGLISH' ? 'selected' : ''}`}
            style={{ flex: 1, justifyContent: 'center' }}
            onClick={() => onSetLanguage('ENGLISH')}
          >
            <Globe size={15} />
            <span>English</span>
          </button>
        </div>

        <span className="field-label">{strings.themeLabel}</span>
        <div className="row-gap">
          <button
            type="button"
            className={`m3-chip ${themeMode === 'SYSTEM' ? 'selected' : ''}`}
            style={{ flex: 1, justifyContent: 'center' }}
            onClick={() => onSetThemeMode('SYSTEM')}
          >
            <SunMoon size={15} />
            <span>{strings.themeSystem}</span>
          </button>
          <button
            type="button"
            className={`m3-chip ${themeMode === 'DARK' ? 'selected' : ''}`}
            style={{ flex: 1, justifyContent: 'center' }}
            onClick={() => onSetThemeMode('DARK')}
          >
            <Moon size={15} />
            <span>{strings.themeDark}</span>
          </button>
          <button
            type="button"
            className={`m3-chip ${themeMode === 'LIGHT' ? 'selected' : ''}`}
            style={{ flex: 1, justifyContent: 'center' }}
            onClick={() => onSetThemeMode('LIGHT')}
          >
            <Sun size={15} />
            <span>{strings.themeLight}</span>
          </button>
        </div>

        <label className="row-between" style={{ cursor: 'pointer', paddingTop: '4px' }}>
          <span className="row-gap">
            <BellRing size={18} style={{ color: 'var(--secondary)' }} />
            <span>{strings.notificationsEnabledLabel}</span>
          </span>
          <input
            type="checkbox"
            checked={desktopNotificationsEnabled}
            onChange={(e) => handleToggleDesktopNotifs(e.target.checked)}
            style={{ width: '18px', height: '18px' }}
          />
        </label>
      </div>

      {/* Tiered Support & Escalation Messaging */}
      <div className="m3-card">
        <div className="row-between" style={{ flexWrap: 'wrap' }}>
          <div className="row-gap">
            <Headset size={20} style={{ color: 'var(--primary)' }} />
            <h3 style={{ margin: 0 }}>{strings.supportSectionTitle}</h3>
          </div>
          {userRole !== 'SUPER_ADMIN' ? (
            <button type="button" className="btn btn-primary" onClick={() => setShowComposeSupport(true)}>
              <Send size={15} />
              <span>{userRole === 'ADMIN' ? strings.contactSuperAdminAction : strings.contactAdminAction}</span>
            </button>
          ) : null}
        </div>

        {supportMessages.length === 0 ? (
          <div className="muted">{strings.noSupportMessages}</div>
        ) : (
          <SupportInboxContent
            strings={strings}
            userRole={userRole}
            currentUserId={currentUserId}
            messages={supportMessages}
            onReply={(msg) => setReplyingTo(msg)}
            onZoomImage={(url) => setZoomImageUrl(url)}
          />
        )}
      </div>

      {/* Accounts & Role Management (Admin + Super Admin) */}
      {userRole !== 'STUDENT' ? (
        <div className="m3-card">
          <div className="row-between" style={{ flexWrap: 'wrap' }}>
            <div className="row-gap">
              <Users size={20} style={{ color: 'var(--primary)' }} />
              <h3 style={{ margin: 0 }}>{strings.accountsSectionTitle}</h3>
            </div>
            <button type="button" className="btn btn-primary" onClick={() => setShowInviteModal(true)}>
              <UserPlus size={16} />
              <span>{strings.addAccountAction}</span>
            </button>
          </div>
          <div className="muted">{strings.accountsSectionSub}</div>

          {roleInvites.length > 0 ? (
            <>
              <div style={{ fontWeight: 700, fontSize: '0.95rem' }}>
                {strings.pendingInvitesTitle} ({roleInvites.length})
              </div>
              {roleInvites.map((invite) => (
                <div key={invite.email} className="m3-subsurface" style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
                  <div>
                    <div style={{ fontWeight: 700 }}>
                      {invite.displayNameHint} • {invite.assignedRole}
                    </div>
                    <div className="muted" style={{ fontSize: '0.82rem' }}>
                      {invite.email} • {invite.status === 'CLAIMED' ? strings.claimedStatus : strings.pendingStatus}
                    </div>
                  </div>
                  {(userRole === 'SUPER_ADMIN' || invite.assignedRole === 'STUDENT') ? (
                    <button
                      type="button"
                      className="icon-btn"
                      style={{ color: 'var(--error)' }}
                      onClick={() => revokeInvite(db, auth, invite.email).catch((e) => onShowToast(e.message))}
                      title={strings.revokeInvite}
                    >
                      <Trash2 size={16} />
                    </button>
                  ) : null}
                </div>
              ))}
            </>
          ) : null}

          <div style={{ fontWeight: 700, fontSize: '0.95rem' }}>
            {strings.registeredUsersTitle} ({usersDirectory.length})
          </div>

          {usersDirectory.length > 3 ? (
            <div style={{ position: 'relative' }}>
              <Search
                size={16}
                style={{ position: 'absolute', top: '50%', insetInlineStart: '12px', transform: 'translateY(-50%)', color: 'var(--text-secondary)' }}
              />
              <input
                className="m3-input"
                style={{ paddingInlineStart: '36px' }}
                placeholder={strings.searchUsersHint}
                value={userSearch}
                onChange={(e) => setUserSearch(e.target.value)}
              />
            </div>
          ) : null}

          {filteredUsers.map((u) => {
            const disabled = Boolean(u.accountDisabled);
            const banUntil = Number(u.banUntilMillis || 0);
            const isTimedBanned = banUntil > Date.now();
            const canBanTarget =
              u.userId !== currentUserId &&
              u.role !== 'SUPER_ADMIN' &&
              (userRole === 'SUPER_ADMIN' || u.role === 'STUDENT');

            const rLabel =
              u.role === 'SUPER_ADMIN'
                ? strings.roleSuperAdmin
                : u.role === 'ADMIN'
                  ? strings.roleAdmin
                  : strings.roleStudent;
            return (
              <div
                key={u.userId}
                className="m3-subsurface"
                style={
                  disabled || isTimedBanned
                    ? { background: 'color-mix(in srgb, var(--error-container) 28%, transparent)' }
                    : undefined
                }
              >
                <div className="row-between">
                  <div>
                    <div style={{ fontWeight: 700 }}>{u.displayName}</div>
                    <div style={{ color: 'var(--primary)', fontSize: '0.82rem', fontWeight: 600 }}>
                      {rLabel} • AI {u.aiCallsToday || 0} • Sync {u.writesToday || 0}
                    </div>
                    {isTimedBanned ? (
                      <div style={{ color: 'var(--error)', fontSize: '0.78rem', fontWeight: 600 }}>
                        {strings.bannedUntilLabel}: {new Date(banUntil).toLocaleString()}
                        {u.banReason ? ` (${u.banReason})` : ''}
                      </div>
                    ) : null}
                  </div>
                  <span className={`pill ${disabled || isTimedBanned ? 'pill-error' : 'pill-secondary'}`}>
                    {disabled || isTimedBanned ? strings.accountStatusDisabled : strings.accountStatusActive}
                  </span>
                </div>

                {(canBanTarget || (userRole === 'SUPER_ADMIN' && u.userId !== currentUserId && u.role !== 'SUPER_ADMIN')) ? (
                  <div className="chip-row">
                    {canBanTarget ? (
                      <button
                        type="button"
                        className="btn btn-outlined"
                        onClick={() => setBanTargetUser(u)}
                      >
                        <Clock size={15} />
                        <span>{isTimedBanned ? strings.unbanUserAction : strings.banUserAction}</span>
                      </button>
                    ) : null}

                    {userRole === 'SUPER_ADMIN' && u.userId !== currentUserId && u.role !== 'SUPER_ADMIN' ? (
                      <>
                        {u.role === 'STUDENT' ? (
                          <button
                            type="button"
                            className="btn btn-outlined"
                            onClick={() => promoteUserToAdmin(db, auth, u).catch((e) => onShowToast(e.message))}
                          >
                            {strings.promoteToAdmin}
                          </button>
                        ) : u.role === 'ADMIN' ? (
                          <button
                            type="button"
                            className="btn btn-outlined"
                            onClick={() => demoteAdminToStudent(db, auth, u.userId).catch((e) => onShowToast(e.message))}
                          >
                            {strings.demoteToStudent}
                          </button>
                        ) : null}

                        <button
                          type="button"
                          className={`btn ${disabled ? 'btn-outlined' : 'btn-danger-outlined'}`}
                          onClick={() =>
                            setUserAccountDisabled(db, auth, u, !disabled).catch((e) => onShowToast(e.message))
                          }
                        >
                          {disabled ? <CheckCircle2 size={15} /> : <Ban size={15} />}
                          <span>{disabled ? strings.enableAccountAction : strings.disableAccountAction}</span>
                        </button>
                      </>
                    ) : null}
                  </div>
                ) : null}
              </div>
            );
          })}
        </div>
      ) : null}

      {/* Super Admin Policy & Connection Controls */}
      {userRole === 'SUPER_ADMIN' ? (
        <div className="m3-card">
          <div className="row-between">
            <div className="row-gap">
              <Settings size={20} style={{ color: 'var(--tertiary)' }} />
              <h3 style={{ margin: 0 }}>{strings.governanceTitle}</h3>
            </div>
            <button
              type="button"
              className="btn btn-outlined"
              onClick={() => setShowConnectionModal(true)}
            >
              <Settings size={15} />
              <span>{strings.firebaseSetupTitle}</span>
            </button>
          </div>

          <label className="row-between" style={{ cursor: 'pointer' }}>
            <span>{strings.enableAiTutorSwitch}</span>
            <input
              type="checkbox"
              checked={aiEnabled}
              onChange={(e) => setAiEnabled(e.target.checked)}
              style={{ width: '18px', height: '18px' }}
            />
          </label>

          <label className="row-between" style={{ cursor: 'pointer' }}>
            <span>{strings.emergencyZeroCostLock}</span>
            <input
              type="checkbox"
              checked={zeroCostLock}
              onChange={(e) => setZeroCostLock(e.target.checked)}
              style={{ width: '18px', height: '18px' }}
            />
          </label>

          <div className="row-gap">
            <div className="field-group">
              <label className="field-label">{strings.aiCapPerDay}</label>
              <input
                className="m3-input"
                type="number"
                min={1}
                max={100}
                value={aiCap}
                onChange={(e) => setAiCap(e.target.value)}
              />
            </div>
            <div className="field-group">
              <label className="field-label">{strings.writesCapPerDay}</label>
              <input
                className="m3-input"
                type="number"
                min={10}
                max={500}
                value={writeCap}
                onChange={(e) => setWriteCap(e.target.value)}
              />
            </div>
          </div>

          <button
            type="button"
            className="btn btn-primary"
            onClick={async () => {
              try {
                await updateSystemSettingsAsSuperAdmin(db, auth, {
                  dailyAiLimit: aiCap,
                  dailyWriteLimit: writeCap,
                  aiEnabled,
                  zeroCostLockActive: zeroCostLock
                });
                onShowToast(strings.savePolicyButton);
              } catch (err) {
                onShowToast(err.message);
              }
            }}
          >
            {strings.savePolicyButton}
          </button>
        </div>
      ) : null}

      {/* Security Audit Logs: STRICTLY SUPER_ADMIN ONLY */}
      {userRole === 'SUPER_ADMIN' ? (
        <div className="m3-card">
          <div
            className="row-between"
            style={{ cursor: 'pointer' }}
            onClick={() => setShowAuditLogs(!showAuditLogs)}
          >
            <div className="row-gap">
              <ShieldAlert size={20} style={{ color: 'var(--primary)' }} />
              <div>
                <div style={{ fontWeight: 700 }}>{strings.auditLogsTitle}</div>
                <div className="muted" style={{ fontSize: '0.82rem' }}>{strings.auditLogsSubtitle}</div>
              </div>
            </div>
            {showAuditLogs ? <ChevronUp size={20} /> : <ChevronDown size={20} />}
          </div>

          {showAuditLogs ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {auditLogs.map((log) => (
                <div key={log.id} className="m3-subsurface">
                  <div className="row-between">
                    <span style={{ color: 'var(--primary)', fontWeight: 700, fontSize: '0.82rem' }}>
                      {log.action}
                    </span>
                    <span
                      style={{
                        fontWeight: 700,
                        fontSize: '0.8rem',
                        color:
                          log.severity === 'CRITICAL' || log.severity === 'HIGH'
                            ? 'var(--error)'
                            : log.severity === 'WARN'
                              ? 'var(--tertiary)'
                              : 'var(--secondary)'
                      }}
                    >
                      {log.severity}
                    </span>
                  </div>
                  <div style={{ fontSize: '0.9rem' }}>{log.details}</div>
                  <div className="muted" style={{ fontSize: '0.78rem' }}>
                    {log.actorLabel} • {log.targetPath}
                  </div>
                </div>
              ))}
            </div>
          ) : null}
        </div>
      ) : null}

      {showInviteModal ? (
        <InviteModal
          strings={strings}
          isSuperAdmin={userRole === 'SUPER_ADMIN'}
          onClose={() => setShowInviteModal(false)}
          onSubmit={async (payload) => {
            try {
              await inviteAccountByEmail(db, auth, payload);
              setShowInviteModal(false);
              onShowToast(strings.addAccountAction);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}

      {banTargetUser ? (
        <TimedBanModal
          strings={strings}
          language={language}
          user={banTargetUser}
          onClose={() => setBanTargetUser(null)}
          onConfirm={async (minutes, reason) => {
            try {
              await setUserTimedBan(db, auth, banTargetUser, minutes, reason);
              setBanTargetUser(null);
              onShowToast(minutes > 0 ? strings.banUserAction : strings.unbanUserAction);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}

      {showComposeSupport ? (
        <ComposeSupportModal
          strings={strings}
          title={userRole === 'ADMIN' ? strings.contactSuperAdminAction : strings.contactAdminAction}
          onClose={() => setShowComposeSupport(false)}
          onSubmit={async ({ subject, body, imageFile }) => {
            try {
              await sendSupportMessage(db, auth, { userRole, subject, body, imageFile });
              setShowComposeSupport(false);
              onShowToast(strings.sendAction);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}

      {replyingTo ? (
        <ReplySupportModal
          strings={strings}
          message={replyingTo}
          onClose={() => setReplyingTo(null)}
          onSubmit={async ({ replyText, imageFile }) => {
            try {
              await replyToSupportMessage(db, auth, { message: replyingTo, replyText, imageFile });
              setReplyingTo(null);
              onShowToast(strings.sendAction);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}

      {showConnectionModal && userRole === 'SUPER_ADMIN' ? (
        <SuperAdminConnectionModal
          strings={strings}
          language={language}
          onClose={() => setShowConnectionModal(false)}
          onSaved={async (nextClients) => {
            onClientsUpdated?.(nextClients);
            setShowConnectionModal(false);
            onShowToast(language === 'ARABIC' ? 'تم حفظ إعدادات الربط' : 'Connection settings saved');
          }}
        />
      ) : null}

      {zoomImageUrl ? (
        <div className="modal-backdrop" onClick={() => setZoomImageUrl(null)}>
          <div className="modal-panel" style={{ maxWidth: '820px' }} onClick={(e) => e.stopPropagation()}>
            <div className="row-between">
              <span style={{ fontWeight: 700 }}>{strings.supportSectionTitle}</span>
              <button type="button" className="icon-btn" onClick={() => setZoomImageUrl(null)}>
                <X size={18} />
              </button>
            </div>
            <img
              src={zoomImageUrl}
              alt="Support attachment"
              style={{ width: '100%', maxHeight: '72vh', objectFit: 'contain', borderRadius: '10px' }}
            />
          </div>
        </div>
      ) : null}
    </div>
  );
}

function SupportInboxContent({ strings, userRole, currentUserId, messages, onReply, onZoomImage }) {
  if (userRole === 'STUDENT') {
    return (
      <>
        <div style={{ fontWeight: 700, fontSize: '0.95rem' }}>{strings.mySentMessagesTitle}</div>
        {messages.map((m) => (
          <SupportTicketItem key={m.id} strings={strings} msg={m} canReply={false} onReply={() => {}} onZoomImage={onZoomImage} />
        ))}
      </>
    );
  }

  if (userRole === 'ADMIN') {
    const adminToSuper = messages.filter((m) => m.channel === 'ADMIN_TO_SUPER' && m.senderUid === currentUserId);
    const studentInquiries = messages.filter((m) => m.channel === 'STUDENT_TO_ADMIN');
    return (
      <>
        {adminToSuper.length > 0 ? (
          <>
            <div style={{ fontWeight: 700, fontSize: '0.95rem' }}>{strings.adminToSuperInboxTitle}</div>
            {adminToSuper.map((m) => (
              <SupportTicketItem key={m.id} strings={strings} msg={m} canReply={false} onReply={() => {}} onZoomImage={onZoomImage} />
            ))}
          </>
        ) : null}
        {studentInquiries.length > 0 ? (
          <>
            <div style={{ fontWeight: 700, fontSize: '0.95rem' }}>{strings.studentInquiriesTitle}</div>
            {studentInquiries.map((m) => (
              <SupportTicketItem key={m.id} strings={strings} msg={m} canReply onReply={() => onReply(m)} onZoomImage={onZoomImage} />
            ))}
          </>
        ) : null}
      </>
    );
  }

  const escalations = messages.filter((m) => m.channel === 'ADMIN_TO_SUPER');
  const studentMsgs = messages.filter((m) => m.channel === 'STUDENT_TO_ADMIN');
  return (
    <>
      {escalations.length > 0 ? (
        <>
          <div style={{ fontWeight: 700, fontSize: '0.95rem' }}>{strings.adminToSuperInboxTitle}</div>
          {escalations.map((m) => (
            <SupportTicketItem key={m.id} strings={strings} msg={m} canReply onReply={() => onReply(m)} onZoomImage={onZoomImage} />
          ))}
        </>
      ) : null}
      {studentMsgs.length > 0 ? (
        <>
          <div style={{ fontWeight: 700, fontSize: '0.95rem' }}>{strings.studentInquiriesTitle}</div>
          {studentMsgs.map((m) => (
            <SupportTicketItem key={m.id} strings={strings} msg={m} canReply onReply={() => onReply(m)} onZoomImage={onZoomImage} />
          ))}
        </>
      ) : null}
    </>
  );
}

function SupportTicketItem({ strings, msg, canReply, onReply, onZoomImage }) {
  const isReplied = msg.status === 'REPLIED' && Boolean(msg.replyText);
  const msgImgUrl = msg.attachmentBase64
    ? `data:${msg.attachmentMimeType || 'image/jpeg'};base64,${msg.attachmentBase64}`
    : null;
  const replyImgUrl = msg.replyAttachmentBase64
    ? `data:${msg.replyAttachmentMimeType || 'image/jpeg'};base64,${msg.replyAttachmentBase64}`
    : null;

  return (
    <div className="m3-subsurface">
      <div className="row-between">
        <span style={{ fontWeight: 700 }}>{msg.subject}</span>
        <span className={`pill ${isReplied ? 'pill-secondary' : 'pill-tertiary'}`}>
          {isReplied ? strings.statusReplied : strings.statusOpen}
        </span>
      </div>
      <div style={{ fontSize: '0.8rem', color: 'var(--primary)', fontWeight: 600 }}>
        {msg.senderName} ({msg.senderRole})
      </div>
      <div style={{ fontSize: '0.92rem' }}>{msg.body}</div>
      {msgImgUrl ? (
        <img
          src={msgImgUrl}
          alt={msg.attachmentName || 'Attachment'}
          onClick={() => onZoomImage(msgImgUrl)}
          style={{
            maxWidth: '240px',
            maxHeight: '160px',
            borderRadius: '10px',
            objectFit: 'cover',
            cursor: 'pointer'
          }}
        />
      ) : null}
      {isReplied ? (
        <div
          style={{
            background: 'color-mix(in srgb, var(--primary-container) 45%, transparent)',
            borderRadius: '10px',
            padding: '10px',
            display: 'flex',
            flexDirection: 'column',
            gap: '6px'
          }}
        >
          <div style={{ fontWeight: 700, color: 'var(--primary)', fontSize: '0.8rem' }}>
            {msg.repliedByName || strings.roleAdmin}
          </div>
          <div style={{ fontSize: '0.88rem' }}>{msg.replyText}</div>
          {replyImgUrl ? (
            <img
              src={replyImgUrl}
              alt={msg.replyAttachmentName || 'Reply attachment'}
              onClick={() => onZoomImage(replyImgUrl)}
              style={{
                maxWidth: '220px',
                maxHeight: '150px',
                borderRadius: '10px',
                objectFit: 'cover',
                cursor: 'pointer'
              }}
            />
          ) : null}
        </div>
      ) : null}
      {canReply ? (
        <div>
          <button type="button" className="btn btn-outlined" onClick={onReply}>
            <Reply size={15} />
            <span>{strings.replyAction}</span>
          </button>
        </div>
      ) : null}
    </div>
  );
}

function TimedBanModal({ strings, language, user, onClose, onConfirm }) {
  const presets =
    language === 'ENGLISH'
      ? [
          { mins: 60, label: '1 Hour' },
          { mins: 360, label: '6 Hours' },
          { mins: 1440, label: '24 Hours' },
          { mins: 4320, label: '3 Days' },
          { mins: 10080, label: '7 Days' },
          { mins: 43200, label: '30 Days' },
          { mins: 0, label: 'Unban Now' }
        ]
      : [
          { mins: 60, label: 'ساعة واحدة' },
          { mins: 360, label: '6 ساعات' },
          { mins: 1440, label: '24 ساعة' },
          { mins: 4320, label: '3 أيام' },
          { mins: 10080, label: '7 أيام' },
          { mins: 43200, label: '30 يوماً' },
          { mins: 0, label: 'فك الحظر الآن' }
        ];
  const [durationMins, setDurationMins] = useState(
    Number(user.banUntilMillis || 0) > Date.now() ? 0 : 1440
  );
  const [reason, setReason] = useState(user.banReason || '');

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form
        className="modal-panel"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          onConfirm(durationMins, reason);
        }}
      >
        <h3 style={{ margin: 0 }}>
          {strings.banModalTitle}: {user.displayName}
        </h3>
        <span className="field-label">{strings.banDurationLabel}</span>
        <div className="chip-row" style={{ flexWrap: 'wrap' }}>
          {presets.map((p) => (
            <button
              key={p.mins}
              type="button"
              className={`m3-chip ${durationMins === p.mins ? 'selected' : ''}`}
              onClick={() => setDurationMins(p.mins)}
            >
              {p.label}
            </button>
          ))}
        </div>
        {durationMins > 0 ? (
          <div className="field-group">
            <label className="field-label">{strings.banReasonLabel}</label>
            <input
              className="m3-input"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={language === 'ENGLISH' ? 'Optional ban reason...' : 'اكتب سبب الحظر (اختياري)...'}
            />
          </div>
        ) : null}
        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>
            {strings.cancelAction}
          </button>
          <button type="submit" className="btn btn-primary">
            {durationMins > 0 ? strings.banUserAction : strings.unbanUserAction}
          </button>
        </div>
      </form>
    </div>
  );
}

function SuperAdminConnectionModal({ strings, language, onClose, onSaved }) {
  const initialCfg = getFirebaseRuntimeConfig();
  const [apiKeyInput, setApiKeyInput] = useState(initialCfg.apiKey);
  const [appIdInput, setAppIdInput] = useState(initialCfg.appId);
  const [projectIdInput, setProjectIdInput] = useState(initialCfg.projectId);
  const [dbIdInput, setDbIdInput] = useState(initialCfg.firestoreDatabaseId);
  const [appCheckTokenInput, setAppCheckTokenInput] = useState(initialCfg.appCheckDebugToken || '');
  const [webRecaptchaInput, setWebRecaptchaInput] = useState(initialCfg.webRecaptchaKey || '');

  const handleSave = async (e) => {
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
    await onSaved(nextClients);
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form className="modal-panel" onClick={(e) => e.stopPropagation()} onSubmit={handleSave}>
        <h3 style={{ margin: 0 }}>{strings.firebaseSetupTitle}</h3>
        <div className="field-group">
          <label className="field-label">Firebase Web API Key</label>
          <input className="m3-input" value={apiKeyInput} onChange={(e) => setApiKeyInput(e.target.value)} required />
        </div>
        <div className="field-group">
          <label className="field-label">Firebase Web App ID (AI Tutor)</label>
          <input className="m3-input" value={appIdInput} onChange={(e) => setAppIdInput(e.target.value)} />
        </div>
        <div className="field-group">
          <label className="field-label">Project ID</label>
          <input className="m3-input" value={projectIdInput} onChange={(e) => setProjectIdInput(e.target.value)} />
        </div>
        <div className="field-group">
          <label className="field-label">Firestore Database ID</label>
          <input className="m3-input" value={dbIdInput} onChange={(e) => setDbIdInput(e.target.value)} />
        </div>
        <div className="field-group">
          <label className="field-label">App Check Debug Token ({language === 'ARABIC' ? 'اختياري' : 'Optional'})</label>
          <input className="m3-input" value={appCheckTokenInput} onChange={(e) => setAppCheckTokenInput(e.target.value)} />
        </div>
        <div className="field-group">
          <label className="field-label">Web reCAPTCHA v3 Site Key ({language === 'ARABIC' ? 'اختياري' : 'Optional'})</label>
          <input className="m3-input" value={webRecaptchaInput} onChange={(e) => setWebRecaptchaInput(e.target.value)} />
        </div>
        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>
            {strings.cancelAction}
          </button>
          <button type="submit" className="btn btn-primary">
            {strings.savePolicyButton}
          </button>
        </div>
      </form>
    </div>
  );
}

function InviteModal({ strings, isSuperAdmin, onClose, onSubmit }) {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [role, setRole] = useState('STUDENT');

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form
        className="modal-panel"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          onSubmit({ rawEmail: email, rawNameHint: name, assignedRole: role });
        }}
      >
        <h3 style={{ margin: 0 }}>{strings.addAccountAction}</h3>
        <div className="field-group">
          <label className="field-label">{strings.inviteNameLabel}</label>
          <input className="m3-input" value={name} onChange={(e) => setName(e.target.value)} required />
        </div>
        <div className="field-group">
          <label className="field-label">{strings.inviteEmailLabel}</label>
          <input
            className="m3-input"
            type="email"
            placeholder="student@gmail.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </div>
        <span className="field-label">{strings.selectRoleLabel}</span>
        <div className="row-gap">
          <button
            type="button"
            className={`m3-chip ${role === 'STUDENT' ? 'selected' : ''}`}
            onClick={() => setRole('STUDENT')}
          >
            {strings.roleStudent}
          </button>
          {isSuperAdmin ? (
            <button
              type="button"
              className={`m3-chip ${role === 'ADMIN' ? 'selected' : ''}`}
              onClick={() => setRole('ADMIN')}
            >
              {strings.roleAdmin}
            </button>
          ) : null}
        </div>
        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>
            {strings.cancelAction}
          </button>
          <button type="submit" className="btn btn-primary" disabled={!email.trim() || !name.trim()}>
            {strings.addAccountAction}
          </button>
        </div>
      </form>
    </div>
  );
}

function ComposeSupportModal({ strings, title, onClose, onSubmit }) {
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [imageFile, setImageFile] = useState(null);
  const [sending, setSending] = useState(false);

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
        onClick={(e) => e.stopPropagation()}
        onSubmit={async (e) => {
          e.preventDefault();
          setSending(true);
          await onSubmit({ subject, body, imageFile });
          setSending(false);
        }}
      >
        <h3 style={{ margin: 0 }}>{title}</h3>
        <div className="field-group">
          <label className="field-label">{strings.supportSubjectLabel}</label>
          <input className="m3-input" value={subject} onChange={(e) => setSubject(e.target.value)} required />
        </div>
        <div className="field-group">
          <label className="field-label">{strings.supportBodyLabel}</label>
          <textarea className="m3-textarea" rows={4} value={body} onChange={(e) => setBody(e.target.value)} required />
        </div>
        <div className="row-gap" style={{ flexWrap: 'wrap' }}>
          <button type="button" className="btn btn-outlined" onClick={pickImage}>
            <ImageIcon size={15} />
            <span>{strings.attachPhotoAction}</span>
          </button>
          {imageFile ? (
            <div className="row-gap pill pill-secondary" style={{ padding: '6px 10px' }}>
              <span>{imageFile.name}</span>
              <button
                type="button"
                className="icon-btn"
                style={{ width: '22px', height: '22px' }}
                onClick={() => setImageFile(null)}
              >
                <X size={13} />
              </button>
            </div>
          ) : null}
        </div>
        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>
            {strings.cancelAction}
          </button>
          <button type="submit" className="btn btn-primary" disabled={sending || !subject.trim() || !body.trim()}>
            {sending ? <span className="spinner" /> : strings.sendAction}
          </button>
        </div>
      </form>
    </div>
  );
}

function ReplySupportModal({ strings, message, onClose, onSubmit }) {
  const [reply, setReply] = useState(message.replyText || '');
  const [imageFile, setImageFile] = useState(null);
  const [sending, setSending] = useState(false);

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
        onClick={(e) => e.stopPropagation()}
        onSubmit={async (e) => {
          e.preventDefault();
          setSending(true);
          await onSubmit({ replyText: reply, imageFile });
          setSending(false);
        }}
      >
        <h3 style={{ margin: 0 }}>
          {strings.replyAction}: {message.subject}
        </h3>
        <div className="muted">{message.body}</div>
        <div className="field-group">
          <label className="field-label">{strings.replyHint}</label>
          <textarea className="m3-textarea" rows={4} value={reply} onChange={(e) => setReply(e.target.value)} required />
        </div>
        <div className="row-gap" style={{ flexWrap: 'wrap' }}>
          <button type="button" className="btn btn-outlined" onClick={pickImage}>
            <ImageIcon size={15} />
            <span>{strings.attachPhotoAction}</span>
          </button>
          {imageFile ? (
            <div className="row-gap pill pill-secondary" style={{ padding: '6px 10px' }}>
              <span>{imageFile.name}</span>
              <button
                type="button"
                className="icon-btn"
                style={{ width: '22px', height: '22px' }}
                onClick={() => setImageFile(null)}
              >
                <X size={13} />
              </button>
            </div>
          ) : null}
        </div>
        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>
            {strings.cancelAction}
          </button>
          <button type="submit" className="btn btn-primary" disabled={sending || !reply.trim()}>
            {sending ? <span className="spinner" /> : strings.sendAction}
          </button>
        </div>
      </form>
    </div>
  );
}
