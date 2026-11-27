const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const STUDENT_A = "student_alice";
const STUDENT_B = "student_bob";
const ADMIN_UID = "admin_carol";
const SUPER_ADMIN_UID = "super_dave";
const BOOTSTRAP_EMAIL = "fvathyvhmed@gmail.com";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(msOffset = 5000) {
  return new Date(Date.now() - msOffset);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

async function seedRolesAndBaseData() {
  const ts = pastDate(20000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("super_admins").doc(SUPER_ADMIN_UID).set({
      userId: SUPER_ADMIN_UID,
      email: BOOTSTRAP_EMAIL,
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("admins").doc(ADMIN_UID).set({
      userId: ADMIN_UID,
      email: "admin@unicore.edu",
      grantedBy: SUPER_ADMIN_UID,
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("users").doc(STUDENT_A).set({
      userId: STUDENT_A,
      displayName: "Alice Student",
      role: "STUDENT",
      aiCallsToday: 0,
      writesToday: 0,
      quotaDayKey: "2026-11-22",
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("users").doc(STUDENT_B).set({
      userId: STUDENT_B,
      displayName: "Bob Student",
      role: "STUDENT",
      aiCallsToday: 1,
      writesToday: 2,
      quotaDayKey: "2026-11-22",
      createdAt: ts,
      updatedAt: ts,
    });
    await db
      .collection("users")
      .doc(STUDENT_B)
      .collection("private")
      .doc("pii")
      .set({
        userId: STUDENT_B,
        email: "bob@unicore.edu",
        createdAt: ts,
        updatedAt: ts,
      });
    await db.collection("courses").doc("course_cs101").set({
      title: "Secure Software Engineering",
      description: "Foundations of Zero-Trust Architecture",
      instructorId: ADMIN_UID,
      instructorName: "Dr. Carol",
      materialLink: "https://onedrive.live.com/sample-notes",
      materialType: "ONEDRIVE_LINK",
      published: true,
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection("quizzes").doc("quiz_1").set({
      courseId: "course_cs101",
      title: "Zero-Trust Midterm",
      durationMinutes: 30,
      questionPrompts: ["What enforces server-side RBAC in Spark Firestore?"],
      optionBlocks: ["Client UI|Firestore Security Rules|Local SharedPrefs|CSS"],
      published: true,
      createdBy: ADMIN_UID,
      createdAt: ts,
      updatedAt: ts,
    });
    await db
      .collection("quizzes")
      .doc("quiz_1")
      .collection("private")
      .doc("answer_key")
      .set({
        quizId: "quiz_1",
        correctIndices: [1],
        passingScore: 70,
        updatedBy: ADMIN_UID,
        createdAt: ts,
        updatedAt: ts,
      });
    await db.collection("system_settings").doc("global").set({
      dailyAiLimitPerUser: 20,
      dailyWriteLimitPerUser: 100,
      maxFileUploadBytes: 5242880,
      aiEnabled: true,
      zeroCostLockActive: false,
      updatedBy: SUPER_ADMIN_UID,
      createdAt: ts,
      updatedAt: ts,
    });
  });
}

test("1. Unauthenticated requests are denied across all collections", async () => {
  await seedRolesAndBaseData();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("courses").where("published", "==", true).get());
  await assertFails(unauthDb.collection("quizzes").where("published", "==", true).get());
  await assertFails(unauthDb.collection("users").doc(STUDENT_A).get());
  await assertFails(unauthDb.collection("audit_logs").get());
  await assertFails(unauthDb.collection("system_settings").doc("global").get());
});

test("2. Student cannot self-assign ADMIN/SUPER_ADMIN or elevate role", async () => {
  const ts = pastDate();
  const studentDb = testEnv
    .authenticatedContext("new_student", { email: "hacker@evil.com", email_verified: true })
    .firestore();

  // Attempt to create profile with role = ADMIN
  await assertFails(
    studentDb.collection("users").doc("new_student").set({
      userId: "new_student",
      displayName: "Hacker",
      role: "ADMIN",
      aiCallsToday: 0,
      writesToday: 0,
      quotaDayKey: "2026-11-22",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Valid STUDENT profile creation succeeds
  await assertSucceeds(
    studentDb.collection("users").doc("new_student").set({
      userId: "new_student",
      displayName: "Legit Student",
      role: "STUDENT",
      aiCallsToday: 0,
      writesToday: 0,
      quotaDayKey: "2026-11-22",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Attempt to elevate own role via update fails
  await assertFails(
    studentDb.collection("users").doc("new_student").update({
      role: "SUPER_ADMIN",
      updatedAt: pastDate(1000),
    })
  );
});

test("3. Student cannot access another student's profile, PII, files, or attempts", async () => {
  await seedRolesAndBaseData();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();

  await assertSucceeds(aliceDb.collection("users").doc(STUDENT_A).get());
  await assertFails(aliceDb.collection("users").doc(STUDENT_B).get());
  await assertFails(aliceDb.collection("users").get());
  await assertFails(
    aliceDb.collection("users").doc(STUDENT_B).collection("private").doc("pii").get()
  );
  await assertFails(
    aliceDb.collection("users").doc(STUDENT_B).update({
      displayName: "Tampered",
      updatedAt: pastDate(1000),
    })
  );
});

test("4. Student cannot access Admin/SuperAdmin collections, system settings writes, or audit logs", async () => {
  await seedRolesAndBaseData();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();

  await assertFails(aliceDb.collection("admins").get());
  await assertFails(aliceDb.collection("super_admins").get());
  await assertFails(aliceDb.collection("audit_logs").get());
  await assertFails(
    aliceDb.collection("admins").doc(STUDENT_A).set({
      userId: STUDENT_A,
      email: "alice@unicore.edu",
      grantedBy: STUDENT_A,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
  await assertFails(
    aliceDb.collection("system_settings").doc("global").update({
      dailyAiLimitPerUser: 100,
      updatedBy: STUDENT_A,
      updatedAt: pastDate(),
    })
  );
  await assertFails(
    aliceDb.collection("courses").doc("fake_course").set({
      title: "Fake Course",
      description: "Unauthorized",
      instructorId: STUDENT_A,
      instructorName: "Alice",
      materialType: "NOTES",
      published: true,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});

test("5. Regular Admin CANNOT access SuperAdmin settings or promote/demote Admins", async () => {
  await seedRolesAndBaseData();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();

  // Admin can read courses, CANNOT read audit logs (SuperAdmin only)
  await assertSucceeds(adminDb.collection("courses").get());
  await assertFails(adminDb.collection("audit_logs").get());

  // Admin CANNOT read or write super_admins
  await assertFails(adminDb.collection("super_admins").get());
  await assertFails(
    adminDb.collection("super_admins").doc(ADMIN_UID).set({
      userId: ADMIN_UID,
      email: "admin@unicore.edu",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Admin CANNOT modify system_settings
  await assertFails(
    adminDb.collection("system_settings").doc("global").update({
      aiEnabled: false,
      updatedBy: ADMIN_UID,
      updatedAt: pastDate(),
    })
  );

  // Admin CANNOT grant admin privileges or elevate user roles
  await assertFails(
    adminDb.collection("admins").doc(STUDENT_A).set({
      userId: STUDENT_A,
      email: "alice@unicore.edu",
      grantedBy: ADMIN_UID,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
  await assertFails(
    adminDb.collection("users").doc(STUDENT_A).update({
      role: "ADMIN",
      updatedAt: pastDate(),
    })
  );
});

test("6. Email spoofing with email_verified=false fails Super Admin checks", async () => {
  const spoofDb = testEnv
    .authenticatedContext("spoof_uid", { email: BOOTSTRAP_EMAIL, email_verified: false })
    .firestore();

  await assertFails(
    spoofDb.collection("system_settings").doc("global").set({
      dailyAiLimitPerUser: 50,
      dailyWriteLimitPerUser: 200,
      maxFileUploadBytes: 5242880,
      aiEnabled: true,
      zeroCostLockActive: false,
      updatedBy: "spoof_uid",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});

test("7. Quiz answer keys are hidden from students; students cannot forge grades or duplicate attempts", async () => {
  await seedRolesAndBaseData();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();

  // Student CANNOT read secret answer key
  await assertFails(
    aliceDb.collection("quizzes").doc("quiz_1").collection("private").doc("answer_key").get()
  );

  // Admin CAN read secret answer key
  await assertSucceeds(
    adminDb.collection("quizzes").doc("quiz_1").collection("private").doc("answer_key").get()
  );

  // Student CANNOT forge score=100 or graded=true on create
  await assertFails(
    aliceDb.collection("quiz_attempts").doc(`${STUDENT_A}_quiz_1`).set({
      userId: STUDENT_A,
      studentName: "Alice Student",
      quizId: "quiz_1",
      quizTitle: "Zero-Trust Midterm",
      selectedIndices: [1],
      status: "GRADED",
      score: 100,
      graded: true,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Valid student submission succeeds with score=0, graded=false, status=SUBMITTED
  await assertSucceeds(
    aliceDb.collection("quiz_attempts").doc(`${STUDENT_A}_quiz_1`).set({
      userId: STUDENT_A,
      studentName: "Alice Student",
      quizId: "quiz_1",
      quizTitle: "Zero-Trust Midterm",
      selectedIndices: [1],
      status: "SUBMITTED",
      score: 0,
      graded: false,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Student CANNOT modify their own grade or answers after submission
  await assertFails(
    aliceDb.collection("quiz_attempts").doc(`${STUDENT_A}_quiz_1`).update({
      score: 100,
      graded: true,
      status: "GRADED",
      updatedAt: pastDate(500),
    })
  );

  // Admin grades the attempt cleanly
  await assertSucceeds(
    adminDb.collection("quiz_attempts").doc(`${STUDENT_A}_quiz_1`).update({
      score: 100,
      graded: true,
      status: "GRADED",
      gradedBy: ADMIN_UID,
      updatedAt: pastDate(500),
    })
  );

  // Terminal state lock: Regular Admin cannot re-modify once GRADED
  await assertFails(
    adminDb.collection("quiz_attempts").doc(`${STUDENT_A}_quiz_1`).update({
      score: 40,
      graded: true,
      status: "GRADED",
      gradedBy: ADMIN_UID,
      updatedAt: pastDate(200),
    })
  );
});

test("8. Audit logs are append-only and strictly immutable even for Admins and Super Admins", async () => {
  await seedRolesAndBaseData();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const superDb = testEnv.authenticatedContext(SUPER_ADMIN_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("audit_logs").doc("log_001").set({
      actorUid: STUDENT_A,
      actorLabel: "Alice Student",
      action: "UNAUTHORIZED_ADMIN_ATTEMPT",
      severity: "HIGH",
      targetPath: "system_settings/global",
      details: "Blocked attempt to open Super Admin console",
      createdAt: pastDate(),
    })
  );

  // Student AND Regular Admin CANNOT read audit logs (Super Admin only)
  await assertFails(aliceDb.collection("audit_logs").get());
  await assertFails(adminDb.collection("audit_logs").get());

  // Only SuperAdmin can read, and NOBODY can update or delete
  await assertSucceeds(superDb.collection("audit_logs").get());
  await assertFails(
    adminDb.collection("audit_logs").doc("log_001").update({ details: "Erased" })
  );
  await assertFails(adminDb.collection("audit_logs").doc("log_001").delete());
  await assertFails(
    superDb.collection("audit_logs").doc("log_001").update({ details: "Erased" })
  );
  await assertFails(superDb.collection("audit_logs").doc("log_001").delete());
});

test("9. File upload validation, HTTPS-only URLs, ghost fields, and query alignment", async () => {
  await seedRolesAndBaseData();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();

  // Reject executable MIME type and oversized file
  await assertFails(
    aliceDb
      .collection("users")
      .doc(STUDENT_A)
      .collection("files")
      .doc("bad_exe")
      .set({
        userId: STUDENT_A,
        originalName: "malware.exe",
        storedName: "uuid123.exe",
        mimeType: "application/x-msdownload",
        sizeBytes: 2048,
        sha256Hex: "a".repeat(64),
        status: "VERIFIED",
        createdAt: pastDate(),
        updatedAt: pastDate(),
      })
  );

  await assertFails(
    aliceDb
      .collection("users")
      .doc(STUDENT_A)
      .collection("files")
      .doc("huge_pdf")
      .set({
        userId: STUDENT_A,
        originalName: "huge.pdf",
        storedName: "uuid123.pdf",
        mimeType: "application/pdf",
        sizeBytes: 10485760,
        sha256Hex: "b".repeat(64),
        status: "VERIFIED",
        createdAt: pastDate(),
        updatedAt: pastDate(),
      })
  );

  // Valid verified PDF metadata succeeds
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(STUDENT_A)
      .collection("files")
      .doc("valid_pdf")
      .set({
        userId: STUDENT_A,
        originalName: "lecture_notes.pdf",
        storedName: "safe_uuid_01.pdf",
        mimeType: "application/pdf",
        sizeBytes: 102400,
        sha256Hex: "c".repeat(64),
        status: "VERIFIED",
        createdAt: pastDate(),
        updatedAt: pastDate(),
      })
  );

  // Reject non-HTTPS course link (XSS / insecure HTTP)
  await assertFails(
    adminDb.collection("courses").doc("bad_link").set({
      title: "Bad Link Course",
      description: "Testing HTTPS guard",
      instructorId: ADMIN_UID,
      instructorName: "Dr. Carol",
      materialLink: "javascript:alert(1)",
      materialType: "PDF_LINK",
      published: true,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Reject ghost field on user update
  await assertFails(
    aliceDb.collection("users").doc(STUDENT_A).update({
      displayName: "Alice",
      isVerified: true,
      updatedAt: pastDate(500),
    })
  );

  // Student filtered queries succeed, unfiltered fail
  await assertSucceeds(aliceDb.collection("courses").where("published", "==", true).get());
  await assertFails(aliceDb.collection("courses").get());
  await assertSucceeds(aliceDb.collection("quizzes").where("published", "==", true).get());
  await assertFails(aliceDb.collection("quizzes").get());
  await assertSucceeds(
    aliceDb.collection("quiz_attempts").where("userId", "==", STUDENT_A).get()
  );
  await assertFails(aliceDb.collection("quiz_attempts").get());
});

test("10. Super Admin manages settings & roles; duplicate quiz attempt & future timestamp fail", async () => {
  await seedRolesAndBaseData();
  const superDb = testEnv
    .authenticatedContext(SUPER_ADMIN_UID, { email: BOOTSTRAP_EMAIL, email_verified: true })
    .firestore();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();

  // Super Admin updates global zero-cost quota settings
  await assertSucceeds(
    superDb.collection("system_settings").doc("global").update({
      dailyAiLimitPerUser: 15,
      aiEnabled: false,
      updatedBy: SUPER_ADMIN_UID,
      updatedAt: pastDate(500),
    })
  );

  // Super Admin grants Admin role
  await assertSucceeds(
    superDb.collection("admins").doc(STUDENT_B).set({
      userId: STUDENT_B,
      email: "bob@unicore.edu",
      grantedBy: SUPER_ADMIN_UID,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Student submits quiz attempt once
  await assertSucceeds(
    aliceDb.collection("quiz_attempts").doc(`${STUDENT_A}_quiz_1`).set({
      userId: STUDENT_A,
      studentName: "Alice Student",
      quizId: "quiz_1",
      quizTitle: "Zero-Trust Midterm",
      selectedIndices: [1],
      status: "SUBMITTED",
      score: 0,
      graded: false,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Duplicate attempt with same deterministic ID fails (existing doc)
  await assertFails(
    aliceDb.collection("quiz_attempts").doc(`${STUDENT_A}_quiz_1`).set({
      userId: STUDENT_A,
      studentName: "Alice Student",
      quizId: "quiz_1",
      quizTitle: "Zero-Trust Midterm",
      selectedIndices: [0],
      status: "SUBMITTED",
      score: 0,
      graded: false,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Future timestamp tampered payload fails
  await assertFails(
    aliceDb.collection("users").doc(STUDENT_A).update({
      displayName: "Future Alice",
      updatedAt: new Date(Date.now() + 120000),
    })
  );
});

test("11. Role invites RBAC: Admin can invite students only; SuperAdmin can invite admins and users claim safely", async () => {
  await seedRolesAndBaseData();
  const superDb = testEnv
    .authenticatedContext(SUPER_ADMIN_UID, { email: BOOTSTRAP_EMAIL, email_verified: true })
    .firestore();
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: "admin@unicore.edu", email_verified: true })
    .firestore();
  const aliceDb = testEnv
    .authenticatedContext(STUDENT_A, { email: "alice@unicore.edu", email_verified: true })
    .firestore();

  // Admin CAN invite a Student
  await assertSucceeds(
    adminDb.collection("role_invites").doc("new_student@unicore.edu").set({
      email: "new_student@unicore.edu",
      displayNameHint: "New Student",
      assignedRole: "STUDENT",
      invitedBy: ADMIN_UID,
      invitedByLabel: "Dr. Carol",
      status: "PENDING",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Regular Admin CANNOT invite an ADMIN
  await assertFails(
    adminDb.collection("role_invites").doc("sneaky_admin@unicore.edu").set({
      email: "sneaky_admin@unicore.edu",
      displayNameHint: "Sneaky Admin",
      assignedRole: "ADMIN",
      invitedBy: ADMIN_UID,
      invitedByLabel: "Dr. Carol",
      status: "PENDING",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Student CANNOT read/list invites or create invites
  await assertFails(aliceDb.collection("role_invites").get());
  await assertFails(aliceDb.collection("role_invites").doc("new_student@unicore.edu").get());

  // Super Admin CAN invite an ADMIN
  await assertSucceeds(
    superDb.collection("role_invites").doc("dr_tarek@unicore.edu").set({
      email: "dr_tarek@unicore.edu",
      displayNameHint: "Dr. Tarek",
      assignedRole: "ADMIN",
      invitedBy: SUPER_ADMIN_UID,
      invitedByLabel: "Super Admin",
      status: "PENDING",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Invited Admin signs in with verified email and claims invite + creates admin doc
  const tarekDb = testEnv
    .authenticatedContext("tarek_uid", { email: "Dr_Tarek@unicore.edu", email_verified: true })
    .firestore();
  await assertSucceeds(tarekDb.collection("role_invites").doc("dr_tarek@unicore.edu").get());
  await assertSucceeds(
    tarekDb.collection("admins").doc("tarek_uid").set({
      userId: "tarek_uid",
      email: "dr_tarek@unicore.edu",
      grantedBy: SUPER_ADMIN_UID,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
  await assertSucceeds(
    tarekDb.collection("role_invites").doc("dr_tarek@unicore.edu").update({
      status: "CLAIMED",
      claimedByUid: "tarek_uid",
      updatedAt: pastDate(500),
    })
  );
});

test("12. Notifications RBAC: Admins send broadcasts/targeted alerts; Students read only ALL or own", async () => {
  await seedRolesAndBaseData();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();
  const bobDb = testEnv.authenticatedContext(STUDENT_B).firestore();

  await assertSucceeds(
    adminDb.collection("notifications").doc("notif_all_1").set({
      title: "Midterm Week",
      body: "Review CS101 Zero-Trust notes.",
      category: "COURSE",
      targetUid: "ALL",
      senderUid: ADMIN_UID,
      senderName: "Dr. Carol",
      createdAt: pastDate(),
    })
  );

  await assertSucceeds(
    adminDb.collection("notifications").doc("notif_bob_1").set({
      title: "Grade Published",
      body: "Your Midterm grade is ready.",
      category: "GRADE",
      targetUid: STUDENT_B,
      senderUid: ADMIN_UID,
      senderName: "Dr. Carol",
      createdAt: pastDate(),
    })
  );

  // Student cannot create broadcast notification
  await assertFails(
    aliceDb.collection("notifications").doc("fake_notif").set({
      title: "Fake Alert",
      body: "Class canceled",
      category: "ANNOUNCEMENT",
      targetUid: "ALL",
      senderUid: STUDENT_A,
      senderName: "Alice Student",
      createdAt: pastDate(),
    })
  );

  // Alice cannot read Bob's private grade notification directly or via unfiltered list
  await assertFails(aliceDb.collection("notifications").doc("notif_bob_1").get());
  await assertFails(aliceDb.collection("notifications").get());

  // Aligned whereIn(["ALL", uid]) query succeeds for Alice and Bob
  await assertSucceeds(
    aliceDb.collection("notifications").where("targetUid", "in", ["ALL", STUDENT_A]).get()
  );
  await assertSucceeds(
    bobDb.collection("notifications").where("targetUid", "in", ["ALL", STUDENT_B]).get()
  );
});

test("13. Course file chunks & Drive storage folders RBAC: SuperAdmin manages storage folders, Regular Admin & Students cannot modify", async () => {
  await seedRolesAndBaseData();
  const superDb = testEnv
    .authenticatedContext(SUPER_ADMIN_UID, { email: BOOTSTRAP_EMAIL, email_verified: true })
    .firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();

  // Admin creates FILE course with attachment metadata & chunk
  await assertSucceeds(
    adminDb.collection("courses").doc("course_pdf_1").set({
      title: "Discrete Math Slides",
      description: "Lecture 1 PDF attached directly",
      instructorId: ADMIN_UID,
      instructorName: "Dr. Carol",
      materialType: "FILE",
      hasAttachment: true,
      attachmentName: "lecture1.pdf",
      attachmentMimeType: "application/pdf",
      attachmentSizeBytes: 102400,
      chunkCount: 1,
      attachmentSha256: "a".repeat(64),
      published: true,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  await assertSucceeds(
    adminDb.collection("courses").doc("course_pdf_1").collection("chunks").doc("0").set({
      chunkIndex: 0,
      data: "JVBERi0xLjQK",
      createdAt: pastDate(),
    })
  );

  // Student CAN read published course chunks, CANNOT write/delete chunks
  await assertSucceeds(
    aliceDb.collection("courses").doc("course_pdf_1").collection("chunks").get()
  );
  await assertFails(
    aliceDb.collection("courses").doc("course_pdf_1").collection("chunks").doc("1").set({
      chunkIndex: 1,
      data: "AAAA",
      createdAt: pastDate(),
    })
  );

  // Regular Admin CANNOT create drive_folders (Super Admin only)
  await assertFails(
    adminDb.collection("drive_folders").doc("1AbCdEfGhIjKlMnOp").set({
      folderId: "1AbCdEfGhIjKlMnOp",
      title: "Admin Unauthorized Folder",
      folderUrl: "https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOp",
      addedBy: ADMIN_UID,
      addedByName: "Dr. Carol",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Non-drive URLs rejected even for Super Admin
  await assertFails(
    superDb.collection("drive_folders").doc("1AbCdEfGhIjKlMnOp").set({
      folderId: "1AbCdEfGhIjKlMnOp",
      title: "Bad Non-Drive Host",
      folderUrl: "https://evil.example.com/folders/1AbCdEfGhIjKlMnOp",
      addedBy: SUPER_ADMIN_UID,
      addedByName: "Super Dave",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Super Admin CAN connect, update, and delete storage folder
  await assertSucceeds(
    superDb.collection("drive_folders").doc("1AbCdEfGhIjKlMnOp").set({
      folderId: "1AbCdEfGhIjKlMnOp",
      title: "NCT 1 Storage",
      folderUrl: "https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOp",
      addedBy: SUPER_ADMIN_UID,
      addedByName: "Super Dave",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  await assertSucceeds(
    superDb.collection("drive_folders").doc("1AbCdEfGhIjKlMnOp").update({
      title: "NCT 1 Updated",
      folderUrl: "https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOp?usp=sharing",
      updatedAt: pastDate(100),
    })
  );

  // Regular Admin CANNOT update or delete Super Admin's storage folder
  await assertFails(
    adminDb.collection("drive_folders").doc("1AbCdEfGhIjKlMnOp").update({
      title: "Hijacked",
      updatedAt: pastDate(50),
    })
  );
  await assertFails(
    adminDb.collection("drive_folders").doc("1AbCdEfGhIjKlMnOp").delete()
  );

  // Student CAN list drive_folders for transparent app sync, CANNOT create, update, or delete
  await assertSucceeds(aliceDb.collection("drive_folders").get());
  await assertFails(
    aliceDb.collection("drive_folders").doc("1AbCdEfGhIjKlMnOp").delete()
  );

  // Super Admin CAN delete storage folder
  await assertSucceeds(
    superDb.collection("drive_folders").doc("1AbCdEfGhIjKlMnOp").delete()
  );
});

test("14. Super Admin can disable/re-enable accounts without deleting; disabled users are blocked from all collections", async () => {
  await seedRolesAndBaseData();
  const superDb = testEnv
    .authenticatedContext(SUPER_ADMIN_UID, { email: BOOTSTRAP_EMAIL, email_verified: true })
    .firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();

  // Regular Admin CANNOT disable Student Alice
  await assertFails(
    adminDb.collection("users").doc(STUDENT_A).update({
      accountDisabled: true,
      updatedAt: pastDate(),
    })
  );

  // Student Alice CANNOT toggle her own accountDisabled status
  await assertFails(
    aliceDb.collection("users").doc(STUDENT_A).update({
      accountDisabled: true,
      updatedAt: pastDate(),
    })
  );

  // Super Admin CANNOT disable themselves
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(SUPER_ADMIN_UID).set({
      userId: SUPER_ADMIN_UID,
      displayName: "Super Dave",
      role: "SUPER_ADMIN",
      aiCallsToday: 0,
      writesToday: 0,
      quotaDayKey: "2026-11-22",
      createdAt: pastDate(5000),
      updatedAt: pastDate(5000),
    });
  });
  await assertFails(
    superDb.collection("users").doc(SUPER_ADMIN_UID).update({
      accountDisabled: true,
      updatedAt: pastDate(),
    })
  );

  // Super Admin disables Student Alice
  await assertSucceeds(
    superDb.collection("users").doc(STUDENT_A).update({
      accountDisabled: true,
      updatedAt: pastDate(),
    })
  );

  // Disabled Alice can still read her own profile (to render Suspended banner)
  await assertSucceeds(aliceDb.collection("users").doc(STUDENT_A).get());

  // Disabled Alice is blocked from courses, drive folders, quizzes, attempts, and notifications
  await assertFails(aliceDb.collection("courses").where("published", "==", true).get());
  await assertFails(aliceDb.collection("drive_folders").get());
  await assertFails(aliceDb.collection("quizzes").where("published", "==", true).get());
  await assertFails(
    aliceDb.collection("notifications").where("targetUid", "in", ["ALL", STUDENT_A]).get()
  );

  // Super Admin re-enables Alice without losing her record
  await assertSucceeds(
    superDb.collection("users").doc(STUDENT_A).update({
      accountDisabled: false,
      updatedAt: pastDate(100),
    })
  );
  await assertSucceeds(aliceDb.collection("courses").where("published", "==", true).get());
  await assertSucceeds(aliceDb.collection("drive_folders").get());
});

test("15. Support messaging RBAC: Students contact Admins only; Admins ONLY contact Super Admin", async () => {
  await seedRolesAndBaseData();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();
  const bobDb = testEnv.authenticatedContext(STUDENT_B).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const superDb = testEnv
    .authenticatedContext(SUPER_ADMIN_UID, { email: BOOTSTRAP_EMAIL, email_verified: true })
    .firestore();

  // Student Alice sends message to Admin
  await assertSucceeds(
    aliceDb.collection("support_messages").doc("msg_alice_1").set({
      channel: "STUDENT_TO_ADMIN",
      senderUid: STUDENT_A,
      senderName: "Alice Student",
      senderRole: "STUDENT",
      subject: "Need PDF clarification",
      body: "Can you re-upload slide 4?",
      status: "OPEN",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Student CANNOT contact Super Admin directly via ADMIN_TO_SUPER channel
  await assertFails(
    aliceDb.collection("support_messages").doc("msg_alice_illegal").set({
      channel: "ADMIN_TO_SUPER",
      senderUid: STUDENT_A,
      senderName: "Alice Student",
      senderRole: "STUDENT",
      subject: "Bypass",
      body: "Trying to reach super admin",
      status: "OPEN",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Student Bob CANNOT read Alice's message
  await assertFails(bobDb.collection("support_messages").doc("msg_alice_1").get());

  // Admin CAN read and reply to Student Alice's message
  await assertSucceeds(
    adminDb.collection("support_messages").where("channel", "==", "STUDENT_TO_ADMIN").get()
  );
  await assertSucceeds(
    adminDb.collection("support_messages").doc("msg_alice_1").update({
      status: "REPLIED",
      replyText: "Slide 4 has been updated cleanly.",
      repliedByUid: ADMIN_UID,
      repliedByName: "Dr. Carol",
      updatedAt: pastDate(200),
    })
  );

  // Admin Carol sends ADMIN_TO_SUPER message to Super Admin
  await assertSucceeds(
    adminDb.collection("support_messages").doc("msg_admin_1").set({
      channel: "ADMIN_TO_SUPER",
      senderUid: ADMIN_UID,
      senderName: "Dr. Carol",
      senderRole: "ADMIN",
      subject: "Quota increase request",
      body: "Need higher daily write cap for exam week.",
      status: "OPEN",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Student CANNOT read Admin-to-SuperAdmin messages
  await assertFails(aliceDb.collection("support_messages").doc("msg_admin_1").get());

  // Regular Admin CANNOT reply to their own ADMIN_TO_SUPER ticket
  await assertFails(
    adminDb.collection("support_messages").doc("msg_admin_1").update({
      status: "REPLIED",
      replyText: "Self-approved",
      repliedByUid: ADMIN_UID,
      repliedByName: "Dr. Carol",
      updatedAt: pastDate(100),
    })
  );

  // Super Admin CAN read and reply to Admin Carol's message
  await assertSucceeds(superDb.collection("support_messages").get());
  await assertSucceeds(
    superDb.collection("support_messages").doc("msg_admin_1").update({
      status: "REPLIED",
      replyText: "Write cap raised to 250.",
      repliedByUid: SUPER_ADMIN_UID,
      repliedByName: "Super Dave",
      updatedAt: pastDate(50),
    })
  );
});

test("16. Academic Departments & Years RBAC: Only Admins and SuperAdmins can create/edit/delete departments", async () => {
  await seedRolesAndBaseData();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const superDb = testEnv
    .authenticatedContext(SUPER_ADMIN_UID, { email: BOOTSTRAP_EMAIL, email_verified: true })
    .firestore();

  // Student CANNOT create a department
  await assertFails(
    aliceDb.collection("departments").doc("dept_re").set({
      name: "طاقة متجددة",
      years: ["الفرقة الأولى", "الفرقة الثانية"],
      createdBy: STUDENT_A,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Admin CAN create a department with study years
  await assertSucceeds(
    adminDb.collection("departments").doc("dept_re").set({
      name: "طاقة متجددة",
      years: ["الفرقة الأولى", "الفرقة الثانية", "الفرقة الثالثة", "الفرقة الرابعة"],
      createdBy: ADMIN_UID,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Active Student CAN read departments
  await assertSucceeds(aliceDb.collection("departments").get());

  // Student CANNOT edit or delete a department
  await assertFails(
    aliceDb.collection("departments").doc("dept_re").update({
      name: "Hacked Dept",
      updatedAt: pastDate(100),
    })
  );
  await assertFails(aliceDb.collection("departments").doc("dept_re").delete());

  // Super Admin CAN edit departments created by Admin
  await assertSucceeds(
    superDb.collection("departments").doc("dept_re").update({
      name: "هندسة الطاقة المتجددة",
      years: ["الفرقة الأولى", "الفرقة الثانية", "الفرقة الثالثة", "الفرقة الرابعة"],
      updatedAt: pastDate(100),
    })
  );

  // Admin CAN assign a course to a Department & Academic Year
  await assertSucceeds(
    adminDb.collection("courses").doc("course_cs101").update({
      departmentId: "dept_re",
      departmentName: "هندسة الطاقة المتجددة",
      academicYear: "الفرقة الأولى",
      updatedAt: pastDate(50),
    })
  );
});

test("17. Flexible Super Admin grants, manual super_admin role recognition, and self-promotion block", async () => {
  await seedRolesAndBaseData();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A, { email: "alice@unicore.edu", email_verified: true }).firestore();
  const superDb = testEnv.authenticatedContext(SUPER_ADMIN_UID, { email: BOOTSTRAP_EMAIL, email_verified: true }).firestore();

  // Student CANNOT self-assign super_admin or SUPER_ADMIN
  await assertFails(
    aliceDb.collection("users").doc(STUDENT_A).update({
      role: "super_admin",
      updatedAt: pastDate(100),
    })
  );

  // Existing Super Admin can invite a new SUPER_ADMIN
  await assertSucceeds(
    superDb.collection("role_invites").doc("newsuper@unicore.edu").set({
      email: "newsuper@unicore.edu",
      displayNameHint: "New Super",
      assignedRole: "SUPER_ADMIN",
      invitedBy: SUPER_ADMIN_UID,
      invitedByLabel: "Super Dave",
      status: "PENDING",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Invited user claims SUPER_ADMIN role
  const newSuperDb = testEnv.authenticatedContext("new_super_uid", {
    email: "newsuper@unicore.edu",
    email_verified: true,
  }).firestore();

  await assertSucceeds(
    newSuperDb.collection("super_admins").doc("new_super_uid").set({
      userId: "new_super_uid",
      email: "newsuper@unicore.edu",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
  await assertSucceeds(
    newSuperDb.collection("users").doc("new_super_uid").set({
      userId: "new_super_uid",
      displayName: "New Super",
      role: "SUPER_ADMIN",
      aiCallsToday: 0,
      writesToday: 0,
      quotaDayKey: "2026-11-27",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Manual Firestore Console `role: "super_admin"` on users doc grants Super Admin powers
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc("console_super").set({
      userId: "console_super",
      displayName: "Console Super",
      role: "super_admin",
      aiCallsToday: 0,
      writesToday: 0,
      quotaDayKey: "2026-11-27",
      createdAt: pastDate(20000),
      updatedAt: pastDate(20000),
    });
  });
  const consoleSuperDb = testEnv.authenticatedContext("console_super").firestore();
  await assertSucceeds(consoleSuperDb.collection("audit_logs").get());
  await assertSucceeds(
    consoleSuperDb.collection("system_settings").doc("global").update({
      tutorialVideoUrl: "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      tutorialVideoTitle: "UniCore Guide",
      updatedBy: "console_super",
      updatedAt: pastDate(100),
    })
  );
});

test("18. User onboarding placement (Year & Section), favorites, and completed assignments", async () => {
  await seedRolesAndBaseData();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();
  const bobDb = testEnv.authenticatedContext(STUDENT_B).firestore();

  await assertSucceeds(
    aliceDb.collection("users").doc(STUDENT_A).update({
      departmentId: "dept_re",
      departmentName: "طاقة متجددة",
      academicYear: "الفرقة الأولى",
      sectionName: "سيكشن 2",
      placementCompleted: true,
      favoriteCourseIds: ["course_cs101"],
      completedAssignmentIds: ["assign_1"],
      classroomConnected: true,
      updatedAt: pastDate(100),
    })
  );

  // Bob CANNOT alter Alice's placement or favorites
  await assertFails(
    bobDb.collection("users").doc(STUDENT_A).update({
      academicYear: "الفرقة الرابعة",
      updatedAt: pastDate(50),
    })
  );
});

test("19. Assignments ('اللي عليا') & Google Classroom isolation rules", async () => {
  await seedRolesAndBaseData();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();
  const bobDb = testEnv.authenticatedContext(STUDENT_B).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();

  // Admin publishes shared assignment
  await assertSucceeds(
    adminDb.collection("assignments").doc("assign_shared").set({
      title: "Circuit Analysis Lab 1",
      description: "Solve problems 1-5",
      courseId: "course_cs101",
      courseTitle: "Circuits",
      departmentName: "طاقة متجددة",
      academicYear: "الفرقة الأولى",
      sectionName: "سيكشن 1",
      dueAtMillis: Date.now() + 86400000,
      sourceType: "APP",
      ownerUid: "ALL",
      published: true,
      createdBy: ADMIN_UID,
      createdByName: "Dr. Carol",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Student CANNOT publish shared 'ALL' assignment
  await assertFails(
    aliceDb.collection("assignments").doc("assign_fake_all").set({
      title: "Fake Assignment",
      description: "Not allowed",
      dueAtMillis: Date.now() + 86400000,
      sourceType: "APP",
      ownerUid: "ALL",
      published: true,
      createdBy: STUDENT_A,
      createdByName: "Alice Student",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Student CAN import personal Google Classroom assignment scoped to own UID
  await assertSucceeds(
    aliceDb.collection("assignments").doc("assign_gc_alice").set({
      title: "GC Homework",
      description: "Synced from Classroom",
      dueAtMillis: Date.now() + 172800000,
      classroomLink: "https://classroom.google.com/c/123",
      sourceType: "CLASSROOM",
      ownerUid: STUDENT_A,
      published: true,
      createdBy: STUDENT_A,
      createdByName: "Alice Student",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Alice filtered query (where ownerUid in ['ALL', STUDENT_A]) succeeds; Bob cannot read Alice's personal Classroom assignment
  await assertSucceeds(
    aliceDb.collection("assignments").where("ownerUid", "in", ["ALL", STUDENT_A]).get()
  );
  await assertFails(bobDb.collection("assignments").doc("assign_gc_alice").get());
  await assertFails(bobDb.collection("assignments").where("ownerUid", "in", ["ALL", STUDENT_A]).get());
});

test("20. Schedule items, Zoom sessions, Instructions, and Course lecture videos RBAC", async () => {
  await seedRolesAndBaseData();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();

  await assertSucceeds(
    adminDb.collection("courses").doc("course_cs101").update({
      sectionName: "سيكشن 1",
      videoUrl: "https://www.youtube.com/watch?v=abc12345",
      videoTitle: "Lecture 1 Video",
      updatedAt: pastDate(100),
    })
  );

  await assertSucceeds(
    adminDb.collection("schedule_items").doc("sched_1").set({
      title: "Power Systems Lecture",
      itemType: "LECTURE",
      dayOfWeek: 0,
      startTimeText: "09:00",
      endTimeText: "11:00",
      locationOrMode: "Hall A",
      academicYear: "الفرقة الأولى",
      sectionName: "سيكشن 1",
      videoUrl: "https://www.youtube.com/watch?v=abc12345",
      published: true,
      createdBy: ADMIN_UID,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  await assertSucceeds(
    adminDb.collection("zoom_sessions").doc("zoom_1").set({
      title: "Live Revision",
      topic: "Midterm Q&A",
      zoomUrl: "https://zoom.us/j/1234567890",
      scheduledAtMillis: Date.now() + 3600000,
      durationMinutes: 60,
      hostName: "Dr. Carol",
      academicYear: "الفرقة الأولى",
      sectionName: "سيكشن 1",
      published: true,
      createdBy: ADMIN_UID,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  await assertSucceeds(
    adminDb.collection("instructions").doc("inst_1").set({
      title: "Exam Rules",
      body: "Bring student ID to all midterm halls.",
      priority: "PINNED",
      academicYear: "ALL",
      sectionName: "ALL",
      published: true,
      createdBy: ADMIN_UID,
      createdByName: "Dr. Carol",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  // Active student can query published collections
  await assertSucceeds(aliceDb.collection("schedule_items").where("published", "==", true).get());
  await assertSucceeds(aliceDb.collection("zoom_sessions").where("published", "==", true).get());
  await assertSucceeds(aliceDb.collection("instructions").where("published", "==", true).get());

  // Student cannot write or delete schedule/zoom/instructions
  await assertFails(aliceDb.collection("schedule_items").doc("sched_1").delete());
  await assertFails(aliceDb.collection("zoom_sessions").doc("zoom_1").delete());
  await assertFails(aliceDb.collection("instructions").doc("inst_1").delete());
});

test("21. Section messages RBAC: Students post MESSAGE unpinned; Admins post pinned NOTICE", async () => {
  await seedRolesAndBaseData();
  const aliceDb = testEnv.authenticatedContext(STUDENT_A).firestore();
  const bobDb = testEnv.authenticatedContext(STUDENT_B).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();

  // Student cannot post pinned NOTICE or impersonate ADMIN
  await assertFails(
    aliceDb.collection("section_messages").doc("sec_bad").set({
      departmentName: "طاقة متجددة",
      academicYear: "الفرقة الأولى",
      sectionName: "سيكشن 1",
      senderUid: STUDENT_A,
      senderName: "Alice Student",
      senderRole: "STUDENT",
      messageType: "NOTICE",
      isPinned: true,
      text: "Fake notice",
      createdAt: pastDate(),
    })
  );

  // Student posts valid section MESSAGE
  await assertSucceeds(
    aliceDb.collection("section_messages").doc("sec_alice_1").set({
      departmentName: "طاقة متجددة",
      academicYear: "الفرقة الأولى",
      sectionName: "سيكشن 1",
      senderUid: STUDENT_A,
      senderName: "Alice Student",
      senderRole: "STUDENT",
      messageType: "MESSAGE",
      isPinned: false,
      text: "Hello Section 1 classmates!",
      createdAt: pastDate(),
    })
  );

  // Admin posts pinned NOTICE
  await assertSucceeds(
    adminDb.collection("section_messages").doc("sec_admin_1").set({
      departmentName: "طاقة متجددة",
      academicYear: "الفرقة الأولى",
      sectionName: "سيكشن 1",
      senderUid: ADMIN_UID,
      senderName: "Dr. Carol",
      senderRole: "ADMIN",
      messageType: "NOTICE",
      isPinned: true,
      text: "Lab moved to Room 204.",
      createdAt: pastDate(),
    })
  );

  // Bob cannot delete Alice's message; Admin can delete any message
  await assertFails(bobDb.collection("section_messages").doc("sec_alice_1").delete());
  await assertSucceeds(adminDb.collection("section_messages").doc("sec_alice_1").delete());
});

