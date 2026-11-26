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

// --- Profanity & Abuse Guard (Arabic + Franco-Arab + English) ---

const PROFANITY_REGEX_LIST = [
  // English explicit slurs & profanity (word boundaries prevent benign substrings)
  /\b(f+u+c+k+(?:ing|er|ed|s)?|s+h+i+t+(?:ty|s)?|b+i+t+c+h+(?:es|ing)?|a+s+s+h+o+l+e+s?|b+a+s+t+a+r+d+s?|d+i+c+k+(?:head|s)?|p+u+s+s+y|c+u+n+t+s?|w+h+o+r+e+s?|s+l+u+t+s?|m+o+t+h+e+r+f+u+c+k+e*r*|n+i+g+g+(?:a|er)s?|f+a+g+g*o*t*s?)\b/i,
  // Franco-Arab common insults
  /\b(k+o+s+o+m+(?:a+k|o+k|h+o+m)?|a+h+b+a+|s+h+a+r+m+o+t+a*|m+e+t+n+a+k+|a+r+s+|m+a+3+r+a+s+|k+h+a+w+a+l+|z+e+b+y*|t+e+z+a+k+|y+a+b+n\s*e+l\s*(?:w+e+s+k+h+a+|a+h+b+a+|m+e+t+n+a+k+a+))\b/i,
  // Normalized Arabic roots/phrases
  /(?:^|\s|[،.؟!:\-_])(كس\s*امك|كسمك|كس\s*اختك|شرموط|شرموطه|قحب|قحبه|منيك|متناك|متناكه|ابن\s*الوسخه|ابن\s*الكلب|ابن\s*الحرام|يلعن|يلعن\s*دين|خول|معرص|عرص|زبي|زبك|طيز|طيزك|حيوان|حمار|وسخ|زباله|نجس|كسم|لعنه\s*الله)(?=$|\s|[،.؟!:\-_])/i
];

function normalizeForProfanityCheck(raw) {
  return String(raw || '')
    .toLowerCase()
    .replace(/[\u064B-\u065F\u0670\u0640]/g, '') // Arabic harakat + tatweel
    .replace(/[أإآ]/g, 'ا')
    .replace(/ى/g, 'ي')
    .replace(/ة/g, 'ه')
    .replace(/[@]/g, 'a')
    .replace(/[$]/g, 's')
    .replace(/!/g, 'i')
    .replace(/(.)\1{2,}/g, '$1$1')
    .replace(/\s+/g, ' ')
    .trim();
}

export function containsProfanity(raw) {
  const normalized = normalizeForProfanityCheck(raw);
  if (!normalized) return false;
  return PROFANITY_REGEX_LIST.some((re) => re.test(normalized));
}

export function assertCleanText(raw, errorMsg) {
  if (containsProfanity(raw)) {
    throw new Error(
      errorMsg ||
        'تم حظر الإرسال لاحتواء النص على ألفاظ غير لائقة. يرجى الالتزام بآداب الحوار الجامعي.'
    );
  }
}

// --- Client-side Image Compression for Inline Attachments (< 500 KB) ---

export async function compressImageFileToBase64(file, maxBase64Chars = 640_000, maxDim = 1280) {
  if (!file) throw new Error('No image selected.');
  const buffer = await file.arrayBuffer();
  const bytes = new Uint8Array(buffer);
  const detected = detectMagicMimeType(bytes, file.name);
  if (!detected || !detected.mimeType.startsWith('image/')) {
    throw new Error('Only verified PNG or JPEG images are allowed.');
  }

  const rawBase64 = uint8ToBase64(bytes);
  if (rawBase64.length <= maxBase64Chars) {
    return {
      base64: rawBase64,
      mimeType: detected.mimeType,
      fileName: sanitizeText(file.name).slice(0, 140) || `image.${detected.ext}`
    };
  }

  const blobUrl = URL.createObjectURL(new Blob([bytes], { type: detected.mimeType }));
  try {
    const img = await new Promise((resolve, reject) => {
      const el = new Image();
      el.onload = () => resolve(el);
      el.onerror = () => reject(new Error('Failed to read selected image.'));
      el.src = blobUrl;
    });

    const scale = Math.min(1, maxDim / Math.max(img.width || 1, img.height || 1));
    const width = Math.max(1, Math.round(img.width * scale));
    const height = Math.max(1, Math.round(img.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d');
    ctx.drawImage(img, 0, 0, width, height);

    for (const quality of [0.82, 0.68, 0.52, 0.38]) {
      const dataUrl = canvas.toDataURL('image/jpeg', quality);
      const b64 = dataUrl.split(',')[1] || '';
      if (b64.length <= maxBase64Chars) {
        const cleanName = (sanitizeText(file.name).replace(/\.[^.]+$/, '') || 'image') + '.jpg';
        return {
          base64: b64,
          mimeType: 'image/jpeg',
          fileName: cleanName.slice(0, 140)
        };
      }
    }
    throw new Error('Image is too large even after compression. Choose a smaller image.');
  } finally {
    URL.revokeObjectURL(blobUrl);
  }
}

// --- Clean AI Tutor Math & Markdown Output (removes raw LaTeX like $$50\ \Omega$$) ---

export function formatAiReadableText(raw) {
  let text = String(raw || '');
  if (!text) return '';

  // Remove math delimiters
  text = text
    .replace(/\$\$([\s\S]*?)\$\$/g, '$1')
    .replace(/\$([^$\n]+?)\$/g, '$1')
    .replace(/\\\[([\s\S]*?)\\\]/g, '$1')
    .replace(/\\\(([\s\S]*?)\\\)/g, '$1');

  // Common LaTeX commands -> clean Unicode
  for (let i = 0; i < 3; i++) {
    text = text
      .replace(/\\(?:d?frac)\{([^{}]+)\}\{([^{}]+)\}/g, '($1 ÷ $2)')
      .replace(/\\sqrt\{([^{}]+)\}/g, '√($1)')
      .replace(/\\(?:text|mathrm|mathbf|mathit|operatorname)\{([^{}]*)\}/g, '$1');
  }

  text = text
    .replace(/\\Omega\b/g, 'Ω')
    .replace(/\\mu\b/g, 'μ')
    .replace(/\\alpha\b/g, 'α')
    .replace(/\\beta\b/g, 'β')
    .replace(/\\gamma\b/g, 'γ')
    .replace(/\\theta\b/g, 'θ')
    .replace(/\\phi\b/g, 'φ')
    .replace(/\\pi\b/g, 'π')
    .replace(/\\Delta\b/g, 'Δ')
    .replace(/\\lambda\b/g, 'λ')
    .replace(/\\times\b/g, '×')
    .replace(/\\cdot\b/g, '·')
    .replace(/\\div\b/g, '÷')
    .replace(/\\pm\b/g, '±')
    .replace(/\\approx\b/g, '≈')
    .replace(/\\neq\b/g, '≠')
    .replace(/\\leq\b/g, '≤')
    .replace(/\\geq\b/g, '≥')
    .replace(/\\degree\b|\^\\circ/g, '°')
    .replace(/\\to\b|\\rightarrow\b/g, '←')
    .replace(/\^\{2\}|\^2\b/g, '²')
    .replace(/\^\{3\}|\^3\b/g, '³')
    .replace(/\\[,;:! ]/g, ' ')
    .replace(/\\left\b|\\right\b/g, '')
    .replace(/([A-Za-z0-9])_\{([^{}]+)\}/g, '$1($2)')
    .replace(/([A-Za-z])_([A-Za-z0-9])\b/g, '$1($2)')
    .replace(/\{([^{}\n]+)\}/g, '$1');

  // Clean Markdown clutter (### headings, **bold**, --- separators, * bullets)
  text = text
    .replace(/^[ \t]*#{1,6}[ \t]*(.+)$/gm, '$1')
    .replace(/\*\*([^*\n]+)\*\*/g, '$1')
    .replace(/__([^_\n]+)__/g, '$1')
    .replace(/`([^`\n]+)`/g, '$1')
    .replace(/^[ \t]*[-*_]{3,}[ \t]*$/gm, '')
    .replace(/^[ \t]*\*[ \t]+/gm, '• ')
    .replace(/[ \t]{2,}/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();

  return text;
}
