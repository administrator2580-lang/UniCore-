# Security Summary

**Reviewed Files**: 22
**Issues Resolved**: 1

### Issues Resolved
| Area | File | Line | What Was Happening | Impact | Status | How It Was Fixed |
|---|---|---|---|---|---|---|
| Token Claim Evaluation Guard | `firestore.rules` | 31 | `isBootstrappedSuperAdmin()` read `request.auth.token.email` without checking key presence first, producing undefined-property warnings on custom test tokens. | Low | Fixed | Added `('email' in request.auth.token) && ('email_verified' in request.auth.token)` before reading email claims and re-ran all 10 emulator security suites. |

### Safeguards Verified
| Area | File | How It's Protected |
|---|---|---|
| Authentication & Passwords | `AuthHelper.kt`, `AuthScreen.kt` | Uses Jetpack Credential Manager with Google Sign-In (`GetSignInWithGoogleOption`); zero plaintext passwords or custom password storage. |
| API Secrets & App Check | `AppCheckInit.kt`, `.env.example`, `AiTutorRepository.kt` | Removed unused `GEMINI_API_KEY` placeholders; routes Gemini calls through `Firebase.ai` with Firebase App Check attestation initialized before UI/ViewModels. |
| RBAC & Data Isolation | `firestore.rules`, `UniCoreRepository.kt` | Enforces `STUDENT`, `ADMIN`, and `SUPER_ADMIN` separation via `/admins` and `/super_admins`, split `/users/{uid}/private` PII, split `/quizzes/{id}/private/answer_key`, and immutable `/audit_logs`. |
| HTTPS & Network Security | `network_security_config.xml`, `AndroidManifest.xml`, `InputValidator.kt` | Disables cleartext HTTP (`cleartextTrafficPermitted="false"`, `usesCleartextTraffic="false"`), sets `allowBackup="false"`, and blocks `http://`, `javascript:`, `data:`, and private SSRF IP hosts. |
| Rate Limiting & Zero-Cost Guard | `RateLimiter.kt`, `ZeroCostQuotaManager.kt` | Persistent DataStore sliding-window + exponential backoff lockouts and automatic safe pause at 100% free-tier cap (80% warning). |
| File Upload Sandbox | `SecureFileValidator.kt`, `CoursesAndFilesScreen.kt` | Zero-permission Photo Picker / SAF, MIME + extension + binary magic-byte verification, 5 MB cap, SHA-256 hashing, and randomized UUID storage inside `filesDir/secure_uploads`. |

## PoC Verification

### Undefined Auth Token Claim Access in Bootstrapped Super Admin Rule

#### Summary
| Field | Value |
|---|---|
| Issue Type | Unchecked Token Claim Property Access |
| Impact | Low |
| Affected File | `firestore.rules:31` |
| Test Scenario | Authenticated request whose JWT lacks `email` / `email_verified` claims evaluating `isSuperAdmin()` during write validation |

#### How It Was Fixed
Added explicit key membership guards (`('email' in request.auth.token) && ('email_verified' in request.auth.token)`) inside `isBootstrappedSuperAdmin()` so tokens lacking email claims cleanly evaluate `false` without CEL property errors.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | Authenticated request arrives at `firestore.rules` evaluation | Enters `isSuperAdmin()` |
| 2 | Reaches `isBootstrappedSuperAdmin()` at `firestore.rules:31` | Membership check short-circuits missing `email` safely |
| 3 | Re-ran `FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js` | **10/10 suites passed cleanly** |

#### Conclusion
**Fix Verified** - Missing token claims can no longer trigger CEL undefined-property evaluation errors during authorization checks.
