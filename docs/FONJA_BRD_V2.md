# Business Requirements Document (BRD)
# Fonja — Community Crowdfunding Platform

| | |
|---|---|
| **Version** | 2.0 (trimmed against Crowdr case study) |
| **Status** | Draft |
| **Prepared for** | Fonja Limited |

**Change note from v1:** This revision deliberately narrows scope for launch. Guardian/Guarantor workflow, tiered KYC with per-tier caps, the Trust Reserve Fund, and Community Issues Reporting are moved to the future roadmap — not because they're bad ideas, but because Crowdr (our closest local comparable) proves a leaner trust model works, and v1 should validate the core loop before adding layers on top of it.

---

## 1. Introduction

Fonja is a digital crowdfunding platform for Nigeria, enabling individuals, NGOs, and businesses to raise funds for verified causes, while donors contribute securely via card, bank transfer, and other methods supported by CyberPay.

Fonja digitizes Nigeria's culture of collective giving through transparency, campaign verification, and real-time donation tracking. Donations and platform operations run through **CyberPay's licensed payment infrastructure**. A per-transaction fee model (Section 4) funds platform operations.

The platform supports web applications; a user can act simultaneously as a campaign creator and a donor.

## 2. Business Objectives

- Create a trusted crowdfunding ecosystem for Nigerians.
- Reduce fraud and increase donor confidence through campaign verification.
- Provide transparent fundraising mechanics.
- Enable individuals and organizations to raise funds digitally.
- Provide card, bank transfer, and other CyberPay-supported payment options.
- Support diaspora/international donations.
- Operate a sustainable, transparent, fee-based revenue model.

## 3. Business Problem Statement

- **Lack of transparency** — donors have little visibility into fund use.
- **Lack of accountability** — fundraisers often lack verifiable identity or oversight.
- **Fragmented donation processes** — spread across bank transfers, social media, and cash collection.
- **Limited access** — no structured fundraising tools for individuals/organizations needing urgent support.
- **Trust deficit** — fraud fears keep potential donors from contributing.

## 4. Business Model & Revenue Structure

### 4.1 Platform Fee — 7% on Donations
Deducted per donation, at the point of processing.

| Item | Value |
|---|---|
| Donation amount | ₦10,000 |
| Fonja platform fee (7%) | ₦700 |
| Credited to creator's available earnings | ₦9,300 |
| Credited to Fonja's revenue | ₦700 |
| Shown on campaign progress bar | ₦10,000 (gross) |

Campaign "amount raised" is always the gross figure; the net (post-fee) amount is what's available for the creator to withdraw.

### 4.2 Withdrawal Fee — Flat ₦200
Deducted from the withdrawal amount before payout.

| Item | Value |
|---|---|
| Withdrawal requested | ₦9,300 |
| Withdrawal fee | ₦200 |
| Paid to creator's bank account | ₦9,100 |

### 4.3 Per-Transaction Processing
The 93/7 split applies immediately, per donation — no campaign-end cutoff. A campaign's target date is informational only; it doesn't affect how money moves.

## 5. Platform Users

### 5.1 Donor
Capabilities: register, browse/search campaigns, donate via card or bank transfer, save favorite campaigns, track donation history, receive receipts.
No verification is required to donate — an account (email/phone, OTP-confirmed) is sufficient. *(Simplified from v1's tiered donation caps — Crowdr does not gate donation on multi-level KYC.)*

### 5.2 Campaign Creator
A user who creates campaigns. Must complete **campaign creator verification** (Section 7) before a campaign can go live.
Capabilities: create campaigns, upload supporting/evidence documents, receive donations, withdraw funds, post updates, view donor analytics.

### 5.3 Corporate / NGO
Organizations raising funds or running CSR initiatives. Verification requires legal registration number + evidence of activity (see Section 7). *(CSR module detail: Section 15, deferred to Phase 2.)*

### 5.4 System Administrators
Capabilities: manage users, review and approve/reject campaign verification requests, review campaigns, manage fraud reports, generate reports, manage platform content.

*(Guardian/Guarantor role removed from v1 — see Section 23, Future Roadmap.)*

## 6. Feature: User Registration & Onboarding

**User Story:** As a new user, I want to create an account so I can donate or raise funds.

**Acceptance Criteria**
- User selects Register; enters First Name, Last Name, Email, Phone, Password.
- User receives and validates OTP.
- User creates a transaction PIN.
- User accepts Terms and Conditions.
- System creates account.
- User is redirected to dashboard.

**Alternate Scenarios:** invalid/expired OTP, existing account, weak password.

## 7. Feature: Campaign Creator Verification

**User Story:** As a user, I want to verify my identity so donors trust my campaign and I can raise funds.

*(Replaces v1's three-tier KYC system with a single verification gate, matching Crowdr's model: verification is required to create a campaign, not to donate.)*

**Acceptance Criteria**
- User selects Individual, Business, or NGO verification type.
- **Individual:** submits government-issued ID + supporting evidence relevant to the cause (e.g., medical bill, tuition letter, official document).
- **Business/NGO:** submits legal registration number + evidence of activity.
- Request enters an admin review queue.
- Admin approves or rejects.
- On approval, user gains "Verified Creator" status and can create/publish campaigns.

**Alternate Scenarios:** invalid documents, duplicate request.

*(International/diaspora creator verification is deferred — flagged as a Phase 2 item in Section 23.)*

## 8. Feature: Payment Processing & Withdrawals

**User Story:** As a user, I want to donate or receive funds through a secure payment gateway, without needing to manage a separate wallet.

*(Simplified from v1/v2's wallet abstraction — donations and payouts go directly through CyberPay. Fonja keeps an internal earnings record per creator for withdrawal purposes, but does not expose a wallet product: no top-up, no wallet-to-wallet transfer.)*

### 8.1 Payment Architecture
- **CyberPay**, a licensed processor, handles all fund movement — donation collection and creator payout.
- Fonja does not hold or display a wallet balance to users. Internally, Fonja records each creator's **available earnings** (gross donations received, minus platform fee, minus any prior withdrawals) purely for withdrawal accounting and reporting.
- Legal characterization of the CyberPay relationship to be confirmed with CyberPay's compliance team and Fonja's legal counsel (Section 19).

### 8.2 Supported Donation Payment Methods (via CyberPay)
- Debit/Credit Card
- Bank Transfer (via NIBSS)
- Other methods CyberPay supports natively (to be confirmed against CyberPay's current gateway offering)

### 8.3 Donation Flow
Donor pays directly at the point of donation via CyberPay → payment confirmed → 93/7 split recorded → 93% added to creator's available-earnings record, 7% recorded as Fonja revenue.

### 8.4 Withdrawal Flow
- A **verified** creator can withdraw their available earnings **at any time, with no per-withdrawal approval step** — verification happens up front, at campaign-creator onboarding, not per transaction.
- Paid to a bank account linked during verification; account name should match verified identity.
- Settlement via CyberPay/NIBSS.
- Flat ₦200 fee applies (Section 4.2).

## 9. Feature: Campaign Creation

**User Story:** As a user, I want to build a fundraising campaign with minimal friction, and only deal with verification at the point I'm ready to publish.

*(Flow pattern adopted from GoFundMe's onboarding wizard — low-friction to start, hard gates deferred to the end.)*

### 9.1 Creation Wizard (no account required until step 7)

1. Fund destination (country) + category.
2. Who the funds are for (self / someone else / charity).
3. Fundraising goal amount — system suggests a starting goal based on similar campaigns (auto-adjusting goal, toggleable).
4. Cover photo/video + optional additional images.
5. Story — free text, with AI-assisted enhancement (tone, grammar, word choice, structure).
6. Title.
7. **Sign in** — Google, Apple, or email. Account is created here if the user is new; skipped if already signed in.
8. **Review screen** — every field editable; campaign is saved as **Draft**.

### 9.2 Launch Gate

- Hitting **Launch** is what triggers the verification requirement — not any earlier step.
- If the user isn't yet a Verified Creator (Section 7), Launch instead prompts: "Verify to publish this campaign," collecting evidence documents matched to the chosen category (medical bill, tuition letter, business/NGO registration, etc.).
- On verification submission, campaign moves to **Pending Review** (Section 11.2); Admin approves → **Active**.
- If the user is already a Verified Creator (from a prior campaign), Launch skips straight to **Pending Review** — no repeat document upload.

**Rationale:** this maximizes the number of people who complete a nearly-full campaign before hitting any friction, matching GoFundMe's observed conversion pattern, while still enforcing Fonja's verification-before-live requirement (Section 7) at the one point it's unavoidable.

**Categories:** Medical, Education, Emergency, NGO, Community Development, Rehabilitation, Business Support, CSR, Religious Causes.

## 10. Feature: Donations

**User Story:** As a donor, I want to support campaigns securely.

**Acceptance Criteria**
- User selects a campaign, clicks Donate, enters amount.
- User selects payment method: Bank Transfer or Card (per CyberPay's supported options, Section 8.2).
- System displays a summary including the 7% platform fee disclosure.
- User confirms; payment processed via CyberPay.
- On success, the donation is recorded and split: 93% credited to the creator's available-earnings record, 7% recorded as Fonja revenue.
- Campaign progress updates instantly with the gross amount.
- Donation is recorded (donor, campaign, amount, timestamp, method); receipt generated.

**Alternate Scenarios:** insufficient funds, gateway timeout, declined transaction (no earnings record created; donor shown retry).

## 11. Campaign Funding Model & Lifecycle

**User Story:** As a creator, I want to keep whatever funds my campaign raises, whether or not I reach my goal.

### 11.1 Flexible Funding — "Keep What You Raise"
No all-or-nothing threshold. Whatever is raised is available to the creator.

### 11.2 Campaign Lifecycle States

*(Simplified from v1's seven states to four for launch; Suspended is added only once fraud response tooling exists.)*

| State | How reached | Donations accepted? | Withdrawals allowed? | Visible on feed? |
|---|---|---|---|---|
| **Draft** | Creator filling form, not submitted | No | N/A | No |
| **Pending Review** | Submitted; awaiting Admin approval | No | No | No |
| **Active** | Approved by Admin | Yes | Yes (verified creator) | Yes |
| **Closed** | Creator or Admin manually stops new donations | No | Yes (existing balance) | Shown as "Closed", archived |

### 11.3 Goal-Reached Messaging
Once a campaign passes its goal but remains Active, prompt the creator to add a note (e.g., "Goal reached — additional funds go toward X") to maintain donor trust.

## 12. Feature: Live Campaign Feed

**User Story:** As a donor, I want to browse active campaigns.

**Acceptance Criteria**
- Feed displays campaigns; users can search, filter, sort.
- Feed shows: goal amount, amount raised (gross), number of donors, verification status, lifecycle state badge.

## 13. Feature: Notifications

System sends notifications for: donations made/received, campaign approvals and updates, verification status changes, withdrawal requests and confirmations.

**Channels:** Push, Email, SMS.

## 14. Admin Portal

Administrators can: approve users/verification requests, approve campaigns, review withdrawals, manage fraud reports and campaign lifecycle states, view analytics, generate reports.

## 15. Reporting & Analytics

**User reports:** donation history, earnings/withdrawal history, campaign performance.
**Admin reports:** daily donations, active campaigns by state, fraud reports, verification queue status, revenue (platform fee + withdrawal fee income).

## 16. Fraud Prevention (Launch Scope)

*(Simplified from v1's full Suspended/Terminated/blacklist framework — kept lean until volume justifies the fuller machinery.)*

- Mandatory creator verification before a campaign can go live (Section 7).
- Mandatory evidence document upload.
- Admin review as the final gate before Active.
- **Donor reporting:** a "Report this campaign" action on every campaign page, routed to an Admin queue.
- Confirmed fraud → campaign is deactivated, further withdrawals blocked, case logged for potential referral to law enforcement.
- Full escrow/refund mechanics (e.g., a discretionary Trust Reserve Fund) are deferred — see Section 23.

## 17. Non-Functional Requirements

**Performance:** page response < 3s; payment processing < 10s.
**Security:** data encryption in transit and at rest, two-factor authentication, secure PIN management, PCI DSS alignment for any card data touched.
**Audit:** full transaction audit trail, user activity logs.

*(v1's explicit 5M-user / 100K-concurrent target is removed from launch NFRs — scale planning should be revisited once real usage data exists, not designed against upfront. See Section 24.)*

## 18. Integrations

**Payment:** CyberPay (primary), NIBSS.
**Notification:** SMS Gateway, Email Gateway, Push Notification Service.

## 19. Open Items — Needs Legal / Compliance Review

1. **CyberPay partnership scope** — confirm whether CyberPay's license covers Fonja's payment flow (direct pass-through donations and creator payouts) as described.
2. **NDPA compliance** — government ID and evidence documents require a documented data protection policy: retention, access control, deletion.
3. **AML/CFT obligations** — transaction monitoring thresholds and suspicious activity reporting for donations at scale.
4. **Consumer disclosure requirements** — whether the 7% + ₦200 fee structure needs a specific disclosure format under CBN consumer protection rules.

## 20. Future Roadmap

*(Everything below is acknowledged as good long-term direction, deliberately excluded from v1 to keep the foundation buildable in days, not weeks.)*

**Phase 2**
- Tiered KYC with donation/withdrawal caps (v1's original multi-tier model), once real fraud/volume data justifies the complexity.
- Campaign Guardian/Guarantor role — a trusted third-party sign-off, as an optional trust badge for high-value campaigns.
- CSR Module — business accounts, employee co-donation tracking.
- International/diaspora donor and creator verification, multi-currency support.
- Volunteer matching — connecting causes with people, not just money (validated by Crowdr's model).
- Public profiles — a giving/volunteering history page per user, for social proof and retention.

**Phase 3**
- Community Issues Reporting (fund community infrastructure with no single "creator" — ownership/governance model needs definition first).
- Trust Reserve Fund — discretionary goodwill refund pool, pending the compliance review in Section 19.
- Full fraud lifecycle: Suspended/Terminated states, creator blacklisting, automated fraud flags.
- Advanced fraud detection (AI-assisted).

**Phase 4**
- Pan-African expansion.
- Blockchain transparency ledger.

## 21. Success Metrics

- Registered users; verified creators.
- Total campaigns created; campaign success rate.
- Total donations processed; average donation value.
- Fraud report rate.
- User retention rate.

## Conclusion

Fonja aims to be a trusted, lean crowdfunding platform for Nigeria — validating the core donate/create/verify/payout loop first, with trust and social-impact features (Guardian sign-off, CSR, volunteer matching, community campaigns) layered in as the platform proves itself, rather than built speculatively upfront.