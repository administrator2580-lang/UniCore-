import {
  collection,
  deleteDoc,
  deleteField,
  doc,
  getDoc,
  getDocs,
  increment,
  limit,
  onSnapshot,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where
} from 'firebase/firestore';
import {
  base64ToUint8,
  CHUNK_RAW_BYTES,
  extractGoogleDriveFolder,
  isValidId,
  MAX_INLINE_COURSE_FILE_BYTES,
  sanitizeText,
  sha256Hex,
  uint8ToBase64,
  validateBoundedText,
  validateOptionalHttpsUrl,
  validateSelectedUploadFile
} from '../security/validators';

export const BOOTSTRAPPED_SUPER_ADMIN_EMAIL = 'fvathyvhmed@gmail.com';
const EMAIL_REGEX = /^[a-z0-9_.+-]+@[a-z0-9.-]+\.[a-z]{2,}$/;

export function currentUtcDayKey() {
  return new Date().toISOString().slice(0, 10);
}

function makeShortId(prefix) {
  const rand = crypto.randomUUID ? crypto.randomUUID().replace(/-/g, '') : `${Date.now()}${Math.random().toString(36).slice(2)}`;
  return `${prefix}_${rand.slice(0, 20)}`;
}

function timestampSec(ts) {
  if (!ts) return Number.MAX_SAFE_INTEGER;
  if (typeof ts.seconds === 'number') return ts.seconds;
  if (typeof ts.toMillis === 'function') return Math.floor(ts.toMillis() / 1000);
  return Number.MAX_SAFE_INTEGER;
}

export async function logAuditEvent(db, auth, action, severity, targetPath, details) {
  const uid = auth.currentUser?.uid;
  if (!uid) return;
  const logId = makeShortId('log');
  const rawLabel = sanitizeText(auth.currentUser?.displayName || auth.currentUser?.email || uid).slice(0, 80);
  const actorLabel = rawLabel.length >= 2 ? rawLabel : 'user';
  try {
    await setDoc(doc(db, 'audit_logs', logId), {
      actorUid: uid,
      actorLabel,
      action,
      severity: severity || 'INFO',
      targetPath: String(targetPath || 'unknown').slice(0, 180) || 'unknown',
      details: sanitizeText(details).slice(0, 400) || 'Security event',
      createdAt: serverTimestamp()
    });
  } catch {
    // Non-fatal audit write failure
  }
}

export async function ensureUserSessionProfile(db, auth) {
  const user = auth.currentUser;
  if (!user?.uid) throw new Error('Not authenticated');
  const uid = user.uid;
  const today = currentUtcDayKey();
  const normalizedEmail = (user.email || '').trim().toLowerCase();
  const isBootstrappedSuperAdmin =
    normalizedEmail === BOOTSTRAPPED_SUPER_ADMIN_EMAIL && user.emailVerified === true;

  let pendingInvite = null;
  if (normalizedEmail && user.emailVerified === true) {
    try {
      const invSnap = await getDoc(doc(db, 'role_invites', normalizedEmail));
      if (invSnap.exists() && invSnap.data()?.status === 'PENDING') {
        pendingInvite = { id: invSnap.id, ...invSnap.data() };
      }
    } catch {
      pendingInvite = null;
    }
  }

  let hasSuperAdminDoc = false;
  try {
    hasSuperAdminDoc = (await getDoc(doc(db, 'super_admins', uid))).exists();
  } catch {
    hasSuperAdminDoc = false;
  }

  let hasAdminDoc = false;
  try {
    hasAdminDoc = (await getDoc(doc(db, 'admins', uid))).exists();
  } catch {
    hasAdminDoc = false;
  }

  const hasPendingAdminInvite = pendingInvite?.assignedRole === 'ADMIN';
  const resolvedRole =
    isBootstrappedSuperAdmin || hasSuperAdminDoc
      ? 'SUPER_ADMIN'
      : hasAdminDoc || hasPendingAdminInvite
        ? 'ADMIN'
        : 'STUDENT';

  if (isBootstrappedSuperAdmin) {
    const superRef = doc(db, 'super_admins', uid);
    if (!(await getDoc(superRef)).exists()) {
      await setDoc(superRef, {
        userId: uid,
        email: normalizedEmail || BOOTSTRAPPED_SUPER_ADMIN_EMAIL,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });
    }
    const settingsRef = doc(db, 'system_settings', 'global');
    if (!(await getDoc(settingsRef)).exists()) {
      await setDoc(settingsRef, {
        dailyAiLimitPerUser: 20,
        dailyWriteLimitPerUser: 100,
        maxFileUploadBytes: 5_242_880,
        aiEnabled: true,
        zeroCostLockActive: false,
        updatedBy: uid,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });
    }
  }

  // Step 1: Pending Admin Invite -> /admins/{uid} before claiming invite
  if (hasPendingAdminInvite && !hasAdminDoc && pendingInvite && normalizedEmail) {
    await setDoc(doc(db, 'admins', uid), {
      userId: uid,
      email: normalizedEmail,
      grantedBy: pendingInvite.invitedBy,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
  }

  // Step 2: Create or update /users/{uid}
  const userRef = doc(db, 'users', uid);
  const userSnap = await getDoc(userRef);
  let cleanName = sanitizeText(user.displayName || pendingInvite?.displayNameHint || 'UniCore Learner').slice(0, 60);
  if (cleanName.length < 2) cleanName = 'Learner';

  if (!userSnap.exists()) {
    await setDoc(userRef, {
      userId: uid,
      displayName: cleanName,
      role: resolvedRole,
      aiCallsToday: 0,
      writesToday: 0,
      quotaDayKey: today,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
  } else {
    const data = userSnap.data() || {};
    const updates = { updatedAt: serverTimestamp() };
    if (data.quotaDayKey !== today) {
      updates.aiCallsToday = 0;
      updates.writesToday = 0;
      updates.quotaDayKey = today;
    }
    if (hasPendingAdminInvite && data.role === 'STUDENT') {
      updates.role = 'ADMIN';
    }
    if (Object.keys(updates).length > 1) {
      await updateDoc(userRef, updates);
    }
  }

  // Step 3: Mark invite CLAIMED
  if (pendingInvite && normalizedEmail) {
    try {
      await updateDoc(doc(db, 'role_invites', normalizedEmail), {
        status: 'CLAIMED',
        claimedByUid: uid,
        updatedAt: serverTimestamp()
      });
    } catch {
      // Ignore if already claimed
    }
  }

  // Step 4: Isolated PII in /users/{uid}/private/pii
  const piiRef = doc(db, 'users', uid, 'private', 'pii');
  if (!(await getDoc(piiRef)).exists()) {
    await setDoc(piiRef, {
      userId: uid,
      email: (normalizedEmail || 'unknown@unicore.edu').slice(0, 120),
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
  }

  await logAuditEvent(
    db,
    auth,
    'AUTH_SUCCESS',
    'INFO',
    `users/${uid}`,
    `Desktop/Web session initialized with role ${resolvedRole}.`
  );

  return resolvedRole;
}

export async function incrementDailyCounter(db, uid, isAi) {
  await updateDoc(doc(db, 'users', uid), {
    [isAi ? 'aiCallsToday' : 'writesToday']: increment(1),
    quotaDayKey: currentUtcDayKey(),
    updatedAt: serverTimestamp()
  });
}

// --- Real-time Subscriptions ---

export function subscribeUserProfile(db, uid, onData, onError) {
  return onSnapshot(
    doc(db, 'users', uid),
    (snap) => onData(snap.exists() ? { id: snap.id, ...snap.data() } : null),
    onError
  );
}

export function subscribeSystemSettings(db, onData) {
  return onSnapshot(
    doc(db, 'system_settings', 'global'),
    (snap) =>
      onData(
        snap.exists()
          ? snap.data()
          : {
              dailyAiLimitPerUser: 20,
              dailyWriteLimitPerUser: 100,
              maxFileUploadBytes: 5_242_880,
              aiEnabled: true,
              zeroCostLockActive: false
            }
      ),
    () => {}
  );
}

export function subscribeCourses(db, isAdmin, onData, onError) {
  const q = isAdmin
    ? collection(db, 'courses')
    : query(collection(db, 'courses'), where('published', '==', true));
  return onSnapshot(
    q,
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .sort((a, b) => timestampSec(b.createdAt) - timestampSec(a.createdAt));
      onData(list);
    },
    onError
  );
}

export function subscribeDepartments(db, onData, onError) {
  return onSnapshot(
    collection(db, 'departments'),
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .sort((a, b) => timestampSec(a.createdAt) - timestampSec(b.createdAt));
      onData(list);
    },
    onError
  );
}

export function subscribeDriveFolders(db, onData, onError) {
  return onSnapshot(
    collection(db, 'drive_folders'),
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .sort((a, b) => timestampSec(b.createdAt) - timestampSec(a.createdAt));
      onData(list);
    },
    onError
  );
}

export function subscribeQuizzes(db, isAdmin, onData, onError) {
  const q = isAdmin
    ? collection(db, 'quizzes')
    : query(collection(db, 'quizzes'), where('published', '==', true));
  return onSnapshot(
    q,
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .sort((a, b) => timestampSec(b.createdAt) - timestampSec(a.createdAt));
      onData(list);
    },
    onError
  );
}

export function subscribeQuizAttempts(db, uid, isAdmin, onData, onError) {
  const q = isAdmin
    ? collection(db, 'quiz_attempts')
    : query(collection(db, 'quiz_attempts'), where('userId', '==', uid));
  return onSnapshot(
    q,
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .sort((a, b) => timestampSec(b.updatedAt) - timestampSec(a.updatedAt));
      onData(list);
    },
    onError
  );
}

export function subscribeVerifiedFiles(db, uid, onData, onError) {
  return onSnapshot(
    collection(db, 'users', uid, 'files'),
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .sort((a, b) => timestampSec(b.createdAt) - timestampSec(a.createdAt));
      onData(list);
    },
    onError
  );
}

export function subscribeNotifications(db, uid, isAdmin, onData, onError) {
  const q = isAdmin
    ? collection(db, 'notifications')
    : query(collection(db, 'notifications'), where('targetUid', 'in', ['ALL', uid]));
  return onSnapshot(
    q,
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .sort((a, b) => timestampSec(b.createdAt) - timestampSec(a.createdAt));
      onData(list);
    },
    onError
  );
}

export function subscribeSupportMessages(db, uid, role, onData, onError) {
  if (role === 'SUPER_ADMIN') {
    return onSnapshot(
      collection(db, 'support_messages'),
      (snap) => {
        const list = snap.docs
          .map((d) => ({ id: d.id, ...d.data() }))
          .sort((a, b) => timestampSec(b.updatedAt || b.createdAt) - timestampSec(a.updatedAt || a.createdAt));
        onData(list);
      },
      onError
    );
  }

  if (role === 'ADMIN') {
    let studentList = [];
    let ownList = [];
    const emitMerged = () => {
      const map = new Map();
      [...studentList, ...ownList].forEach((m) => map.set(m.id, m));
      const merged = Array.from(map.values()).sort(
        (a, b) => timestampSec(b.updatedAt || b.createdAt) - timestampSec(a.updatedAt || a.createdAt)
      );
      onData(merged);
    };
    const u1 = onSnapshot(
      query(collection(db, 'support_messages'), where('channel', '==', 'STUDENT_TO_ADMIN')),
      (snap) => {
        studentList = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
        emitMerged();
      },
      onError
    );
    const u2 = onSnapshot(
      query(collection(db, 'support_messages'), where('senderUid', '==', uid)),
      (snap) => {
        ownList = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
        emitMerged();
      },
      onError
    );
    return () => {
      u1();
      u2();
    };
  }

  return onSnapshot(
    query(collection(db, 'support_messages'), where('senderUid', '==', uid)),
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .sort((a, b) => timestampSec(b.updatedAt || b.createdAt) - timestampSec(a.updatedAt || a.createdAt));
      onData(list);
    },
    onError
  );
}

export function subscribeUsersDirectory(db, onData, onError) {
  return onSnapshot(
    collection(db, 'users'),
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .sort((a, b) => String(a.role).localeCompare(String(b.role)) || String(a.displayName).localeCompare(String(b.displayName)));
      onData(list);
    },
    onError
  );
}

export function subscribeRoleInvites(db, onData, onError) {
  return onSnapshot(
    collection(db, 'role_invites'),
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .sort((a, b) => timestampSec(b.createdAt) - timestampSec(a.createdAt));
      onData(list);
    },
    onError
  );
}

export function subscribeAuditLogs(db, onData, onError) {
  const q = query(collection(db, 'audit_logs'), orderBy('createdAt', 'desc'), limit(50));
  return onSnapshot(
    q,
    (snap) => onData(snap.docs.map((d) => ({ id: d.id, ...d.data() }))),
    onError
  );
}

// --- Write Operations ---

export async function sendNotification(db, auth, { title, body, category = 'ANNOUNCEMENT', targetUid = 'ALL' }) {
  const tRes = validateBoundedText(title, 'Notification title', 2, 100);
  if (!tRes.valid) throw new Error(tRes.reason);
  const bRes = validateBoundedText(body, 'Notification message', 2, 400);
  if (!bRes.valid) throw new Error(bRes.reason);
  const safeTarget = targetUid === 'ALL' ? 'ALL' : isValidId(targetUid) ? targetUid : null;
  if (!safeTarget) throw new Error('Invalid notification target.');

  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  let senderName = sanitizeText(auth.currentUser?.displayName || 'UniCore Admin').slice(0, 60);
  if (senderName.length < 2) senderName = 'Admin';

  const notifId = makeShortId('notif');
  await setDoc(doc(db, 'notifications', notifId), {
    title: tRes.value,
    body: bRes.value,
    category,
    targetUid: safeTarget,
    senderUid: uid,
    senderName,
    createdAt: serverTimestamp()
  });

  await logAuditEvent(db, auth, 'NOTIFICATION_SENT', 'INFO', `notifications/${notifId}`, `Sent ${category} notification to ${safeTarget}.`);
}

export async function createCourseWithOptionalFile(
  db,
  auth,
  { title, description, materialLink, materialType, file, departmentId, departmentName, academicYear }
) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Sign in required.');
  const tRes = validateBoundedText(title, 'Course title', 3, 100);
  if (!tRes.valid) throw new Error(tRes.reason);
  const dRes = validateBoundedText(description, 'Description', 1, 600);
  if (!dRes.valid) throw new Error(dRes.reason);
  const lRes = validateOptionalHttpsUrl(materialLink);
  if (!lRes.valid) throw new Error(lRes.reason);

  let validated = null;
  const encodedChunks = [];
  if (file) {
    validated = await validateSelectedUploadFile(file, MAX_INLINE_COURSE_FILE_BYTES);
    for (let offset = 0; offset < validated.bytes.length; offset += CHUNK_RAW_BYTES) {
      const slice = validated.bytes.subarray(offset, Math.min(offset + CHUNK_RAW_BYTES, validated.bytes.length));
      encodedChunks.push(uint8ToBase64(slice));
    }
    if (encodedChunks.length > 6) {
      throw new Error('Attached file exceeds maximum chunk count.');
    }
  }

  let instructorName = sanitizeText(auth.currentUser?.displayName || 'Instructor').slice(0, 60);
  if (instructorName.length < 2) instructorName = 'Instructor';

  const docRef = doc(collection(db, 'courses'));
  const payload = {
    title: tRes.value,
    description: dRes.value,
    instructorId: uid,
    instructorName,
    materialType: validated ? 'FILE' : materialType || 'NOTES',
    hasAttachment: Boolean(validated),
    published: !validated,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  };

  if (lRes.value) payload.materialLink = lRes.value;
  if (departmentId && isValidId(departmentId)) payload.departmentId = departmentId;
  if (departmentName && sanitizeText(departmentName).length >= 2) {
    payload.departmentName = sanitizeText(departmentName).slice(0, 80);
  }
  if (academicYear && sanitizeText(academicYear)) {
    payload.academicYear = sanitizeText(academicYear).slice(0, 40);
  }
  if (validated) {
    payload.attachmentName = validated.originalName.slice(0, 140);
    payload.attachmentMimeType = validated.mimeType;
    payload.attachmentSizeBytes = validated.sizeBytes;
    payload.chunkCount = encodedChunks.length;
    payload.attachmentSha256 = validated.sha256Hex;
  }

  await setDoc(docRef, payload);

  try {
    if (validated) {
      for (let idx = 0; idx < encodedChunks.length; idx++) {
        await setDoc(doc(db, 'courses', docRef.id, 'chunks', String(idx)), {
          chunkIndex: idx,
          data: encodedChunks[idx],
          createdAt: serverTimestamp()
        });
      }
      await updateDoc(docRef, {
        published: true,
        updatedAt: serverTimestamp()
      });
    }
  } catch (err) {
    await deleteCourse(db, docRef.id).catch(() => {});
    throw err;
  }

  await incrementDailyCounter(db, uid, false);
  await sendNotification(db, auth, {
    title: tRes.value,
    body: validated ? `${instructorName} uploaded ${validated.originalName}.` : `${instructorName} published new course material.`,
    category: 'COURSE',
    targetUid: 'ALL'
  });
}

export async function updateCourseDetails(db, { courseId, title, description, departmentId, departmentName, academicYear }) {
  const tRes = validateBoundedText(title, 'Course title', 3, 100);
  if (!tRes.valid) throw new Error(tRes.reason);
  const dRes = validateBoundedText(description, 'Description', 1, 600);
  if (!dRes.valid) throw new Error(dRes.reason);

  const updates = {
    title: tRes.value,
    description: dRes.value,
    departmentId: departmentId && isValidId(departmentId) ? departmentId : deleteField(),
    departmentName:
      departmentName && sanitizeText(departmentName).length >= 2
        ? sanitizeText(departmentName).slice(0, 80)
        : deleteField(),
    academicYear: academicYear && sanitizeText(academicYear) ? sanitizeText(academicYear).slice(0, 40) : deleteField(),
    updatedAt: serverTimestamp()
  };

  await updateDoc(doc(db, 'courses', courseId), updates);
}

export async function deleteCourse(db, courseId) {
  try {
    const chunksSnap = await getDocs(collection(db, 'courses', courseId, 'chunks'));
    await Promise.all(chunksSnap.docs.map((d) => deleteDoc(d.ref)));
  } catch {
    // Proceed deleting course document
  }
  await deleteDoc(doc(db, 'courses', courseId));
}

export async function downloadCourseAttachmentBytes(db, course) {
  if (!course?.hasAttachment || !course?.chunkCount || !course?.attachmentSha256) {
    throw new Error('This course does not have a downloadable attachment.');
  }
  const chunkSnap = await getDocs(collection(db, 'courses', course.id, 'chunks'));
  const ordered = chunkSnap.docs
    .map((d) => ({
      idx: typeof d.data().chunkIndex === 'number' ? d.data().chunkIndex : Number(d.id),
      data: d.data().data
    }))
    .filter((c) => !Number.isNaN(c.idx) && typeof c.data === 'string')
    .sort((a, b) => a.idx - b.idx);

  if (ordered.length < course.chunkCount) {
    throw new Error('Attachment chunks are incomplete.');
  }

  const parts = ordered.map((c) => base64ToUint8(c.data));
  const totalLen = parts.reduce((acc, p) => acc + p.length, 0);
  const combined = new Uint8Array(totalLen);
  let pos = 0;
  for (const p of parts) {
    combined.set(p, pos);
    pos += p.length;
  }

  const actualSha = await sha256Hex(combined);
  if (actualSha.toLowerCase() !== String(course.attachmentSha256).toLowerCase()) {
    throw new Error('File integrity check (SHA-256) failed.');
  }

  return {
    bytes: combined,
    base64: uint8ToBase64(combined),
    fileName: course.attachmentName || 'material.pdf',
    mimeType: course.attachmentMimeType || 'application/pdf'
  };
}

// --- Departments ---

export async function saveDepartment(db, auth, { existingDeptId, name, years }) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  const nRes = validateBoundedText(name, 'Department name', 2, 80);
  if (!nRes.valid) throw new Error(nRes.reason);

  const cleanYears = Array.from(
    new Set(
      (years || [])
        .map((y) => sanitizeText(y).slice(0, 40))
        .filter(Boolean)
    )
  ).slice(0, 12);

  if (cleanYears.length === 0) {
    throw new Error('Provide at least 1 study year or level.');
  }

  const deptId = existingDeptId && isValidId(existingDeptId) ? existingDeptId : makeShortId('dept');
  if (existingDeptId) {
    await updateDoc(doc(db, 'departments', deptId), {
      name: nRes.value,
      years: cleanYears,
      updatedAt: serverTimestamp()
    });
  } else {
    await setDoc(doc(db, 'departments', deptId), {
      name: nRes.value,
      years: cleanYears,
      createdBy: uid,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
  }

  await logAuditEvent(
    db,
    auth,
    'DEPARTMENT_UPDATED',
    'INFO',
    `departments/${deptId}`,
    `${existingDeptId ? 'Updated' : 'Created'} department '${nRes.value}' (${cleanYears.length} levels).`
  );
}

export async function deleteDepartment(db, auth, deptId) {
  await deleteDoc(doc(db, 'departments', deptId));
  await logAuditEvent(db, auth, 'DEPARTMENT_UPDATED', 'INFO', `departments/${deptId}`, 'Deleted academic department.');
}

// --- Drive Folders ---

export async function linkDriveFolder(db, auth, { title, folderUrl, departmentId, departmentName, academicYear }) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  const tRes = validateBoundedText(title, 'Folder title', 2, 100);
  if (!tRes.valid) throw new Error(tRes.reason);
  const dRes = extractGoogleDriveFolder(folderUrl);
  if (!dRes.valid) throw new Error(dRes.reason);

  let addedByName = sanitizeText(auth.currentUser?.displayName || 'Admin').slice(0, 60);
  if (addedByName.length < 2) addedByName = 'Admin';

  const payload = {
    folderId: dRes.folderId,
    title: tRes.value,
    folderUrl: dRes.canonicalUrl,
    addedBy: uid,
    addedByName,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  };
  if (departmentId && isValidId(departmentId)) payload.departmentId = departmentId;
  if (departmentName && sanitizeText(departmentName).length >= 2) {
    payload.departmentName = sanitizeText(departmentName).slice(0, 80);
  }
  if (academicYear && sanitizeText(academicYear)) {
    payload.academicYear = sanitizeText(academicYear).slice(0, 40);
  }

  await setDoc(doc(db, 'drive_folders', dRes.folderId), payload);
  await logAuditEvent(
    db,
    auth,
    'DRIVE_FOLDER_LINKED',
    'INFO',
    `drive_folders/${dRes.folderId}`,
    `Connected Google Drive folder '${tRes.value}'.`
  );
  await sendNotification(db, auth, {
    title: tRes.value,
    body: `${addedByName} connected a live Google Drive folder to Courses.`,
    category: 'COURSE',
    targetUid: 'ALL'
  });
}

export async function unlinkDriveFolder(db, folderId) {
  await deleteDoc(doc(db, 'drive_folders', folderId));
}

// --- Quizzes ---

export async function createQuizWithAnswerKey(db, auth, { courseId, title, durationMinutes, prompts, optionBlocks, correctIndices }) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  if (!courseId) throw new Error('Select a course first.');
  const tRes = validateBoundedText(title, 'Quiz title', 3, 100);
  if (!tRes.valid) throw new Error(tRes.reason);

  const cleanPrompts = [];
  for (const p of prompts || []) {
    const r = validateBoundedText(p, 'Question', 5, 250);
    if (!r.valid) throw new Error(r.reason);
    cleanPrompts.push(r.value);
  }
  if (cleanPrompts.length === 0 || cleanPrompts.length !== optionBlocks.length || cleanPrompts.length !== correctIndices.length) {
    throw new Error('Provide at least 1 complete question.');
  }

  const cleanOptionBlocks = optionBlocks.map((block) => {
    const opts = String(block)
      .split('|')
      .map((s) => sanitizeText(s).slice(0, 80))
      .filter(Boolean);
    if (opts.length < 2) throw new Error('Each question needs at least 2 options.');
    return opts.join('|');
  });

  const safeCorrect = correctIndices.map((idx, i) => {
    const count = cleanOptionBlocks[i].split('|').length;
    return Math.max(0, Math.min(Number(idx) || 0, count - 1));
  });

  const mins = Math.max(1, Math.min(180, Number(durationMinutes) || 15));
  const quizRef = doc(collection(db, 'quizzes'));

  await setDoc(quizRef, {
    courseId,
    title: tRes.value,
    durationMinutes: mins,
    questionPrompts: cleanPrompts,
    optionBlocks: cleanOptionBlocks,
    published: true,
    createdBy: uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });

  await setDoc(doc(db, 'quizzes', quizRef.id, 'private', 'answer_key'), {
    quizId: quizRef.id,
    correctIndices: safeCorrect,
    passingScore: 70,
    updatedBy: uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });

  await incrementDailyCounter(db, uid, false);
  await sendNotification(db, auth, {
    title: tRes.value,
    body: `New ${cleanPrompts.length}-question quiz published (${mins} min).`,
    category: 'QUIZ',
    targetUid: 'ALL'
  });
}

export async function deleteQuiz(db, quizId) {
  try {
    await deleteDoc(doc(db, 'quizzes', quizId, 'private', 'answer_key'));
  } catch {
    // Ignore if already absent
  }
  await deleteDoc(doc(db, 'quizzes', quizId));
}

export async function submitQuizAttempt(db, auth, { quiz, selectedIndices }) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  const attemptId = `${uid}_${quiz.id}`;
  let studentName = sanitizeText(auth.currentUser?.displayName || 'Student').slice(0, 60);
  if (studentName.length < 2) studentName = 'Student';

  await setDoc(doc(db, 'quiz_attempts', attemptId), {
    userId: uid,
    studentName,
    quizId: quiz.id,
    quizTitle: String(quiz.title || 'Quiz').slice(0, 100),
    selectedIndices: selectedIndices.map((n) => Number(n) || 0),
    status: 'SUBMITTED',
    score: 0,
    graded: false,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });

  await logAuditEvent(
    db,
    auth,
    'QUIZ_SUBMITTED',
    'INFO',
    `quiz_attempts/${attemptId}`,
    `Student submitted quiz '${quiz.title}' for verification.`
  );
  await incrementDailyCounter(db, uid, false);
}

export async function gradeQuizAttemptAsAdmin(db, auth, attempt) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  const keySnap = await getDoc(doc(db, 'quizzes', attempt.quizId, 'private', 'answer_key'));
  if (!keySnap.exists()) throw new Error('Protected answer key not found.');

  const correctIndices = keySnap.data()?.correctIndices || [];
  const total = Math.max(1, correctIndices.length);
  let correctCount = 0;
  for (let i = 0; i < total; i++) {
    if (Number(attempt.selectedIndices?.[i]) === Number(correctIndices[i])) {
      correctCount++;
    }
  }
  const computedScore = Math.max(0, Math.min(100, Math.round((correctCount * 100) / total)));

  await updateDoc(doc(db, 'quiz_attempts', attempt.id), {
    score: computedScore,
    graded: true,
    status: 'GRADED',
    gradedBy: uid,
    updatedAt: serverTimestamp()
  });

  await logAuditEvent(
    db,
    auth,
    'GRADE_FINALIZED',
    'INFO',
    `quiz_attempts/${attempt.id}`,
    `Admin finalized grade (${computedScore}%) for student attempt.`
  );
  await sendNotification(db, auth, {
    title: attempt.quizTitle,
    body: `Your quiz result is out: ${computedScore}%.`,
    category: 'GRADE',
    targetUid: attempt.userId
  });
  return computedScore;
}

// --- Vault Files ---

export async function recordVerifiedVaultFile(db, auth, file) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  const validated = await validateSelectedUploadFile(file);
  const fileRef = doc(collection(db, 'users', uid, 'files'));

  await setDoc(fileRef, {
    userId: uid,
    originalName: validated.originalName,
    storedName: validated.storedName,
    mimeType: validated.mimeType,
    sizeBytes: validated.sizeBytes,
    sha256Hex: validated.sha256Hex,
    status: 'VERIFIED',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });

  await logAuditEvent(
    db,
    auth,
    'FILE_UPLOAD_VERIFIED',
    'INFO',
    `users/${uid}/files/${fileRef.id}`,
    `Sandboxed ${validated.mimeType} (${validated.sizeBytes}B) sha256=${validated.sha256Hex.slice(0, 12)}...`
  );

  return { fileId: fileRef.id, validated };
}

// --- Accounts, Invites, Support, Settings ---

export async function inviteAccountByEmail(db, auth, { rawEmail, rawNameHint, assignedRole }) {
  const email = String(rawEmail || '').trim().toLowerCase();
  if (email.length < 3 || email.length > 120 || !EMAIL_REGEX.test(email)) {
    throw new Error('Enter a valid Google email address.');
  }
  if (assignedRole === 'SUPER_ADMIN') {
    throw new Error('Super Admin role cannot be assigned via invite.');
  }
  const nRes = validateBoundedText(rawNameHint, 'Name', 2, 60);
  if (!nRes.valid) throw new Error(nRes.reason);

  const uid = auth.currentUser?.uid;
  let inviterLabel = sanitizeText(auth.currentUser?.displayName || auth.currentUser?.email || uid).slice(0, 80);
  if (inviterLabel.length < 2) inviterLabel = 'Admin';

  await setDoc(doc(db, 'role_invites', email), {
    email,
    displayNameHint: nRes.value,
    assignedRole,
    invitedBy: uid,
    invitedByLabel: inviterLabel,
    status: 'PENDING',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });

  await logAuditEvent(
    db,
    auth,
    'ACCOUNT_INVITED',
    'INFO',
    `role_invites/${email}`,
    `Pre-registered ${assignedRole} account invite.`
  );
}

export async function revokeInvite(db, auth, emailKey) {
  await deleteDoc(doc(db, 'role_invites', emailKey));
  await logAuditEvent(db, auth, 'ROLE_CHANGED', 'INFO', `role_invites/${emailKey}`, 'Removed account invite.');
}

export async function promoteUserToAdmin(db, auth, targetUser) {
  const uid = auth.currentUser?.uid;
  const cleanUid = targetUser.userId;
  let piiEmail = `${cleanUid.slice(0, 12)}@unicore.edu`;
  try {
    const piiSnap = await getDoc(doc(db, 'users', cleanUid, 'private', 'pii'));
    const found = piiSnap.data()?.email;
    if (found && found.length >= 3 && found.length <= 120) piiEmail = found;
  } catch {
    // Fallback used
  }

  await setDoc(doc(db, 'admins', cleanUid), {
    userId: cleanUid,
    email: piiEmail,
    grantedBy: uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });

  await updateDoc(doc(db, 'users', cleanUid), {
    role: 'ADMIN',
    updatedAt: serverTimestamp()
  });

  await logAuditEvent(
    db,
    auth,
    'ROLE_CHANGED',
    'HIGH',
    `admins/${cleanUid}`,
    `Super Admin promoted ${targetUser.displayName} to ADMIN.`
  );
  await sendNotification(db, auth, {
    title: 'Admin Access Granted',
    body: 'Your UniCore account has been promoted to Admin.',
    category: 'ROLE',
    targetUid: cleanUid
  });
}

export async function demoteAdminToStudent(db, auth, targetUid) {
  await deleteDoc(doc(db, 'admins', targetUid));
  await updateDoc(doc(db, 'users', targetUid), {
    role: 'STUDENT',
    updatedAt: serverTimestamp()
  }).catch(() => {});

  await logAuditEvent(
    db,
    auth,
    'ROLE_CHANGED',
    'HIGH',
    `admins/${targetUid}`,
    `Super Admin changed role of ${targetUid} to STUDENT.`
  );
  await sendNotification(db, auth, {
    title: 'Role Updated',
    body: 'Your UniCore role has been updated to Student.',
    category: 'ROLE',
    targetUid
  });
}

export async function setUserAccountDisabled(db, auth, targetUser, disabled) {
  const actorUid = auth.currentUser?.uid;
  if (targetUser.userId === actorUid) {
    throw new Error('You cannot disable your own Super Admin account.');
  }
  await updateDoc(doc(db, 'users', targetUser.userId), {
    accountDisabled: Boolean(disabled),
    updatedAt: serverTimestamp()
  });
  await logAuditEvent(
    db,
    auth,
    'ACCOUNT_STATUS_CHANGED',
    disabled ? 'HIGH' : 'INFO',
    `users/${targetUser.userId}`,
    `Super Admin ${disabled ? 'disabled' : 're-enabled'} account ${targetUser.displayName}.`
  );
}

export async function sendSupportMessage(db, auth, { userRole, subject, body }) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  const sRes = validateBoundedText(subject, 'Subject', 2, 100);
  if (!sRes.valid) throw new Error(sRes.reason);
  const bRes = validateBoundedText(body, 'Message', 2, 800);
  if (!bRes.valid) throw new Error(bRes.reason);

  const channel = userRole === 'ADMIN' ? 'ADMIN_TO_SUPER' : 'STUDENT_TO_ADMIN';
  const senderRole = userRole === 'ADMIN' ? 'ADMIN' : 'STUDENT';
  let senderName = sanitizeText(auth.currentUser?.displayName || 'UniCore User').slice(0, 60);
  if (senderName.length < 2) senderName = 'User';

  const msgId = makeShortId('msg');
  await setDoc(doc(db, 'support_messages', msgId), {
    channel,
    senderUid: uid,
    senderName,
    senderRole,
    subject: sRes.value,
    body: bRes.value,
    status: 'OPEN',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });

  await logAuditEvent(
    db,
    auth,
    'SUPPORT_MESSAGE_SENT',
    'INFO',
    `support_messages/${msgId}`,
    `Support inquiry (${channel}): ${sRes.value}`
  );
}

export async function replyToSupportMessage(db, auth, { message, replyText }) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  const rRes = validateBoundedText(replyText, 'Reply', 1, 800);
  if (!rRes.valid) throw new Error(rRes.reason);

  let replierName = sanitizeText(auth.currentUser?.displayName || 'Administrator').slice(0, 60);
  if (replierName.length < 2) replierName = 'Admin';

  await updateDoc(doc(db, 'support_messages', message.id), {
    status: 'REPLIED',
    replyText: rRes.value,
    repliedByUid: uid,
    repliedByName: replierName,
    updatedAt: serverTimestamp()
  });

  await sendNotification(db, auth, {
    title: `Reply: ${message.subject}`,
    body: `${replierName} replied to your support message.`,
    category: 'ANNOUNCEMENT',
    targetUid: message.senderUid
  });
}

export async function updateSystemSettingsAsSuperAdmin(db, auth, { dailyAiLimit, dailyWriteLimit, aiEnabled, zeroCostLockActive }) {
  const uid = auth.currentUser?.uid;
  const aiCap = Math.max(1, Math.min(100, Number(dailyAiLimit) || 20));
  const writeCap = Math.max(10, Math.min(500, Number(dailyWriteLimit) || 100));

  await updateDoc(doc(db, 'system_settings', 'global'), {
    dailyAiLimitPerUser: aiCap,
    dailyWriteLimitPerUser: writeCap,
    aiEnabled: Boolean(aiEnabled),
    zeroCostLockActive: Boolean(zeroCostLockActive),
    updatedBy: uid,
    updatedAt: serverTimestamp()
  });

  await logAuditEvent(
    db,
    auth,
    'SETTINGS_CHANGED',
    'HIGH',
    'system_settings/global',
    `Super Admin updated zero-cost settings: aiLimit=${aiCap}, aiEnabled=${Boolean(aiEnabled)}, lock=${Boolean(zeroCostLockActive)}`
  );
}
