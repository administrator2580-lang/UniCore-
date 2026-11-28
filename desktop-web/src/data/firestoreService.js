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
  assertCleanText,
  base64ToUint8,
  CHUNK_RAW_BYTES,
  compressImageFileToBase64,
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

export function normalizeUserRole(raw) {
  const upper = String(raw || '')
    .trim()
    .toUpperCase()
    .replace(/[-\s]+/g, '_');
  if (upper === 'SUPER_ADMIN' || upper === 'SUPERADMIN') return 'SUPER_ADMIN';
  if (upper === 'ADMIN') return 'ADMIN';
  return 'STUDENT';
}

export function matchesStudentScope(userProfile, userRole, item) {
  if (normalizeUserRole(userRole) !== 'STUDENT') return true;
  if (!userProfile || !userProfile.placementCompleted) return true;

  const itemDeptId = item?.departmentId || '';
  const itemDeptName = item?.departmentName || '';
  const itemYear = item?.academicYear || '';
  const itemSection = item?.sectionName || '';

  const pDeptId = userProfile.departmentId || '';
  const pDeptName = userProfile.departmentName || '';
  const pYear = userProfile.academicYear || '';
  const pSection = userProfile.sectionName || '';

  const deptMatches =
    (!itemDeptId && !itemDeptName) ||
    itemDeptId.toUpperCase() === 'ALL' ||
    itemDeptName.toUpperCase() === 'ALL' ||
    (!pDeptId && !pDeptName) ||
    (pDeptId && itemDeptId === pDeptId) ||
    (pDeptName && itemDeptName.trim().toLowerCase() === pDeptName.trim().toLowerCase());

  const yearMatches =
    !itemYear ||
    itemYear.toUpperCase() === 'ALL' ||
    !pYear ||
    itemYear.trim().toLowerCase() === pYear.trim().toLowerCase();

  const sectionMatches =
    !itemSection ||
    itemSection.toUpperCase() === 'ALL' ||
    !pSection ||
    itemSection.trim().toLowerCase() === pSection.trim().toLowerCase();

  return deptMatches && yearMatches && sectionMatches;
}

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

const SESSION_CACHE_PREFIX = 'unicore_session_meta_v1_';

export function getCachedUserRole(uid) {
  if (!uid) return null;
  try {
    const raw = localStorage.getItem(`${SESSION_CACHE_PREFIX}${uid}`);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (['STUDENT', 'ADMIN', 'SUPER_ADMIN'].includes(parsed?.role)) {
      return parsed.role;
    }
  } catch {
    // ignore
  }
  return null;
}

function readSessionMeta(uid) {
  try {
    const raw = localStorage.getItem(`${SESSION_CACHE_PREFIX}${uid}`);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

function writeSessionMeta(uid, patch) {
  try {
    const next = { ...readSessionMeta(uid), ...patch };
    localStorage.setItem(`${SESSION_CACHE_PREFIX}${uid}`, JSON.stringify(next));
  } catch {
    // non-fatal
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
  const sessionMeta = readSessionMeta(uid);

  try {
    const [pendingInvite, hasSuperAdminDoc, hasAdminDoc, userSnap] = await Promise.all([
      normalizedEmail && user.emailVerified === true
        ? getDoc(doc(db, 'role_invites', normalizedEmail))
            .then((invSnap) =>
              invSnap.exists() && invSnap.data()?.status === 'PENDING'
                ? { id: invSnap.id, ...invSnap.data() }
                : null
            )
            .catch(() => null)
        : Promise.resolve(null),
      isBootstrappedSuperAdmin
        ? Promise.resolve(true)
        : getDoc(doc(db, 'super_admins', uid))
            .then((s) => s.exists())
            .catch(() => false),
      getDoc(doc(db, 'admins', uid))
        .then((s) => s.exists())
        .catch(() => false),
      getDoc(doc(db, 'users', uid)).catch(() => null)
    ]);

    const pendingRole = normalizeUserRole(pendingInvite?.assignedRole);
    const hasPendingSuperInvite = Boolean(pendingInvite && pendingRole === 'SUPER_ADMIN');
    const hasPendingAdminInvite = Boolean(pendingInvite && pendingRole === 'ADMIN');
    const resolvedRole =
      isBootstrappedSuperAdmin || hasSuperAdminDoc || hasPendingSuperInvite
        ? 'SUPER_ADMIN'
        : hasAdminDoc || hasPendingAdminInvite
          ? 'ADMIN'
          : 'STUDENT';

    if (isBootstrappedSuperAdmin && !sessionMeta.superBootstrapped) {
      try {
        const superRef = doc(db, 'super_admins', uid);
        const settingsRef = doc(db, 'system_settings', 'global');
        const [superSnap, settingsSnap] = await Promise.all([getDoc(superRef), getDoc(settingsRef)]);
        await Promise.all([
          !superSnap.exists()
            ? setDoc(superRef, {
                userId: uid,
                email: normalizedEmail || BOOTSTRAPPED_SUPER_ADMIN_EMAIL,
                createdAt: serverTimestamp(),
                updatedAt: serverTimestamp()
              })
            : Promise.resolve(),
          !settingsSnap.exists()
            ? setDoc(settingsRef, {
                dailyAiLimitPerUser: 20,
                dailyWriteLimitPerUser: 100,
                maxFileUploadBytes: 5_242_880,
                aiEnabled: true,
                zeroCostLockActive: false,
                updatedBy: uid,
                createdAt: serverTimestamp(),
                updatedAt: serverTimestamp()
              })
            : Promise.resolve()
        ]);
        writeSessionMeta(uid, { superBootstrapped: true });
      } catch {
        // non-fatal when offline
      }
    }

    // Step 1: Pending Super Admin or Admin Invite -> marker collection before claiming invite
    if (hasPendingSuperInvite && !hasSuperAdminDoc && pendingInvite && normalizedEmail) {
      await setDoc(doc(db, 'super_admins', uid), {
        userId: uid,
        email: normalizedEmail,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });
    } else if (hasPendingAdminInvite && !hasAdminDoc && pendingInvite && normalizedEmail) {
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
    let cleanName = sanitizeText(user.displayName || pendingInvite?.displayNameHint || 'UniCore Learner').slice(0, 60);
    if (cleanName.length < 2) cleanName = 'Learner';

    if (userSnap && !userSnap.exists()) {
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
    } else if (userSnap && userSnap.exists()) {
      const data = userSnap.data() || {};
      const existingNormalized = normalizeUserRole(data.role);
      const updates = { updatedAt: serverTimestamp() };
      if (data.quotaDayKey !== today) {
        updates.aiCallsToday = 0;
        updates.writesToday = 0;
        updates.quotaDayKey = today;
      }
      if (resolvedRole === 'SUPER_ADMIN' && existingNormalized !== 'SUPER_ADMIN') {
        updates.role = 'SUPER_ADMIN';
      } else if (hasPendingAdminInvite && existingNormalized === 'STUDENT') {
        updates.role = 'ADMIN';
      } else if (data.role && data.role !== existingNormalized) {
        updates.role = existingNormalized;
      }
      if (Object.keys(updates).length > 1) {
        await updateDoc(userRef, updates).catch(() => {});
      }
    }

    // Step 3: Mark invite CLAIMED
    if (pendingInvite && normalizedEmail) {
      updateDoc(doc(db, 'role_invites', normalizedEmail), {
        status: 'CLAIMED',
        claimedByUid: uid,
        updatedAt: serverTimestamp()
      }).catch(() => {});
    }

    // Step 4: Isolated PII in /users/{uid}/private/pii (skip lookup if already cached)
    if (!sessionMeta.piiSaved) {
      try {
        const piiRef = doc(db, 'users', uid, 'private', 'pii');
        if (!(await getDoc(piiRef)).exists()) {
          await setDoc(piiRef, {
            userId: uid,
            email: (normalizedEmail || 'unknown@unicore.edu').slice(0, 120),
            createdAt: serverTimestamp(),
            updatedAt: serverTimestamp()
          });
        }
        writeSessionMeta(uid, { piiSaved: true });
      } catch {
        // ignore offline error
      }
    }

    const effectiveRole =
      resolvedRole !== 'STUDENT'
        ? resolvedRole
        : userSnap?.exists?.()
          ? normalizeUserRole(userSnap.data()?.role)
          : resolvedRole;

    writeSessionMeta(uid, { role: effectiveRole });

    if (sessionMeta.lastAuditDay !== today) {
      logAuditEvent(
        db,
        auth,
        'AUTH_SUCCESS',
        'INFO',
        `users/${uid}`,
        `Desktop/Web session initialized with role ${effectiveRole}.`
      )
        .then(() => writeSessionMeta(uid, { lastAuditDay: today }))
        .catch(() => {});
    }

    return effectiveRole;
  } catch (err) {
    const cachedRole = getCachedUserRole(uid);
    if (cachedRole) return cachedRole;
    throw err;
  }
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
  assertCleanText(`${title} ${body}`);
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
  {
    title,
    description,
    materialLink,
    materialType,
    file,
    departmentId,
    departmentName,
    academicYear,
    sectionName,
    videoUrl,
    videoTitle
  }
) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Sign in required.');
  assertCleanText(`${title} ${description} ${videoTitle || ''}`);
  const tRes = validateBoundedText(title, 'Course title', 3, 100);
  if (!tRes.valid) throw new Error(tRes.reason);
  const dRes = validateBoundedText(description, 'Description', 1, 600);
  if (!dRes.valid) throw new Error(dRes.reason);
  const lRes = validateOptionalHttpsUrl(materialLink);
  if (!lRes.valid) throw new Error(lRes.reason);
  const vRes = validateOptionalHttpsUrl(videoUrl);
  if (!vRes.valid) throw new Error(vRes.reason);

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
  if (vRes.value) payload.videoUrl = vRes.value;
  if (videoTitle && sanitizeText(videoTitle)) {
    payload.videoTitle = sanitizeText(videoTitle).slice(0, 120);
  }
  if (departmentId && isValidId(departmentId)) payload.departmentId = departmentId;
  if (departmentName && sanitizeText(departmentName).length >= 2) {
    payload.departmentName = sanitizeText(departmentName).slice(0, 80);
  }
  if (academicYear && sanitizeText(academicYear)) {
    payload.academicYear = sanitizeText(academicYear).slice(0, 40);
  }
  if (sectionName && sanitizeText(sectionName)) {
    payload.sectionName = sanitizeText(sectionName).slice(0, 40);
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

export async function updateCourseDetails(
  db,
  { courseId, title, description, departmentId, departmentName, academicYear, sectionName, videoUrl, videoTitle }
) {
  assertCleanText(`${title} ${description} ${videoTitle || ''}`);
  const tRes = validateBoundedText(title, 'Course title', 3, 100);
  if (!tRes.valid) throw new Error(tRes.reason);
  const dRes = validateBoundedText(description, 'Description', 1, 600);
  if (!dRes.valid) throw new Error(dRes.reason);
  const vRes = validateOptionalHttpsUrl(videoUrl);
  if (!vRes.valid) throw new Error(vRes.reason);

  const updates = {
    title: tRes.value,
    description: dRes.value,
    departmentId: departmentId && isValidId(departmentId) ? departmentId : deleteField(),
    departmentName:
      departmentName && sanitizeText(departmentName).length >= 2
        ? sanitizeText(departmentName).slice(0, 80)
        : deleteField(),
    academicYear: academicYear && sanitizeText(academicYear) ? sanitizeText(academicYear).slice(0, 40) : deleteField(),
    sectionName: sectionName && sanitizeText(sectionName) ? sanitizeText(sectionName).slice(0, 40) : deleteField(),
    videoUrl: vRes.value ? vRes.value : deleteField(),
    videoTitle: videoTitle && sanitizeText(videoTitle) ? sanitizeText(videoTitle).slice(0, 120) : deleteField(),
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
  assertCleanText(`${name} ${(years || []).join(' ')}`);
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
  assertCleanText(title);
  const tRes = validateBoundedText(title, 'Storage source title', 2, 100);
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
    `Configured study files storage source '${tRes.value}'.`
  );
  await sendNotification(db, auth, {
    title: tRes.value,
    body: `${addedByName} added new study materials to ${tRes.value}.`,
    category: 'COURSE',
    targetUid: 'ALL'
  });
}

export async function updateDriveFolder(
  db,
  auth,
  { existingFolderId, title, folderUrl, departmentId, departmentName, academicYear }
) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  assertCleanText(title);
  const tRes = validateBoundedText(title, 'Storage source title', 2, 100);
  if (!tRes.valid) throw new Error(tRes.reason);
  const dRes = extractGoogleDriveFolder(folderUrl);
  if (!dRes.valid) throw new Error(dRes.reason);

  if (dRes.folderId === existingFolderId) {
    const updates = {
      title: tRes.value,
      folderUrl: dRes.canonicalUrl,
      departmentId: departmentId && isValidId(departmentId) ? departmentId : deleteField(),
      departmentName:
        departmentName && sanitizeText(departmentName).length >= 2
          ? sanitizeText(departmentName).slice(0, 80)
          : deleteField(),
      academicYear:
        academicYear && sanitizeText(academicYear) ? sanitizeText(academicYear).slice(0, 40) : deleteField(),
      updatedAt: serverTimestamp()
    };
    await updateDoc(doc(db, 'drive_folders', existingFolderId), updates);
    await logAuditEvent(
      db,
      auth,
      'DRIVE_FOLDER_LINKED',
      'INFO',
      `drive_folders/${existingFolderId}`,
      `Super Admin updated storage source '${tRes.value}'.`
    );
  } else {
    let addedByName = sanitizeText(auth.currentUser?.displayName || 'Super Admin').slice(0, 60);
    if (addedByName.length < 2) addedByName = 'Super Admin';
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
    if (existingFolderId) {
      await deleteDoc(doc(db, 'drive_folders', existingFolderId)).catch(() => {});
    }
    await logAuditEvent(
      db,
      auth,
      'DRIVE_FOLDER_LINKED',
      'INFO',
      `drive_folders/${dRes.folderId}`,
      `Super Admin replaced storage source '${existingFolderId}' with '${tRes.value}'.`
    );
  }
}

export async function unlinkDriveFolder(db, folderId, auth = null) {
  await deleteDoc(doc(db, 'drive_folders', folderId));
  if (auth) {
    await logAuditEvent(
      db,
      auth,
      'DRIVE_FOLDER_LINKED',
      'INFO',
      `drive_folders/${folderId}`,
      `Super Admin removed storage source ${folderId}.`
    );
  }
}

// --- Quizzes ---

export async function createQuizWithAnswerKey(
  db,
  auth,
  { courseId, title, durationMinutes, startAtMillis = 0, endAtMillis = 0, imageFile = null, prompts, optionBlocks, correctIndices }
) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  if (!courseId) throw new Error('Select a course first.');
  assertCleanText(title);
  const tRes = validateBoundedText(title, 'Quiz title', 3, 100);
  if (!tRes.valid) throw new Error(tRes.reason);

  const cleanPrompts = [];
  for (const p of prompts || []) {
    assertCleanText(p);
    const r = validateBoundedText(p, 'Question', 3, 250);
    if (!r.valid) throw new Error(r.reason);
    cleanPrompts.push(r.value);
  }
  if (cleanPrompts.length === 0 || cleanPrompts.length !== optionBlocks.length || cleanPrompts.length !== correctIndices.length) {
    throw new Error('Provide at least 1 complete question.');
  }

  const cleanOptionBlocks = optionBlocks.map((block) => {
    assertCleanText(block);
    const opts = String(block)
      .split('|')
      .map((s) => sanitizeText(s).slice(0, 80))
      .filter(Boolean);
    if (opts.length < 2) throw new Error('Each question needs at least 2 options.');
    if (opts.length > 6) throw new Error('Maximum 6 options per question.');
    return opts.join('|');
  });

  const safeCorrect = correctIndices.map((idx, i) => {
    const count = cleanOptionBlocks[i].split('|').length;
    return Math.max(0, Math.min(Number(idx) || 0, count - 1));
  });

  const startMs = Math.max(0, Number(startAtMillis) || 0);
  const endMs = Math.max(0, Number(endAtMillis) || 0);
  if (startMs > 0 && endMs > 0 && endMs <= startMs) {
    throw new Error('Quiz closing time must be after opening time.');
  }

  let imgMeta = null;
  const imgChunks = [];
  if (imageFile) {
    const compressed = await compressImageFileToBase64(imageFile, 1_500_000, 1400);
    const chunkLen = 450_000;
    for (let pos = 0; pos < compressed.base64.length; pos += chunkLen) {
      imgChunks.push(compressed.base64.slice(pos, pos + chunkLen));
    }
    if (imgChunks.length > 4) {
      throw new Error('Quiz image exceeds maximum chunk size.');
    }
    imgMeta = compressed;
  }

  const mins = Math.max(1, Math.min(180, Number(durationMinutes) || 15));
  const quizRef = doc(collection(db, 'quizzes'));

  const payload = {
    courseId,
    title: tRes.value,
    durationMinutes: mins,
    questionPrompts: cleanPrompts,
    optionBlocks: cleanOptionBlocks,
    hasImage: Boolean(imgMeta),
    published: !imgMeta,
    createdBy: uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  };
  if (startMs > 0) payload.startAtMillis = startMs;
  if (endMs > 0) payload.endAtMillis = endMs;
  if (imgMeta) {
    payload.imageName = imgMeta.fileName;
    payload.imageMimeType = imgMeta.mimeType;
    payload.chunkCount = imgChunks.length;
  }

  await setDoc(quizRef, payload);

  try {
    if (imgMeta) {
      for (let i = 0; i < imgChunks.length; i++) {
        await setDoc(doc(db, 'quizzes', quizRef.id, 'assets', String(i)), {
          chunkIndex: i,
          data: imgChunks[i],
          createdAt: serverTimestamp()
        });
      }
      await updateDoc(quizRef, { published: true, updatedAt: serverTimestamp() });
    }

    await setDoc(doc(db, 'quizzes', quizRef.id, 'private', 'answer_key'), {
      quizId: quizRef.id,
      correctIndices: safeCorrect,
      passingScore: 70,
      updatedBy: uid,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
  } catch (err) {
    await deleteQuiz(db, quizRef.id).catch(() => {});
    throw err;
  }

  await incrementDailyCounter(db, uid, false);
  await sendNotification(db, auth, {
    title: tRes.value,
    body: `New ${cleanPrompts.length}-question quiz published (${mins} min).`,
    category: 'QUIZ',
    targetUid: 'ALL'
  });
}

export async function loadQuizImageDataUrl(db, quiz) {
  if (!quiz?.hasImage || !quiz?.chunkCount) return null;
  const snap = await getDocs(collection(db, 'quizzes', quiz.id, 'assets'));
  const ordered = snap.docs
    .map((d) => ({ idx: Number(d.data().chunkIndex ?? d.id), data: d.data().data }))
    .filter((x) => !Number.isNaN(x.idx) && typeof x.data === 'string')
    .sort((a, b) => a.idx - b.idx);
  if (ordered.length === 0) return null;
  const base64 = ordered.map((x) => x.data).join('');
  return `data:${quiz.imageMimeType || 'image/jpeg'};base64,${base64}`;
}

export async function deleteQuiz(db, quizId) {
  try {
    await deleteDoc(doc(db, 'quizzes', quizId, 'private', 'answer_key'));
  } catch {
    // Ignore if already absent
  }
  try {
    const assetsSnap = await getDocs(collection(db, 'quizzes', quizId, 'assets'));
    await Promise.all(assetsSnap.docs.map((d) => deleteDoc(d.ref)));
  } catch {
    // Ignore
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

export async function cancelQuizForViolation(db, auth, { quiz, selectedIndices, reason }) {
  const uid = auth.currentUser?.uid;
  if (!uid) return;
  const attemptId = `${uid}_${quiz.id}`;
  let studentName = sanitizeText(auth.currentUser?.displayName || 'Student').slice(0, 60);
  if (studentName.length < 2) studentName = 'Student';
  const cleanReason = sanitizeText(reason || 'Left the application during an active quiz.').slice(0, 200);
  const safeIndices =
    Array.isArray(selectedIndices) && selectedIndices.length > 0
      ? selectedIndices.map((n) => Number(n) || 0)
      : [0];

  await setDoc(doc(db, 'quiz_attempts', attemptId), {
    userId: uid,
    studentName,
    quizId: quiz.id,
    quizTitle: String(quiz.title || 'Quiz').slice(0, 100),
    selectedIndices: safeIndices,
    status: 'CANCELLED_VIOLATION',
    score: 0,
    graded: true,
    cancellationReason: cleanReason.length >= 3 ? cleanReason : 'Left app during quiz',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });

  await logAuditEvent(
    db,
    auth,
    'QUIZ_VIOLATION_CANCELLED',
    'HIGH',
    `quiz_attempts/${attemptId}`,
    `Quiz '${quiz.title}' cancelled for violation: ${cleanReason}`
  );
}

export async function resetCancelledAttemptAsSuperAdmin(db, auth, attempt) {
  await deleteDoc(doc(db, 'quiz_attempts', attempt.id));
  await logAuditEvent(
    db,
    auth,
    'QUIZ_VIOLATION_CANCELLED',
    'INFO',
    `quiz_attempts/${attempt.id}`,
    `Super Admin unlocked cancelled attempt for ${attempt.studentName}.`
  );
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

export async function deleteVerifiedVaultFile(db, auth, fileId) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  await deleteDoc(doc(db, 'users', uid, 'files', fileId));
}

// --- Accounts, Invites, Support, Chat, Settings ---

export async function inviteAccountByEmail(db, auth, { rawEmail, rawNameHint, assignedRole }) {
  const email = String(rawEmail || '').trim().toLowerCase();
  if (email.length < 3 || email.length > 120 || !EMAIL_REGEX.test(email)) {
    throw new Error('Enter a valid Google email address.');
  }
  const normalizedRole = normalizeUserRole(assignedRole);
  assertCleanText(rawNameHint);
  const nRes = validateBoundedText(rawNameHint, 'Name', 2, 60);
  if (!nRes.valid) throw new Error(nRes.reason);

  const uid = auth.currentUser?.uid;
  let inviterLabel = sanitizeText(auth.currentUser?.displayName || auth.currentUser?.email || uid).slice(0, 80);
  if (inviterLabel.length < 2) inviterLabel = 'Admin';

  await setDoc(doc(db, 'role_invites', email), {
    email,
    displayNameHint: nRes.value,
    assignedRole: normalizedRole,
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
    normalizedRole === 'SUPER_ADMIN' ? 'HIGH' : 'INFO',
    `role_invites/${email}`,
    `Pre-registered ${normalizedRole} account invite.`
  );
}

export async function revokeInvite(db, auth, emailKey) {
  await deleteDoc(doc(db, 'role_invites', emailKey));
  await logAuditEvent(db, auth, 'ROLE_CHANGED', 'INFO', `role_invites/${emailKey}`, 'Removed account invite.');
}

async function resolveUserEmailForPrivilege(db, cleanUid) {
  let piiEmail = `${cleanUid.slice(0, 12)}@unicore.edu`;
  try {
    const piiSnap = await getDoc(doc(db, 'users', cleanUid, 'private', 'pii'));
    const found = piiSnap.data()?.email;
    if (found && found.length >= 3 && found.length <= 120) piiEmail = found;
  } catch {
    // Fallback used
  }
  return piiEmail;
}

export async function promoteUserToAdmin(db, auth, targetUser) {
  const uid = auth.currentUser?.uid;
  const cleanUid = targetUser.userId;
  const piiEmail = await resolveUserEmailForPrivilege(db, cleanUid);

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

export async function promoteUserToSuperAdmin(db, auth, targetUser) {
  const cleanUid = targetUser.userId;
  const piiEmail = await resolveUserEmailForPrivilege(db, cleanUid);

  await setDoc(doc(db, 'super_admins', cleanUid), {
    userId: cleanUid,
    email: piiEmail,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });

  await deleteDoc(doc(db, 'admins', cleanUid)).catch(() => {});

  await updateDoc(doc(db, 'users', cleanUid), {
    role: 'SUPER_ADMIN',
    updatedAt: serverTimestamp()
  });

  await logAuditEvent(
    db,
    auth,
    'ROLE_CHANGED',
    'CRITICAL',
    `super_admins/${cleanUid}`,
    `Super Admin promoted ${targetUser.displayName} to SUPER_ADMIN.`
  );
  await sendNotification(db, auth, {
    title: 'Super Admin Access Granted',
    body: 'Your UniCore account has been promoted to Super Admin.',
    category: 'ROLE',
    targetUid: cleanUid
  });
}

export async function demoteSuperAdminToAdmin(db, auth, targetUid) {
  const uid = auth.currentUser?.uid;
  if (targetUid === uid) {
    throw new Error('You cannot demote your own Super Admin account.');
  }
  const piiEmail = await resolveUserEmailForPrivilege(db, targetUid);
  await deleteDoc(doc(db, 'super_admins', targetUid));
  await setDoc(doc(db, 'admins', targetUid), {
    userId: targetUid,
    email: piiEmail,
    grantedBy: uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  });
  await updateDoc(doc(db, 'users', targetUid), {
    role: 'ADMIN',
    updatedAt: serverTimestamp()
  }).catch(() => {});

  await logAuditEvent(
    db,
    auth,
    'ROLE_CHANGED',
    'HIGH',
    `super_admins/${targetUid}`,
    `Super Admin changed role of ${targetUid} to ADMIN.`
  );
  await sendNotification(db, auth, {
    title: 'Role Updated',
    body: 'Your UniCore role has been updated to Admin.',
    category: 'ROLE',
    targetUid
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

export async function setUserTimedBan(db, auth, targetUser, durationMinutes, reason) {
  const actorUid = auth.currentUser?.uid;
  if (targetUser.userId === actorUid) {
    throw new Error('You cannot ban your own account.');
  }
  assertCleanText(reason || '');
  const cleanReason = sanitizeText(reason || '').slice(0, 200);
  const mins = Math.max(0, Number(durationMinutes) || 0);
  const banUntilMillis = mins > 0 ? Date.now() + mins * 60_000 : 0;

  await updateDoc(doc(db, 'users', targetUser.userId), {
    banUntilMillis,
    banReason: mins > 0 ? (cleanReason || 'مخالفة قواعد الاستخدام') : '',
    updatedAt: serverTimestamp()
  });

  await logAuditEvent(
    db,
    auth,
    'ACCOUNT_BANNED',
    mins > 0 ? 'HIGH' : 'INFO',
    `users/${targetUser.userId}`,
    mins > 0
      ? `Banned ${targetUser.displayName} for ${mins} minutes (${cleanReason || 'policy violation'}).`
      : `Lifted temporary ban for ${targetUser.displayName}.`
  );
}

export async function sendSupportMessage(db, auth, { userRole, subject, body, imageFile = null }) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  assertCleanText(`${subject} ${body}`);
  const sRes = validateBoundedText(subject, 'Subject', 2, 100);
  if (!sRes.valid) throw new Error(sRes.reason);
  const bRes = validateBoundedText(body, 'Message', 2, 800);
  if (!bRes.valid) throw new Error(bRes.reason);

  const channel = userRole === 'ADMIN' ? 'ADMIN_TO_SUPER' : 'STUDENT_TO_ADMIN';
  const senderRole = userRole === 'ADMIN' ? 'ADMIN' : 'STUDENT';
  let senderName = sanitizeText(auth.currentUser?.displayName || 'UniCore User').slice(0, 60);
  if (senderName.length < 2) senderName = 'User';

  let imgPayload = {};
  if (imageFile) {
    const compressed = await compressImageFileToBase64(imageFile, 640_000, 1280);
    imgPayload = {
      attachmentBase64: compressed.base64,
      attachmentMimeType: compressed.mimeType,
      attachmentName: compressed.fileName
    };
  }

  const msgId = makeShortId('msg');
  await setDoc(doc(db, 'support_messages', msgId), {
    channel,
    senderUid: uid,
    senderName,
    senderRole,
    subject: sRes.value,
    body: bRes.value,
    ...imgPayload,
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

export async function replyToSupportMessage(db, auth, { message, replyText, imageFile = null }) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  assertCleanText(replyText);
  const rRes = validateBoundedText(replyText, 'Reply', 1, 800);
  if (!rRes.valid) throw new Error(rRes.reason);

  let replierName = sanitizeText(auth.currentUser?.displayName || 'Administrator').slice(0, 60);
  if (replierName.length < 2) replierName = 'Admin';

  let imgPayload = {};
  if (imageFile) {
    const compressed = await compressImageFileToBase64(imageFile, 640_000, 1280);
    imgPayload = {
      replyAttachmentBase64: compressed.base64,
      replyAttachmentMimeType: compressed.mimeType,
      replyAttachmentName: compressed.fileName
    };
  }

  await updateDoc(doc(db, 'support_messages', message.id), {
    status: 'REPLIED',
    replyText: rRes.value,
    ...imgPayload,
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

// --- Student & Department Group Chat ---

export function buildChatRoomId(departmentId, yearIndex) {
  if (!departmentId || yearIndex === null || yearIndex === undefined) return 'general';
  return `dept_${departmentId}_y_${yearIndex}`;
}

export function subscribeChatMessages(db, roomId, onData, onError) {
  const q = query(
    collection(db, 'chat_rooms', roomId, 'messages'),
    orderBy('createdAt', 'desc'),
    limit(80)
  );
  return onSnapshot(
    q,
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .reverse();
      onData(list);
    },
    onError
  );
}

export async function sendChatMessage(
  db,
  auth,
  { roomId, departmentName = '', academicYear = '', userRole, text, imageFile = null }
) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  assertCleanText(text);
  const cleanText = sanitizeText(text || '').slice(0, 800);
  if (!cleanText && !imageFile) {
    throw new Error('Enter a message or attach an image.');
  }

  let senderName = sanitizeText(auth.currentUser?.displayName || 'UniCore User').slice(0, 60);
  if (senderName.length < 2) senderName = 'User';

  const payload = {
    roomId: isValidId(roomId) ? roomId : 'general',
    senderUid: uid,
    senderName,
    senderRole: ['STUDENT', 'ADMIN', 'SUPER_ADMIN'].includes(userRole) ? userRole : 'STUDENT',
    text: cleanText,
    createdAt: serverTimestamp()
  };

  if (departmentName) payload.departmentName = sanitizeText(departmentName).slice(0, 80);
  if (academicYear) payload.academicYear = sanitizeText(academicYear).slice(0, 40);

  if (imageFile) {
    const compressed = await compressImageFileToBase64(imageFile, 640_000, 1280);
    payload.attachmentBase64 = compressed.base64;
    payload.attachmentMimeType = compressed.mimeType;
    payload.attachmentName = compressed.fileName;
  }

  const msgId = makeShortId('chat');
  await setDoc(doc(db, 'chat_rooms', payload.roomId, 'messages', msgId), payload);
}

export async function deleteChatMessage(db, auth, roomId, messageId) {
  await deleteDoc(doc(db, 'chat_rooms', roomId, 'messages', messageId));
  await logAuditEvent(
    db,
    auth,
    'CHAT_MESSAGE_DELETED',
    'INFO',
    `chat_rooms/${roomId}/messages/${messageId}`,
    'Deleted chat message.'
  );
}

export async function updateSystemSettingsAsSuperAdmin(
  db,
  auth,
  { dailyAiLimit, dailyWriteLimit, aiEnabled, zeroCostLockActive, tutorialVideoUrl = '', tutorialVideoTitle = '' }
) {
  const uid = auth.currentUser?.uid;
  const aiCap = Math.max(1, Math.min(100, Number(dailyAiLimit) || 20));
  const writeCap = Math.max(10, Math.min(500, Number(dailyWriteLimit) || 100));
  const tutUrlCheck = validateOptionalHttpsUrl(tutorialVideoUrl);
  if (!tutUrlCheck.valid) throw new Error(tutUrlCheck.reason);
  const cleanTitle = sanitizeText(tutorialVideoTitle || '').slice(0, 120);

  await updateDoc(doc(db, 'system_settings', 'global'), {
    dailyAiLimitPerUser: aiCap,
    dailyWriteLimitPerUser: writeCap,
    aiEnabled: Boolean(aiEnabled),
    zeroCostLockActive: Boolean(zeroCostLockActive),
    tutorialVideoUrl: tutUrlCheck.value ? tutUrlCheck.value : deleteField(),
    tutorialVideoTitle: cleanTitle ? cleanTitle : deleteField(),
    updatedBy: uid,
    updatedAt: serverTimestamp()
  });

  await logAuditEvent(
    db,
    auth,
    'SETTINGS_CHANGED',
    'HIGH',
    'system_settings/global',
    `Super Admin updated settings: aiLimit=${aiCap}, aiEnabled=${Boolean(aiEnabled)}, lock=${Boolean(zeroCostLockActive)}`
  );
}

// --- Academic Placement, Favorites & Assignments Progress ---

export async function saveUserAcademicPlacement(
  db,
  auth,
  { departmentId, departmentName, academicYear, sectionName }
) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  assertCleanText(`${departmentName || ''} ${academicYear || ''} ${sectionName || ''}`);
  const yRes = validateBoundedText(academicYear, 'Academic Year', 1, 40);
  if (!yRes.valid) throw new Error(yRes.reason);
  const sRes = validateBoundedText(sectionName, 'Section', 1, 40);
  if (!sRes.valid) throw new Error(sRes.reason);
  const cleanDeptName = sanitizeText(departmentName || 'General').slice(0, 80) || 'General';

  await updateDoc(doc(db, 'users', uid), {
    departmentId: departmentId && isValidId(departmentId) ? departmentId : deleteField(),
    departmentName: cleanDeptName,
    academicYear: yRes.value,
    sectionName: sRes.value,
    placementCompleted: true,
    updatedAt: serverTimestamp()
  });
}

export async function toggleFavoriteCourse(db, auth, courseId, currentFavoriteIds = []) {
  const uid = auth.currentUser?.uid;
  if (!uid || !courseId) return;
  const set = new Set(Array.isArray(currentFavoriteIds) ? currentFavoriteIds : []);
  if (set.has(courseId)) set.delete(courseId);
  else set.add(courseId);
  const next = Array.from(set).filter((id) => isValidId(id)).slice(0, 100);
  await updateDoc(doc(db, 'users', uid), {
    favoriteCourseIds: next,
    updatedAt: serverTimestamp()
  });
}

export async function toggleAssignmentCompleted(db, auth, assignmentId, currentCompletedIds = []) {
  const uid = auth.currentUser?.uid;
  if (!uid || !assignmentId) return;
  const set = new Set(Array.isArray(currentCompletedIds) ? currentCompletedIds : []);
  if (set.has(assignmentId)) set.delete(assignmentId);
  else set.add(assignmentId);
  const next = Array.from(set).filter((id) => isValidId(id)).slice(0, 200);
  await updateDoc(doc(db, 'users', uid), {
    completedAssignmentIds: next,
    updatedAt: serverTimestamp()
  });
}

// --- Assignments & Google Classroom ("اللي عليا") ---

export function subscribeAssignments(db, uid, isAdmin, onData, onError) {
  // CRITICAL: Student queries must NEVER add where('published', '==', true) because personal CLASSROOM docs check ownerUid == uid
  const q = isAdmin
    ? collection(db, 'assignments')
    : query(collection(db, 'assignments'), where('ownerUid', 'in', ['ALL', uid]));
  return onSnapshot(
    q,
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .filter((a) => isAdmin || a.published !== false || a.ownerUid === uid)
        .sort((a, b) => (Number(a.dueAtMillis) || 0) - (Number(b.dueAtMillis) || 0));
      onData(list);
    },
    onError
  );
}

export async function createAssignment(
  db,
  auth,
  {
    title,
    description,
    courseTitle = '',
    departmentId = null,
    departmentName = null,
    academicYear = null,
    sectionName = null,
    dueAtMillis,
    classroomLink = '',
    videoUrl = '',
    sourceType = 'APP',
    ownerUid = 'ALL'
  }
) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  assertCleanText(`${title} ${description} ${courseTitle}`);
  const tRes = validateBoundedText(title, 'Assignment title', 2, 120);
  if (!tRes.valid) throw new Error(tRes.reason);
  const dRes = validateBoundedText(description || title, 'Assignment details', 1, 600);
  if (!dRes.valid) throw new Error(dRes.reason);
  const cRes = validateOptionalHttpsUrl(classroomLink);
  if (!cRes.valid) throw new Error(cRes.reason);
  const vRes = validateOptionalHttpsUrl(videoUrl);
  if (!vRes.valid) throw new Error(vRes.reason);

  const dueMs = Number(dueAtMillis) || Date.now() + 2 * 24 * 60 * 60 * 1000;
  let senderName = sanitizeText(auth.currentUser?.displayName || 'UniCore User').slice(0, 60);
  if (senderName.length < 2) senderName = 'User';

  const docRef = doc(collection(db, 'assignments'));
  const payload = {
    title: tRes.value,
    description: dRes.value,
    dueAtMillis: dueMs,
    sourceType: sourceType === 'CLASSROOM' ? 'CLASSROOM' : 'APP',
    ownerUid: ownerUid === 'ALL' ? 'ALL' : uid,
    published: true,
    createdBy: uid,
    createdByName: senderName,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  };

  if (courseTitle && sanitizeText(courseTitle)) payload.courseTitle = sanitizeText(courseTitle).slice(0, 100);
  if (cRes.value) payload.classroomLink = cRes.value;
  if (vRes.value) payload.videoUrl = vRes.value;
  if (departmentId && isValidId(departmentId)) payload.departmentId = departmentId;
  if (departmentName && sanitizeText(departmentName).length >= 2) {
    payload.departmentName = sanitizeText(departmentName).slice(0, 80);
  }
  if (academicYear && sanitizeText(academicYear)) {
    payload.academicYear = sanitizeText(academicYear).slice(0, 40);
  }
  if (sectionName && sanitizeText(sectionName)) {
    payload.sectionName = sanitizeText(sectionName).slice(0, 40);
  }

  await setDoc(docRef, payload);

  if (payload.ownerUid === 'ALL') {
    await sendNotification(db, auth, {
      title: `أسينمنت جديد: ${tRes.value}`,
      body: `${courseTitle ? `${courseTitle} • ` : ''}الموعد: ${new Date(dueMs).toLocaleString()}`,
      category: 'ASSIGNMENT',
      targetUid: 'ALL'
    });
  }
  return docRef.id;
}

export async function importClassroomAssignments(db, auth, items, userProfile) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  let count = 0;

  for (const item of items || []) {
    const safeExt = String(item.externalId || `${Date.now()}_${count}`)
      .replace(/[^a-zA-Z0-9_-]/g, '_')
      .slice(0, 80);
    const docId = `${uid.slice(0, 24)}_${safeExt}`.slice(0, 120);
    const cleanTitle = sanitizeText(item.title || 'Classroom Task').slice(0, 120) || 'Classroom Task';
    const cleanDesc = sanitizeText(item.description || cleanTitle).slice(0, 600) || cleanTitle;
    const cleanCourse = sanitizeText(item.courseTitle || 'Google Classroom').slice(0, 100);
    const cRes = validateOptionalHttpsUrl(item.classroomLink || '');

    const payload = {
      title: cleanTitle,
      description: cleanDesc,
      courseTitle: cleanCourse,
      dueAtMillis: Number(item.dueAtMillis) || Date.now() + 7 * 24 * 60 * 60 * 1000,
      sourceType: 'CLASSROOM',
      ownerUid: uid,
      published: true,
      createdBy: uid,
      createdByName: sanitizeText(auth.currentUser?.displayName || 'Student').slice(0, 60) || 'Student',
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    };
    if (cRes.valid && cRes.value) payload.classroomLink = cRes.value;
    if (userProfile?.departmentId && isValidId(userProfile.departmentId)) {
      payload.departmentId = userProfile.departmentId;
    }
    if (userProfile?.departmentName) payload.departmentName = sanitizeText(userProfile.departmentName).slice(0, 80);
    if (userProfile?.academicYear) payload.academicYear = sanitizeText(userProfile.academicYear).slice(0, 40);
    if (userProfile?.sectionName) payload.sectionName = sanitizeText(userProfile.sectionName).slice(0, 40);

    await setDoc(doc(db, 'assignments', docId), payload, { merge: true });
    count += 1;
  }

  await updateDoc(doc(db, 'users', uid), {
    classroomConnected: true,
    updatedAt: serverTimestamp()
  }).catch(() => {});

  return count;
}

export async function deleteAssignment(db, assignmentId) {
  await deleteDoc(doc(db, 'assignments', assignmentId));
}

// --- Per-Section Weekly Schedule ---

export function subscribeScheduleItems(db, isAdmin, onData, onError) {
  const q = isAdmin
    ? collection(db, 'schedule_items')
    : query(collection(db, 'schedule_items'), where('published', '==', true));
  return onSnapshot(
    q,
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .sort((a, b) => (Number(a.dayOfWeek) || 1) - (Number(b.dayOfWeek) || 1));
      onData(list);
    },
    onError
  );
}

export async function createScheduleItem(
  db,
  auth,
  {
    title,
    itemType = 'LECTURE',
    dayOfWeek = 1,
    startTimeText,
    endTimeText = '',
    locationOrMode,
    instructorName = '',
    departmentId = null,
    departmentName = null,
    academicYear = null,
    sectionName = null,
    videoUrl = ''
  }
) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  assertCleanText(`${title} ${locationOrMode} ${instructorName}`);
  const tRes = validateBoundedText(title, 'Title', 2, 100);
  if (!tRes.valid) throw new Error(tRes.reason);
  const sRes = validateBoundedText(startTimeText, 'Start time', 1, 30);
  if (!sRes.valid) throw new Error(sRes.reason);
  const locRes = validateBoundedText(locationOrMode, 'Location / Hall', 1, 80);
  if (!locRes.valid) throw new Error(locRes.reason);
  const vRes = validateOptionalHttpsUrl(videoUrl);
  if (!vRes.valid) throw new Error(vRes.reason);

  const payload = {
    title: tRes.value,
    itemType: ['LECTURE', 'SECTION', 'ASSIGNMENT', 'EXAM'].includes(itemType) ? itemType : 'LECTURE',
    dayOfWeek: Math.max(1, Math.min(7, Number(dayOfWeek) || 1)),
    startTimeText: sRes.value,
    endTimeText: sanitizeText(endTimeText || '').slice(0, 30),
    locationOrMode: locRes.value,
    published: true,
    createdBy: uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  };
  if (instructorName && sanitizeText(instructorName)) {
    payload.instructorName = sanitizeText(instructorName).slice(0, 60);
  }
  if (departmentId && isValidId(departmentId)) payload.departmentId = departmentId;
  if (departmentName && sanitizeText(departmentName).length >= 2) {
    payload.departmentName = sanitizeText(departmentName).slice(0, 80);
  }
  if (academicYear && sanitizeText(academicYear)) {
    payload.academicYear = sanitizeText(academicYear).slice(0, 40);
  }
  if (sectionName && sanitizeText(sectionName)) {
    payload.sectionName = sanitizeText(sectionName).slice(0, 40);
  }
  if (vRes.value) payload.videoUrl = vRes.value;

  const docRef = doc(collection(db, 'schedule_items'));
  await setDoc(docRef, payload);
}

export async function deleteScheduleItem(db, id) {
  await deleteDoc(doc(db, 'schedule_items', id));
}

// --- Live Zoom Sessions ---

export function subscribeZoomSessions(db, isAdmin, onData, onError) {
  const q = isAdmin
    ? collection(db, 'zoom_sessions')
    : query(collection(db, 'zoom_sessions'), where('published', '==', true));
  return onSnapshot(
    q,
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .sort((a, b) => (Number(a.scheduledAtMillis) || 0) - (Number(b.scheduledAtMillis) || 0));
      onData(list);
    },
    onError
  );
}

export async function createZoomSession(
  db,
  auth,
  {
    title,
    topic = '',
    zoomUrl,
    scheduledAtMillis,
    durationMinutes = 60,
    departmentId = null,
    departmentName = null,
    academicYear = null,
    sectionName = null
  }
) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  assertCleanText(`${title} ${topic}`);
  const tRes = validateBoundedText(title, 'Session title', 2, 120);
  if (!tRes.valid) throw new Error(tRes.reason);
  const urlRes = validateOptionalHttpsUrl(zoomUrl);
  if (!urlRes.valid || !urlRes.value) throw new Error('Enter a valid HTTPS Zoom link.');

  const whenMs = Number(scheduledAtMillis) || Date.now();
  const dur = Math.max(10, Math.min(360, Number(durationMinutes) || 60));
  let hostName = sanitizeText(auth.currentUser?.displayName || 'Instructor').slice(0, 60);
  if (hostName.length < 2) hostName = 'Host';

  const payload = {
    title: tRes.value,
    zoomUrl: urlRes.value,
    scheduledAtMillis: whenMs,
    durationMinutes: dur,
    hostName,
    published: true,
    createdBy: uid,
    createdByName: hostName,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  };
  if (topic && sanitizeText(topic)) payload.topic = sanitizeText(topic).slice(0, 300);
  if (departmentId && isValidId(departmentId)) payload.departmentId = departmentId;
  if (departmentName && sanitizeText(departmentName).length >= 2) {
    payload.departmentName = sanitizeText(departmentName).slice(0, 80);
  }
  if (academicYear && sanitizeText(academicYear)) {
    payload.academicYear = sanitizeText(academicYear).slice(0, 40);
  }
  if (sectionName && sanitizeText(sectionName)) {
    payload.sectionName = sanitizeText(sectionName).slice(0, 40);
  }

  const docRef = doc(collection(db, 'zoom_sessions'));
  await setDoc(docRef, payload);

  await sendNotification(db, auth, {
    title: `محاضرة Zoom: ${tRes.value}`,
    body: `${hostName} • ${new Date(whenMs).toLocaleString()}`,
    category: 'ZOOM',
    targetUid: 'ALL'
  });
}

export async function deleteZoomSession(db, id) {
  await deleteDoc(doc(db, 'zoom_sessions', id));
}

// --- Instructions for Everyone ("تعليمات للكل") ---

export function subscribeInstructions(db, isAdmin, onData, onError) {
  const q = isAdmin
    ? collection(db, 'instructions')
    : query(collection(db, 'instructions'), where('published', '==', true));
  return onSnapshot(
    q,
    (snap) => {
      const rank = { PINNED: 0, IMPORTANT: 1, NORMAL: 2 };
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .sort(
          (a, b) =>
            (rank[a.priority] ?? 2) - (rank[b.priority] ?? 2) ||
            timestampSec(b.createdAt) - timestampSec(a.createdAt)
        );
      onData(list);
    },
    onError
  );
}

export async function createInstruction(
  db,
  auth,
  { title, body, priority = 'NORMAL', departmentId = null, departmentName = null, academicYear = null, sectionName = null }
) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  assertCleanText(`${title} ${body}`);
  const tRes = validateBoundedText(title, 'Instruction title', 2, 120);
  if (!tRes.valid) throw new Error(tRes.reason);
  const bRes = validateBoundedText(body, 'Instruction message', 2, 1000);
  if (!bRes.valid) throw new Error(bRes.reason);

  let createdByName = sanitizeText(auth.currentUser?.displayName || 'Admin').slice(0, 60);
  if (createdByName.length < 2) createdByName = 'Admin';

  const payload = {
    title: tRes.value,
    body: bRes.value,
    priority: ['NORMAL', 'IMPORTANT', 'PINNED'].includes(priority) ? priority : 'NORMAL',
    published: true,
    createdBy: uid,
    createdByName,
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
  if (sectionName && sanitizeText(sectionName)) {
    payload.sectionName = sanitizeText(sectionName).slice(0, 40);
  }

  await setDoc(doc(collection(db, 'instructions')), payload);
  await sendNotification(db, auth, {
    title: tRes.value,
    body: bRes.value.slice(0, 140),
    category: 'ANNOUNCEMENT',
    targetUid: 'ALL'
  });
}

export async function deleteInstruction(db, id) {
  await deleteDoc(doc(db, 'instructions', id));
}

// --- Section Messages ("مسدجات السكشن") ---

export function subscribeSectionMessages(db, onData, onError) {
  const q = query(collection(db, 'section_messages'), orderBy('createdAt', 'desc'), limit(120));
  return onSnapshot(
    q,
    (snap) => {
      const list = snap.docs
        .map((d) => ({ id: d.id, ...d.data() }))
        .sort((a, b) => Number(Boolean(b.isPinned)) - Number(Boolean(a.isPinned)));
      onData(list);
    },
    onError
  );
}

export async function sendSectionMessage(
  db,
  auth,
  { departmentId = null, departmentName = 'General', academicYear, sectionName, userRole, text, isPinned = false, videoUrl = '' }
) {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Not authenticated.');
  assertCleanText(text);
  const tRes = validateBoundedText(text, 'Section message', 1, 800);
  if (!tRes.valid) throw new Error(tRes.reason);
  const yRes = validateBoundedText(academicYear, 'Academic Year', 1, 40);
  if (!yRes.valid) throw new Error(yRes.reason);
  const sRes = validateBoundedText(sectionName, 'Section', 1, 40);
  if (!sRes.valid) throw new Error(sRes.reason);
  const vRes = validateOptionalHttpsUrl(videoUrl);
  if (!vRes.valid) throw new Error(vRes.reason);

  let senderName = sanitizeText(auth.currentUser?.displayName || 'UniCore User').slice(0, 60);
  if (senderName.length < 2) senderName = 'User';
  const role = normalizeUserRole(userRole);
  const isStaff = role !== 'STUDENT';

  const payload = {
    departmentName: sanitizeText(departmentName || 'General').slice(0, 80) || 'General',
    academicYear: yRes.value,
    sectionName: sRes.value,
    senderUid: uid,
    senderName,
    senderRole: role,
    messageType: isStaff ? 'NOTICE' : 'MESSAGE',
    isPinned: isStaff ? Boolean(isPinned) : false,
    text: tRes.value,
    createdAt: serverTimestamp()
  };
  if (departmentId && isValidId(departmentId)) payload.departmentId = departmentId;
  if (vRes.value) payload.videoUrl = vRes.value;

  await setDoc(doc(collection(db, 'section_messages')), payload);
}

export async function deleteSectionMessage(db, id) {
  await deleteDoc(doc(db, 'section_messages', id));
}
