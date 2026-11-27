import { downloadCourseAttachmentBytes } from './firestoreService';
import { loadVaultBlob } from './vaultStorage';

export const SOURCE_AUTO = 'AUTO';
export const SOURCE_NONE = 'NONE';
export const MAX_INLINE_FILE_BYTES = 10 * 1024 * 1024;
export const MAX_TOTAL_INLINE_BYTES = 12 * 1024 * 1024;

const STOP_WORDS = new Set([
  'اشرح', 'اشرحلي', 'شرح', 'فهمني', 'افهم', 'لخص', 'لخصلي', 'ملخص', 'تلخيص',
  'عايز', 'اريد', 'محتاج', 'ممكن', 'لو', 'سمحت', 'هات', 'جيب', 'جيبلي', 'اعطني', 'اديني',
  'اكتب', 'قول', 'قولي', 'اتكلم', 'تكلم', 'من', 'في', 'عن', 'علي', 'على', 'الي', 'إلى', 'الى',
  'لي', 'لك', 'ده', 'دي', 'دا', 'هذا', 'هذه', 'تلك', 'ذلك', 'اللي', 'التي', 'الذي',
  'ماده', 'مادة', 'الماده', 'المادة', 'ملف', 'ملفات', 'الملف', 'المرفوع', 'المرفوعه', 'المرفوعة',
  'درس', 'محاضره', 'محاضرة', 'مذكره', 'مذكرة', 'فصل', 'باب', 'جزء', 'قسم',
  'ايه', 'ما', 'هو', 'هي', 'هم', 'كيف', 'لماذا', 'ليش', 'ازاي', 'فين', 'امته',
  'اسئله', 'اسئلة', 'تدريبيه', 'تدريبية', 'المفاهيم', 'الاساسيه', 'الأساسية', 'نقاط',
  'ببساطه', 'ببساطة', 'مثال', 'عملي', 'جدا', 'كل', 'حاجه', 'حاجة', 'الكل', 'بس', 'يا',
  'explain', 'summarize', 'summary', 'please', 'from', 'the', 'a', 'an', 'in', 'on', 'of',
  'to', 'for', 'with', 'about', 'what', 'is', 'are', 'how', 'why', 'give', 'me', 'tell',
  'file', 'files', 'uploaded', 'course', 'courses', 'material', 'materials', 'lecture',
  'chapter', 'pdf', 'notes', 'document', 'docs', 'simply', 'example', 'practice',
  'questions', 'key', 'concepts', 'bullet', 'points', 'this', 'that', 'everything'
]);

export function normalizeForSearch(raw) {
  if (!raw) return '';
  const noMarks = String(raw).replace(/[\u064B-\u065F\u0670\u06D6-\u06ED\u0640]/g, '');
  let out = '';
  for (const ch of noMarks) {
    const code = ch.codePointAt(0);
    if (ch === 'أ' || ch === 'إ' || ch === 'آ' || ch === 'ٱ') out += 'ا';
    else if (ch === 'ة') out += 'ه';
    else if (ch === 'ى') out += 'ي';
    else if (ch === 'ؤ') out += 'و';
    else if (ch === 'ئ') out += 'ي';
    else if (code >= 0x0660 && code <= 0x0669) out += String(code - 0x0660);
    else if (code >= 0x06F0 && code <= 0x06F9) out += String(code - 0x06F0);
    else out += ch.toLowerCase();
  }
  return out
    .replace(/[^\p{L}\p{Nd}\s]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function normalizeTokenVariants(token) {
  const t = token.replace(/ء+$/, '');
  if (t.length < 2) return [];
  const variants = new Set([t]);
  let stripped = t;
  if (t.startsWith('وال') && t.length > 4) stripped = t.slice(3);
  else if (t.startsWith('بال') && t.length > 4) stripped = t.slice(3);
  else if (t.startsWith('لال') && t.length > 4) stripped = t.slice(3);
  else if (t.startsWith('كال') && t.length > 4) stripped = t.slice(3);
  else if (t.startsWith('فال') && t.length > 4) stripped = t.slice(3);
  else if (t.startsWith('ال') && t.length > 3) stripped = t.slice(2);
  else if ((t.startsWith('و') || t.startsWith('ب') || t.startsWith('ل')) && t.length > 4) stripped = t.slice(1);
  stripped = stripped.replace(/ء+$/, '');

  if (stripped.length >= 2) {
    variants.add(stripped);
    if (stripped.endsWith('يه') && stripped.length > 4) variants.add(stripped.slice(0, -2));
    else if (stripped.endsWith('ي') && stripped.length > 4) variants.add(stripped.slice(0, -1));
  }
  return Array.from(variants);
}

export function extractMeaningfulTokens(prompt) {
  const norm = normalizeForSearch(prompt);
  if (!norm) return [];
  const out = new Set();
  for (const rawWord of norm.split(' ')) {
    const w = rawWord.replace(/ء+$/, '');
    if (w.length < 2 || STOP_WORDS.has(w)) continue;
    const withoutAl = w.startsWith('ال') ? w.slice(2) : w;
    if (withoutAl && STOP_WORDS.has(withoutAl)) continue;
    for (const variant of normalizeTokenVariants(w)) {
      if (variant.length >= 2 && !STOP_WORDS.has(variant)) {
        out.add(variant);
      }
    }
  }
  return Array.from(out);
}

export function buildCandidateList(courses = [], driveFiles = [], vaultFiles = []) {
  const list = [];
  for (const course of courses) {
    list.push({
      kind: 'COURSE',
      sourceId: `course_${course.id}`,
      displayTitle: course.title || '',
      fileName: course.attachmentName || course.title || '',
      contextSubtitle: [course.departmentName, course.academicYear, course.description].filter(Boolean).join(' • '),
      hasBinaryFile: Boolean(course.hasAttachment && course.chunkCount > 0 && course.attachmentSha256),
      raw: course
    });
  }
  for (const file of driveFiles) {
    if (file.isSubFolder) continue;
    list.push({
      kind: 'DRIVE',
      sourceId: `drive_${file.fileId}`,
      displayTitle: file.name || '',
      fileName: file.name || '',
      contextSubtitle: `Google Drive • ${file.folderTitle || ''}`,
      hasBinaryFile: true,
      raw: file
    });
  }
  for (const record of vaultFiles) {
    list.push({
      kind: 'VAULT',
      sourceId: `vault_${record.id}`,
      displayTitle: record.originalName || '',
      fileName: record.originalName || '',
      contextSubtitle: 'My Files Vault',
      hasBinaryFile: true,
      raw: record
    });
  }
  return list;
}

export function findBestMatches(prompt, candidates, pinnedSourceId = SOURCE_AUTO, maxResults = 2) {
  if (!candidates?.length || pinnedSourceId === SOURCE_NONE) return [];
  if (pinnedSourceId !== SOURCE_AUTO) {
    return candidates.filter((c) => c.sourceId === pinnedSourceId).slice(0, 1);
  }

  const tokens = extractMeaningfulTokens(prompt);
  const normPrompt = normalizeForSearch(prompt);

  if (tokens.length === 0) {
    const downloadable = candidates.filter((c) => c.hasBinaryFile);
    return downloadable.length === 1 ? downloadable : [];
  }

  const scored = [];
  for (const candidate of candidates) {
    const fileNorm = normalizeForSearch(candidate.fileName);
    const titleNorm = normalizeForSearch(candidate.displayTitle);
    const descNorm = normalizeForSearch(candidate.contextSubtitle);

    const fileWords = new Set(fileNorm.split(' ').flatMap(normalizeTokenVariants));
    const titleWords = new Set(titleNorm.split(' ').flatMap(normalizeTokenVariants));
    const descWords = new Set(descNorm.split(' ').flatMap(normalizeTokenVariants));

    let score = 0;
    for (const token of tokens) {
      if (fileWords.has(token) || titleWords.has(token)) {
        score += 18;
      } else if (token.length >= 3 && (fileNorm.includes(token) || titleNorm.includes(token))) {
        score += 12;
      } else if (
        token.length >= 4 &&
        (Array.from(fileWords).some((w) => w.startsWith(token) || token.startsWith(w)) ||
          Array.from(titleWords).some((w) => w.startsWith(token) || token.startsWith(w)))
      ) {
        score += 9;
      } else if (descWords.has(token)) {
        score += 6;
      } else if (token.length >= 3 && descNorm.includes(token)) {
        score += 4;
      }
    }

    if (score > 0 && titleNorm.length >= 3 && normPrompt.includes(titleNorm)) {
      score += 15;
    }
    if (score > 0 && candidate.hasBinaryFile) {
      score += 4;
    }
    if (score > 0) {
      scored.push({ candidate, score });
    }
  }

  scored.sort(
    (a, b) =>
      b.score - a.score || Number(b.candidate.hasBinaryFile) - Number(a.candidate.hasBinaryFile)
  );
  return scored.slice(0, maxResults).map((item) => item.candidate);
}

export function isSupportedInlineMediaMime(mimeType) {
  const m = String(mimeType || '').toLowerCase();
  return m === 'application/pdf' || m === 'image/png' || m === 'image/jpeg' || m === 'image/webp';
}

function uint8ToBase64Chunked(uint8) {
  let binary = '';
  const chunkSize = 0x8000;
  for (let i = 0; i < uint8.length; i += chunkSize) {
    binary += String.fromCharCode(...uint8.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

function estimateBytesFromBase64(b64) {
  if (!b64) return 0;
  return Math.floor((b64.length * 3) / 4);
}

export async function loadMatchedMaterialsForAi({ db, matchedCandidates }) {
  if (!matchedCandidates?.length) return [];
  const parts = [];
  let cumulativeBytes = 0;

  for (const candidate of matchedCandidates) {
    if (candidate.kind === 'COURSE') {
      const course = candidate.raw;
      const summary = [
        `Course Title: ${course.title}`,
        course.departmentName ? `Department: ${course.departmentName}` : '',
        course.academicYear ? `Year: ${course.academicYear}` : '',
        course.description ? `Description: ${course.description}` : ''
      ]
        .filter(Boolean)
        .join('\n');

      let attached = false;
      if (candidate.hasBinaryFile) {
        try {
          const res = await downloadCourseAttachmentBytes(db, course);
          const byteCount = res?.bytes?.length || 0;
          if (
            res?.base64 &&
            isSupportedInlineMediaMime(res.mimeType) &&
            byteCount > 0 &&
            byteCount <= MAX_INLINE_FILE_BYTES &&
            cumulativeBytes + byteCount <= MAX_TOTAL_INLINE_BYTES
          ) {
            cumulativeBytes += byteCount;
            parts.push({
              sourceLabel: res.fileName || candidate.fileName,
              mimeType: res.mimeType,
              base64: res.base64,
              courseSummary: summary
            });
            attached = true;
          }
        } catch {
          // Fall back to course summary below
        }
      }
      if (!attached) {
        parts.push({
          sourceLabel: candidate.displayTitle,
          mimeType: 'text/plain',
          courseSummary: summary
        });
      }
    } else if (candidate.kind === 'DRIVE') {
      const file = candidate.raw;
      let attached = false;
      if (typeof window !== 'undefined' && window.unicoreDesktop?.downloadAndOpenDriveFile) {
        try {
          const res = await window.unicoreDesktop.downloadAndOpenDriveFile({
            ...file,
            openExternally: false,
            skipDownloadsCopy: true
          });
          const byteCount = estimateBytesFromBase64(res?.base64);
          if (
            res?.base64 &&
            isSupportedInlineMediaMime(res.mimeType) &&
            byteCount > 0 &&
            byteCount <= MAX_INLINE_FILE_BYTES &&
            cumulativeBytes + byteCount <= MAX_TOTAL_INLINE_BYTES
          ) {
            cumulativeBytes += byteCount;
            parts.push({
              sourceLabel: res.fileName || candidate.fileName,
              mimeType: res.mimeType,
              base64: res.base64,
              courseSummary: candidate.contextSubtitle
            });
            attached = true;
          } else if (res?.textContent) {
            parts.push({
              sourceLabel: res.fileName || candidate.fileName,
              mimeType: 'text/plain',
              extractedText: res.textContent,
              courseSummary: candidate.contextSubtitle
            });
            attached = true;
          }
        } catch {
          // Fallback note below
        }
      }
      if (!attached) {
        parts.push({
          sourceLabel: candidate.fileName,
          mimeType: 'text/plain',
          courseSummary: `${candidate.contextSubtitle}\nDrive File: ${candidate.fileName}\nURL: ${file.viewUrl || ''}`
        });
      }
    } else if (candidate.kind === 'VAULT') {
      const record = candidate.raw;
      try {
        const cached = await loadVaultBlob(record.sha256Hex);
        if (cached?.bytes) {
          const rawBytes = cached.bytes instanceof Uint8Array ? cached.bytes : new Uint8Array(cached.bytes);
          const mime = cached.mimeType || record.mimeType;
          if (
            isSupportedInlineMediaMime(mime) &&
            rawBytes.length > 0 &&
            rawBytes.length <= MAX_INLINE_FILE_BYTES &&
            cumulativeBytes + rawBytes.length <= MAX_TOTAL_INLINE_BYTES
          ) {
            cumulativeBytes += rawBytes.length;
            parts.push({
              sourceLabel: record.originalName,
              mimeType: mime,
              base64: uint8ToBase64Chunked(rawBytes),
              courseSummary: candidate.contextSubtitle
            });
          }
        }
      } catch {
        // Ignore unavailable local blob
      }
    }
  }

  return parts;
}
