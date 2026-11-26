import React, { useState } from 'react';
import { ExternalLink, FileText, Image as ImageIcon, Paperclip, ShieldCheck, Trash2 } from 'lucide-react';
import { deleteVerifiedVaultFile, recordVerifiedVaultFile } from '../data/firestoreService';
import { loadVaultBlob, removeVaultBlob, saveVaultBlob, triggerBrowserFileOpen } from '../data/vaultStorage';

export default function FilesVaultView({ strings, db, auth, files, onShowToast }) {
  const [busy, setBusy] = useState(false);

  const handlePick = (accept) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.onchange = async () => {
      const f = input.files?.[0];
      if (!f) return;
      setBusy(true);
      try {
        const res = await recordVerifiedVaultFile(db, auth, f);
        await saveVaultBlob(res.validated.sha256Hex, {
          bytes: res.validated.bytes,
          mimeType: res.validated.mimeType,
          fileName: res.validated.originalName
        });
        onShowToast(`${res.validated.originalName} ✓`);
      } catch (err) {
        onShowToast(err.message);
      } finally {
        setBusy(false);
      }
    };
    input.click();
  };

  const handleOpenSaved = async (fileRecord) => {
    const cached = await loadVaultBlob(fileRecord.sha256Hex);
    if (!cached?.bytes) {
      onShowToast(
        strings.language === 'ARABIC'
          ? 'هذا الملف محفوظ على الجهاز الذي رُفع منه.'
          : 'File content is stored locally on the device where it was verified.'
      );
      return;
    }
    triggerBrowserFileOpen(cached.bytes, cached.mimeType || fileRecord.mimeType, fileRecord.originalName);
  };

  const handleDelete = async (fileRecord) => {
    try {
      await deleteVerifiedVaultFile(db, auth, fileRecord.id);
      if (fileRecord.sha256Hex) {
        await removeVaultBlob(fileRecord.sha256Hex);
      }
      onShowToast(`${strings.deleteLabel}: ${fileRecord.originalName}`);
    } catch (err) {
      onShowToast(err.message);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
      <div className="m3-card accent-secondary">
        <div style={{ fontWeight: 800, fontSize: '1.1rem' }}>{strings.filesVaultTitle}</div>
        <div className="muted">{strings.filesVaultSub}</div>
        <div className="row-gap" style={{ flexWrap: 'wrap' }}>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy}
            onClick={() => handlePick('application/pdf')}
          >
            <FileText size={17} />
            <span>{strings.verifyPdf}</span>
          </button>
          <button
            type="button"
            className="btn btn-outlined"
            disabled={busy}
            onClick={() => handlePick('image/png,image/jpeg')}
          >
            <ImageIcon size={17} />
            <span>{strings.verifyImage}</span>
          </button>
        </div>
      </div>

      {files.length === 0 ? (
        <div className="m3-card" style={{ alignItems: 'center', textAlign: 'center', padding: '28px' }}>
          <Paperclip size={38} style={{ color: 'var(--primary)' }} />
          <div style={{ fontWeight: 700 }}>{strings.noFilesTitle}</div>
          <div className="muted">{strings.noFilesSub}</div>
        </div>
      ) : (
        files.map((file) => (
          <div key={file.id} className="m3-card">
            <div className="row-between">
              <div className="row-gap" style={{ minWidth: 0, flex: 1 }}>
                <ShieldCheck size={20} style={{ color: 'var(--secondary)', flexShrink: 0 }} />
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontWeight: 700, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {file.originalName}
                  </div>
                  <div className="muted" style={{ fontSize: '0.82rem' }}>
                    {file.mimeType} • {Math.max(1, Math.floor((file.sizeBytes || 0) / 1024))} KB
                  </div>
                </div>
              </div>
              <div className="row-gap">
                <button type="button" className="btn btn-tonal" onClick={() => handleOpenSaved(file)}>
                  <ExternalLink size={15} />
                  <span>{strings.openVaultFile}</span>
                </button>
                <button
                  type="button"
                  className="icon-btn"
                  style={{ color: 'var(--error)' }}
                  onClick={() => handleDelete(file)}
                  title={strings.deleteLabel}
                >
                  <Trash2 size={16} />
                </button>
              </div>
            </div>
          </div>
        ))
      )}
    </div>
  );
}
