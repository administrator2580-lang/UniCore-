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

  // Admin can read published/all courses and audit logs
  await assertSucceeds(adminDb.collection("courses").get());
  await assertSucceeds(adminDb.collection("audit_logs").get());

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

  // Student cannot read audit logs
  await assertFails(aliceDb.collection("audit_logs").get());

  // Admin & SuperAdmin can read, but CANNOT update or delete
  await assertSucceeds(adminDb.collection("audit_logs").get());
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
