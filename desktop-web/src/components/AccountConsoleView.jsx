import React, { useEffect, useMemo, useState } from 'react';
import {
  Ban,
  BellRing,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Globe,
  Headset,
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
  Users
} from 'lucide-react';
import {
  demoteAdminToStudent,
  inviteAccountByEmail,
  promoteUserToAdmin,
  replyToSupportMessage,
  revokeInvite,
  sendSupportMessage,
  setUserAccountDisabled,
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
  onSignOut,
  onShowToast
}) {
  const [showAuditLogs, setShowAuditLogs] = useState(false);
  const [showInviteModal, setShowInviteModal] = useState(false);
  const [showComposeSupport, setShowComposeSupport] = useState(false);
  const [replyingTo, setReplyingTo] = useState(null);
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
                style={disabled ? { background: 'color-mix(in srgb, var(--error-container) 28%, transparent)' } : undefined}
              >
                <div className="row-between">
                  <div>
                    <div style={{ fontWeight: 700 }}>{u.displayName}</div>
                    <div style={{ color: 'var(--primary)', fontSize: '0.82rem', fontWeight: 600 }}>
                      {rLabel} • AI {u.aiCallsToday || 0} • Sync {u.writesToday || 0}
                    </div>
                  </div>
                  <span className={`pill ${disabled ? 'pill-error' : 'pill-secondary'}`}>
                    {disabled ? strings.accountStatusDisabled : strings.accountStatusActive}
                  </span>
                </div>

                {userRole === 'SUPER_ADMIN' && u.userId !== currentUserId && u.role !== 'SUPER_ADMIN' ? (
                  <div className="chip-row">
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
                  </div>
                ) : null}
              </div>
            );
          })}
        </div>
      ) : null}

      {/* Super Admin Policy Controls */}
      {userRole === 'SUPER_ADMIN' ? (
        <div className="m3-card">
          <div className="row-gap">
            <Settings size={20} style={{ color: 'var(--tertiary)' }} />
            <h3 style={{ margin: 0 }}>{strings.governanceTitle}</h3>
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

      {showComposeSupport ? (
        <ComposeSupportModal
          strings={strings}
          title={userRole === 'ADMIN' ? strings.contactSuperAdminAction : strings.contactAdminAction}
          onClose={() => setShowComposeSupport(false)}
          onSubmit={async ({ subject, body }) => {
            try {
              await sendSupportMessage(db, auth, { userRole, subject, body });
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
          onSubmit={async (replyText) => {
            try {
              await replyToSupportMessage(db, auth, { message: replyingTo, replyText });
              setReplyingTo(null);
              onShowToast(strings.sendAction);
            } catch (err) {
              onShowToast(err.message);
            }
          }}
        />
      ) : null}
    </div>
  );
}

function SupportInboxContent({ strings, userRole, currentUserId, messages, onReply }) {
  if (userRole === 'STUDENT') {
    return (
      <>
        <div style={{ fontWeight: 700, fontSize: '0.95rem' }}>{strings.mySentMessagesTitle}</div>
        {messages.map((m) => (
          <SupportTicketItem key={m.id} strings={strings} msg={m} canReply={false} onReply={() => {}} />
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
              <SupportTicketItem key={m.id} strings={strings} msg={m} canReply={false} onReply={() => {}} />
            ))}
          </>
        ) : null}
        {studentInquiries.length > 0 ? (
          <>
            <div style={{ fontWeight: 700, fontSize: '0.95rem' }}>{strings.studentInquiriesTitle}</div>
            {studentInquiries.map((m) => (
              <SupportTicketItem key={m.id} strings={strings} msg={m} canReply onReply={() => onReply(m)} />
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
            <SupportTicketItem key={m.id} strings={strings} msg={m} canReply onReply={() => onReply(m)} />
          ))}
        </>
      ) : null}
      {studentMsgs.length > 0 ? (
        <>
          <div style={{ fontWeight: 700, fontSize: '0.95rem' }}>{strings.studentInquiriesTitle}</div>
          {studentMsgs.map((m) => (
            <SupportTicketItem key={m.id} strings={strings} msg={m} canReply onReply={() => onReply(m)} />
          ))}
        </>
      ) : null}
    </>
  );
}

function SupportTicketItem({ strings, msg, canReply, onReply }) {
  const isReplied = msg.status === 'REPLIED' && Boolean(msg.replyText);
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
      {isReplied ? (
        <div
          style={{
            background: 'color-mix(in srgb, var(--primary-container) 45%, transparent)',
            borderRadius: '10px',
            padding: '10px'
          }}
        >
          <div style={{ fontWeight: 700, color: 'var(--primary)', fontSize: '0.8rem' }}>
            {msg.repliedByName || strings.roleAdmin}
          </div>
          <div style={{ fontSize: '0.88rem' }}>{msg.replyText}</div>
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
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form
        className="modal-panel"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          onSubmit({ subject, body });
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
        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>
            {strings.cancelAction}
          </button>
          <button type="submit" className="btn btn-primary" disabled={!subject.trim() || !body.trim()}>
            {strings.sendAction}
          </button>
        </div>
      </form>
    </div>
  );
}

function ReplySupportModal({ strings, message, onClose, onSubmit }) {
  const [reply, setReply] = useState(message.replyText || '');
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form
        className="modal-panel"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          onSubmit(reply);
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
        <div className="row-between" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-text" onClick={onClose}>
            {strings.cancelAction}
          </button>
          <button type="submit" className="btn btn-primary" disabled={!reply.trim()}>
            {strings.sendAction}
          </button>
        </div>
      </form>
    </div>
  );
}
