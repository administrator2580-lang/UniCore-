export const MAX_INLINE_COURSE_FILE_BYTES = 2_097_152; // 2 MB Spark-plan limit
export const MAX_VAULT_FILE_BYTES = 5_242_880; // 5 MB
export const CHUNK_RAW_BYTES = 512 * 1024; // 512 KB raw -> ~683 KB Base64 (< 750 KB rule)

export function sanitizeText(raw) {
  return String(raw || '')
    .replace(/[\u0000-\u001F\u007F]/g, ' ')
    .replace(/[<>]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

export function validateBoundedText(raw, label, minLen, maxLen) {
  const cleaned = sanitizeText(raw);
  if (cleaned.length < minLen || cleaned.length > maxLen) {
    return { valid: false, reason: `${label} must be between ${minLen} and ${maxLen} characters.` };
  }
  return { valid: true, value: cleaned };
}

export function isValidId(id) {
  return typeof id === 'string' && id.length >= 1 && id.length <= 128 && /^[a-zA-Z0-9_-]+$/.test(id);
}

export function validateOptionalHttpsUrl(rawUrl) {
  const trimmed = String(rawUrl || '').trim();
  if (!trimmed) return { valid: true, value: null };
  if (trimmed.length < 10 || trimmed.length > 500) {
    return { valid: false, reason: 'URL length must be between 10 and 500 characters.' };
  }
  if (!/^https:\/\/[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}(\/.*)?$/.test(trimmed)) {
    return { valid: false, reason: 'Only HTTPS links are allowed.' };
  }
  return { valid: true, value: trimmed };
}

export function extractGoogleDriveFolder(rawUrl) {
  const cleaned = String(rawUrl || '').trim();
  if (!cleaned) {
    return { valid: false, reason: 'Enter a Google Drive folder link.' };
  }
  let match = cleaned.match(/drive\.google\.com\/drive\/(?:u\/\d+\/)?folders\/([a-zA-Z0-9_-]{10,128})/);
  if (!match) {
    match = cleaned.match(/drive\.google\.com\/open\?[^#]*id=([a-zA-Z0-9_-]{10,128})/);
  }
  if (!match) {
    match = cleaned.match(/drive\.google\.com\/folderview\?[^#]*id=([a-zA-Z0-9_-]{10,128})/);
  }
  if (!match) {
    return { valid: false, reason: 'Invalid Google Drive folder link (expected .../drive/folders/...).' };
  }
  const folderId = match[1];
  return {
    valid: true,
    folderId,
    canonicalUrl: `https://drive.google.com/drive/folders/${folderId}`
  };
}

export async function sha256Hex(uint8Array) {
  const digest = await crypto.subtle.digest('SHA-256', uint8Array);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

export function detectMagicMimeType(bytes, fallbackName = '') {
  if (!bytes || bytes.length < 4) return null;
  // PDF: %PDF-
  if (bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 && bytes[3] === 0x46) {
    return { mimeType: 'application/pdf', ext: 'pdf' };
  }
  // PNG: 89 50 4E 47 0D 0A 1A 0A
  if (
    bytes.length >= 8 &&
    bytes[0] === 0x89 &&
    bytes[1] === 0x50 &&
    bytes[2] === 0x4e &&
    bytes[3] === 0x47
  ) {
    return { mimeType: 'image/png', ext: 'png' };
  }
  // JPEG: FF D8 FF
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    const ext = fallbackName.toLowerCase().endsWith('.jpeg') ? 'jpeg' : 'jpg';
    return { mimeType: 'image/jpeg', ext };
  }
  return null;
}

export function uint8ToBase64(bytes) {
  let binary = '';
  const step = 0x8000;
  for (let i = 0; i < bytes.length; i += step) {
    const slice = bytes.subarray(i, Math.min(i + step, bytes.length));
    binary += String.fromCharCode(...slice);
  }
  return btoa(binary);
}

export function base64ToUint8(base64) {
  const cleaned = String(base64 || '').replace(/\s+/g, '');
  const binary = atob(cleaned);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

export async function validateSelectedUploadFile(file, maxBytes = MAX_VAULT_FILE_BYTES) {
  if (!file) throw new Error('No file selected.');
  if (file.size <= 0) throw new Error('Selected file is empty.');
  if (file.size > maxBytes) {
    const mb = (maxBytes / (1024 * 1024)).toFixed(0);
    throw new Error(`File exceeds ${mb} MB limit.`);
  }
  const buffer = await file.arrayBuffer();
  const bytes = new Uint8Array(buffer);
  const detected = detectMagicMimeType(bytes, file.name);
  if (!detected) {
    throw new Error('Only verified PDF, PNG, or JPEG files are allowed.');
  }
  const hash = await sha256Hex(bytes);
  const safeOrig = sanitizeText(file.name).slice(0, 120) || `file.${detected.ext}`;
  const randomTag = Math.random().toString(36).slice(2, 10);
  const storedName = `uc_${Date.now()}_${randomTag}.${detected.ext}`;
  return {
    originalName: safeOrig,
    storedName,
    mimeType: detected.mimeType,
    ext: detected.ext,
    sizeBytes: bytes.length,
    sha256Hex: hash,
    bytes
  };
}
