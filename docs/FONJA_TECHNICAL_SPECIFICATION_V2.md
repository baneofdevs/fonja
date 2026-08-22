# Fonja — Technical Specification (v2)

**Status:** Draft v2 — aligned to BRD v2 (trimmed against Crowdr case study)
**Purpose:** Lay the technical foundation for Fonja's core loop: register → verify (creators) → browse → donate → withdraw.

---

## 1. Overview

Fonja is a donation-based crowdfunding platform for Nigeria. Donors browse a campaign feed and donate; verified creators raise funds and withdraw them. Money moves directly through CyberPay's licensed payment infrastructure — Fonja does not hold a wallet abstraction; it keeps an internal append-only earnings record per creator purely for withdrawal accounting.

v2 deliberately narrows scope from the original draft: no Guardian workflow, no tiered KYC, no Trust Reserve Fund, no Community Issues Reporting. These are real ideas, kept in the BRD's roadmap, not the v1 build.

### 1.1 Goals
- Launch-ready foundation, buildable in days by a small team.
- Correctness and auditability on the money path from day one.
- A verification gate for creators (not donors), matching Crowdr's proven model.

### 1.2 Non-Goals (v1)
- Guardian/Guarantor sign-off workflow (Phase 2)
- Tiered KYC with per-tier donation caps (Phase 2)
- Trust Reserve Fund / discretionary refunds (Phase 3)
- Community Issues Reporting (Phase 3)
- CSR module, volunteer matching, public profiles (Phase 2)
- USSD and multi-gateway support beyond CyberPay (later)

---

## 2. Architecture Pattern: Modular Monolith

One Spring Boot application, organized into bounded packages. Each module owns its own tables; cross-module calls go through service interfaces only.

```
fonja/
├── user/           — accounts, OAuth login, roles (donor / creator / admin)
├── verification/    — single creator-verification gate (individual / business / NGO)
├── campaign/        — create, edit, feed, admin review, campaign lifecycle
├── donation/        — donation initiation, CyberPay integration
├── ledger/          — append-only earnings records for every creator
├── payout/          — withdrawal requests against available earnings (no approval gate)
├── notification/     — push / email / SMS on key events
└── common/           — shared config, security, audit event publisher
```

**Removed from v1 vs. the original draft:** `guardian` module, `kyc` tiering logic, `trust-reserve` ledger category. These return in Phase 2/3 per the BRD roadmap.

---

## 3. Tech Stack

| Layer | Choice |
|---|---|
| Language/Framework | Java 17, Spring Boot |
| Database | MySQL 8, single instance, schema-per-module discipline |
| Object storage | **Local filesystem** for now — verification & evidence documents; abstracted behind an interface so migration to Azure Blob later doesn't touch calling code |
| Auth | OAuth2 (Google, Apple) via Spring Security OAuth2 Client; internal JWT after first login |
| Payment gateway | **CyberPay** (internal — reuse existing response wrapper / webhook conventions where applicable) |
| Containerization | Docker |
| Source control / CI | **GitHub, GitHub Actions** |
| Hosting | **Azure** |
| Messaging | Deferred — introduced only if/when a module is extracted from the monolith |
| Audit | Reuse existing audit-central-service; all payment/verification events published there |

---

## 4. Module Responsibilities

### 4.1 `user`
- OAuth2 login via Google/Apple; maps provider `sub` → internal `user_id`.
- Roles: `DONOR`, `CREATOR`, `ADMIN` — a user can hold both `DONOR` and `CREATOR`.
- No verification required to register or donate.

### 4.2 `verification`
- Gate triggered at **Launch**, not at campaign creation — a user can build an entire draft campaign unauthenticated and unverified.
- Types: Individual (government ID + evidence), Business/NGO (registration number + evidence of activity). Document types requested are matched to the campaign's chosen category.
- Documents uploaded via a `StorageService` interface — implemented today as local filesystem writes, swappable for Azure Blob later without touching the `verification` module itself. Only metadata (`file_path` / future `object_key`, `status`, `doc_type`) stored in MySQL, never file bytes.
- Admin approves/rejects; approval sets `CREATOR` role + `verified = true`. Verification persists across future campaigns — an already-verified creator skips this step entirely on Launch.
- A verification request in `PENDING` is enough to let a campaign queue for review (§4.3), but never enough to put one live.

### 4.3 `campaign`
- Wizard steps 1–6 (destination/category, beneficiary, goal, media, story, title) require no auth and can be held client-side or as an unauthenticated draft.
- Step 7 (sign-in) creates the `user` record if new; step 8 saves the campaign server-side as `draft`.
- States: `draft → pending_review → active`, then one of three terminal states.

  ```
  draft ──► pending_review ──► active ──► completed   (goal reached)
    ▲            │               │
    │            │               └─────► expired      (deadline passed unfunded)
    └── reject ──┤               │
                 └───────────────┴────► cancelled     (creator withdrew)
  ```

  A single `closed` state was the original v2 design. It was split during the Day 2 build because
  the three ways a campaign ends are not interchangeable downstream: `completed` releases a payout,
  `cancelled` may owe donors a refund, and `expired` does neither. `suspended` remains reserved for
  fraud response and has no transition into it until that tooling is built.
- Every transition is a guarded method on the `Campaign` entity — nothing assigns the state field
  directly, so a campaign cannot reach a state it has no legal path to.
- `deadline` is optional. Null means the campaign runs open-ended and never auto-expires; otherwise
  an hourly sweep moves past-deadline `active` campaigns to `expired`. That sweep is an in-process
  schedule with no shared lock — the same single-instance caveat as local file storage applies.
- Hitting **Launch**: if the creator is already `verified = true`, the campaign moves straight to
  `pending_review`. If not, it moves there as soon as they have a verification request in flight —
  the campaign then queues for review while an admin works through their documents. Approval is
  still gated on `verified = true`: an approval attempt on an unverified creator's campaign is
  auto-rejected back to `draft` with the reason recorded, so an unapproved submission can never put
  a campaign live.
- Rejection always records a reason, returned to the creator so they know what to fix. Resubmitting
  clears it.
- The fundraising terms — `goal_amount`, `currency`, `deadline` — are editable only while the
  campaign is a `draft`. Content (title, story, media, category, location) stays editable until the
  campaign ends. Donations are recorded against a campaign's currency and counted towards its goal,
  so moving either once money can arrive would rewrite what donors gave to.
- Deleting a `draft` removes it; deleting anything further along soft-deletes it via `cancelled`.
- Reads are owner-scoped: a campaign belonging to someone else answers 404 rather than 403, so the
  API never confirms that another user's campaign exists.
- Feed surfaces `active` campaigns only. **Not yet built** — the feed and the donor-facing single
  campaign view are the remaining gap in this module.

### 4.4 `donation`
- Donor-entered amount, no tiers, no cap check (any registered donor can donate).
- Calls CyberPay, handles callback.
- On confirmed payment, writes to the creator's earnings record via the `ledger` module — donation module holds no balance logic itself.

### 4.5 `ledger`
- **Append-only**, source of truth for each creator's available earnings. No user-facing wallet — donor payments and creator payouts both pass straight through CyberPay; this module exists purely for accounting and audit.
- Every donation: one entry crediting the creator's earnings record (93%), one entry recording Fonja's revenue (7%) — written atomically.
- Every withdrawal: one entry debiting the creator's earnings record, minus the flat ₦200 fee.
- Available balance is always derived by summing entries, never stored as a mutable column.

### 4.6 `payout`
- Verified creator can request withdrawal against their available earnings **at any time — no per-withdrawal admin approval**, since verification already happened at creator onboarding.
- Requires a bank account linked and name-matched during verification.
- Settlement via CyberPay/NIBSS.
- Status: `requested → processing → paid` / `failed`.

### 4.7 `notification`
- Synchronous send acceptable at launch volume.
- Events: donation receipt, campaign approval/rejection, withdrawal confirmation.

### 4.8 `common`
- Shared security config, idempotency utilities, audit event publisher.

---

## 5. Money-Path Rigor (unchanged from v1 — non-negotiable)

1. **Idempotency on all CyberPay callbacks** — unique constraint on gateway transaction reference.
2. **Ledger (earnings record) is append-only, double-entry style** — no `UPDATE` on a balance column, ever.
3. **Every ledger write and verification decision is audited** via audit-central-service.
4. **Donate ≠ Create.** Donating requires only a registered account. Creating and withdrawing requires verification — but withdrawing itself needs no additional per-transaction gate once verified.

---

## 6. Data Storage Split

- **MySQL** — users, campaigns, donations, earnings/ledger entries, payout requests, verification metadata.
- **Local filesystem** (for now) — verification and campaign evidence document files, behind a `StorageService` interface. MySQL never stores file bytes, only the path/key reference. **Caveat:** local disk isn't durable across container restarts or multiple app instances — fine for single-instance development and the initial foundation, but must move to Azure Blob before any multi-instance or production deployment where the disk could be wiped or isn't shared.

---

## 7. Repo & CI

- **Monorepo** on GitHub: `/services/*` module structure inside a single `fonja` repo.
- GitHub Actions: build/test/lint on PR, deploy on merge to main.
- Environments: `staging`, `production`.

---

## 8. Open Decisions

- [ ] Azure hosting target: App Service vs. AKS
- [ ] Exact document types required per verification type (individual vs. business/NGO)
- [ ] Platform fee split confirmation (7% / ₦200 — business-confirmed, not yet legally reviewed per BRD §19)
- [ ] Timeline to migrate document storage from local filesystem to Azure Blob — must happen before any multi-instance deployment

---

## 9. 3-Day Foundation Plan

**Day 1 — Skeleton, Auth, Verification skeleton**
- Repo setup, module structure, Docker Compose (app + MySQL)
- OAuth2 login (Google first) end-to-end
- `user` module: schema + roles
- `verification` module: `StorageService` interface + local filesystem implementation, upload endpoint + metadata table (admin review UI can follow)

**Day 2 — Campaign & Ledger**
- `campaign` module: CRUD, Launch gate, admin review queue, lifecycle (§4.3) — done; public feed endpoint still outstanding
- `ledger` module: append-only schema, two-way split write path, available-earnings derivation query
- `common`: audit event publisher wired to audit-central-service

**Day 3 — Donation & Payout**
- `donation` module: CyberPay integration, webhook handler with idempotency
- `payout` module: withdrawal request → CyberPay/NIBSS settlement call (sandbox)
- End-to-end smoke test: sign in → view feed → donate → earnings record updated → verified creator withdraws → audit log recorded

---

## 10. Explicitly Deferred (see BRD v2 §20 for full roadmap)

- Guardian/Guarantor workflow
- Tiered KYC + donation caps
- Trust Reserve Fund
- Community Issues Reporting
- CSR module, volunteer matching, public profiles
- Fraud state machine beyond Active → Suspended
- Module extraction into standalone services (only if a specific module needs independent scaling)