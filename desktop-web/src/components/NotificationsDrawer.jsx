import React, { useState } from 'react';
import {
  Bell,
  CheckCircle2,
  ClipboardCheck,
  Megaphone,
  School,
  Send,
  Shield,
  ShieldAlert,
  X
} from 'lucide-react';
import { sendNotification } from '../data/firestoreService';

export default function NotificationsDrawer({
  strings,
  db,
  auth,
  userRole,
  notifications,
  readIds,
  onMarkRead,
  onMarkAllRead,
  onClose,
  onShowToast
}) {
  const [showComposer, setShowComposer] = useState(false);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');

  const hasUnread = notifications.some((n) => !readIds.includes(n.id));

  const categoryIcon = (cat) => {
    switch (cat) {
      case 'COURSE':
        return <School size={18} />;
      case 'QUIZ':
        return <ClipboardCheck size={18} />;
      case 'GRADE':
        return <CheckCircle2 size={18} />;
      case 'ROLE':
        return <Shield size={18} />;
      case 'SECURITY':
        return <ShieldAlert size={18} />;
      default:
        return <Megaphone size={18} />;
    }
  };

  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <div className="drawer-panel" onClick={(e) => e.stopPropagation()}>
        <div className="row-between">
          <div className="row-gap">
            <Bell size={20} style={{ color: 'var(--primary)' }} />
            <h3 style={{ margin: 0 }}>{strings.notificationsTitle}</h3>
          </div>
          <div className="row-gap">
            {hasUnread ? (
              <button
                type="button"
                className="btn btn-text"
                onClick={() => onMarkAllRead(notifications.map((n) => n.id))}
              >
                {strings.markAllRead}
              </button>
            ) : null}
            <button type="button" className="icon-btn" onClick={onClose}>
              <X size={18} />
            </button>
          </div>
        </div>

        {userRole !== 'STUDENT' ? (
          <>
            <button
              type="button"
              className="btn btn-outlined"
              onClick={() => setShowComposer(!showComposer)}
            >
              <Megaphone size={16} />
              <span>{strings.sendNotificationTitle}</span>
            </button>
            {showComposer ? (
              <form
                className="m3-subsurface"
                onSubmit={async (e) => {
                  e.preventDefault();
                  try {
                    await sendNotification(db, auth, { title, body, category: 'ANNOUNCEMENT', targetUid: 'ALL' });
                    setTitle('');
                    setBody('');
                    setShowComposer(false);
                    onShowToast(strings.sendAction);
                  } catch (err) {
                    onShowToast(err.message);
                  }
                }}
              >
                <input
                  className="m3-input"
                  placeholder={strings.notificationTitleLabel}
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  required
                />
                <textarea
                  className="m3-textarea"
                  rows={2}
                  placeholder={strings.notificationBodyLabel}
                  value={body}
                  onChange={(e) => setBody(e.target.value)}
                  required
                />
                <button type="submit" className="btn btn-primary">
                  <Send size={15} />
                  <span>
                    {strings.sendAction} ({strings.sendToAllLabel})
                  </span>
                </button>
              </form>
            ) : null}
          </>
        ) : null}

        {notifications.length === 0 ? (
          <div className="m3-card" style={{ alignItems: 'center', textAlign: 'center', padding: '26px' }}>
            <Bell size={34} style={{ color: 'var(--primary)' }} />
            <div style={{ fontWeight: 700 }}>{strings.noNotificationsTitle}</div>
            <div className="muted">{strings.noNotificationsSub}</div>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
            {notifications.map((n) => {
              const unread = !readIds.includes(n.id);
              return (
                <div
                  key={n.id}
                  className="m3-card"
                  style={{
                    cursor: 'pointer',
                    background: unread ? 'color-mix(in srgb, var(--primary-container) 34%, var(--surface))' : undefined,
                    borderColor: unread ? 'color-mix(in srgb, var(--primary) 45%, transparent)' : undefined
                  }}
                  onClick={() => onMarkRead(n.id)}
                >
                  <div className="row-gap" style={{ alignItems: 'flex-start' }}>
                    <div className="pill pill-primary" style={{ padding: '8px', borderRadius: '10px' }}>
                      {categoryIcon(n.category)}
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div className="row-between">
                        <span style={{ fontWeight: unread ? 800 : 600 }}>{n.title}</span>
                        {unread ? (
                          <span
                            style={{
                              width: '8px',
                              height: '8px',
                              borderRadius: '50%',
                              background: 'var(--primary)',
                              flexShrink: 0
                            }}
                          />
                        ) : null}
                      </div>
                      <div className="muted" style={{ marginTop: '4px' }}>{n.body}</div>
                      <div style={{ color: 'var(--primary)', fontSize: '0.78rem', fontWeight: 600, marginTop: '4px' }}>
                        {n.senderName}
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
