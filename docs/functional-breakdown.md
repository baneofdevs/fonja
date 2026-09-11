# Fonja: Current Functionality Breakdown

Updated: 2026-08-31

This document summarises what is implemented in the current codebase and is intended to help stakeholders understand the platform as it exists right now.

## Executive summary

The platform is currently a working crowdfunding backend with:

- Google OAuth-based sign-in and JWT-protected API access
- user profile management
- creator verification and document review workflow
- campaign creation, editing, image management, launch, and admin review
- donor checkout via CyberPay
- donation settlement and ledger tracking
- admin reporting for platform revenue

The project is not yet complete end-to-end for all business modules. Notable gaps include payout requests/bank linking, broader public discovery flows, and notification delivery features.

## Current platform scope

| Area | Current status | Notes |
|---|---|---|
| Authentication | Live | Google OAuth2 login with internal HS256 JWTs for `/api/**` calls |
| User accounts | Live | Profile retrieval for authenticated users |
| Creator verification | Live | Submit verification request, upload documents, admin approve/reject |
| Campaign management | Live | Draft to review to active lifecycle with creator and admin actions |
| Donations | Live | Hosted checkout via CyberPay + webhook settlement |
| Earnings / ledger | Live | Read-only earnings and platform revenue view for user/admin |
| Payouts | Not yet implemented | Controller is a TODO; bank account and withdrawal flow are not wired up |
| Notifications | Not yet implemented | Entities exist, but delivery flow is not built |
| Public campaign discovery feed | Not yet implemented | Current focus is owner/admin actions and donation mechanics |

## Functional modules in detail

### 1) Authentication and access control

Implemented in the security layer:

- `GET /oauth2/authorization/google` initiates Google login
- `GET /login/oauth2/code/google` handles the OAuth callback
- All `/api/**` endpoints are stateless and require a bearer JWT
- JWT roles are mapped from the `roles` claim to Spring `ROLE_*` authorities
- `/api/v1/*/admin/**` endpoints require `ROLE_ADMIN`
- `/api/v1/donations/webhook/**` is explicitly permitted without user auth because CyberPay signs the webhook independently

Business rules currently enforced:

- Donors only need a registered account to donate
- Campaign creation and publishing require a verified creator
- Verification is a one-time gate for creators
- Apple sign-in is not registered yet

### 2) User accounts

Available functionality:

- `GET /api/v1/users/me` returns the authenticated user profile

Current behaviour:

- No public user profile endpoint is currently exposed
- The system deliberately does not support cross-user lookup in this phase

### 3) Creator verification workflow

Available functionality:

- `POST /api/v1/verification` submits a verification request
- `GET /api/v1/verification/me` fetches the latest request for the authenticated user
- `POST /api/v1/verification/{requestId}/documents` uploads supporting documents
- `GET /api/v1/verification/admin/requests` shows the admin review queue
- `GET /api/v1/verification/admin/requests/{requestId}` fetches a specific request
- `POST /api/v1/verification/admin/requests/{requestId}/approve` approves a request
- `POST /api/v1/verification/admin/requests/{requestId}/reject` rejects a request
- `GET /api/v1/verification/admin/documents/{documentId}` downloads a stored document for review

Key rules:

- Only one pending verification request per user is allowed
- Business and NGO verification require a registration number
- Individual verification is based on document upload rather than registration number
- Approved verification marks the user as verified and grants creator privileges in the same transaction
- Uploaded files are validated by document signature / type detection before storage

### 4) Campaign management

Available functionality:

- `POST /api/v1/campaigns` creates a campaign draft
- `GET /api/v1/campaigns/me` lists the authenticated creator's campaigns
- `GET /api/v1/campaigns/{campaignId}` fetches one owned campaign
- `PATCH /api/v1/campaigns/{campaignId}` updates campaign details
- `POST /api/v1/campaigns/{campaignId}/images` adds an image
- `DELETE /api/v1/campaigns/{campaignId}/images/{imageId}` removes an image
- `POST /api/v1/campaigns/{campaignId}/launch` submits a draft for review
- `DELETE /api/v1/campaigns/{campaignId}` deletes a campaign
- `GET /api/v1/campaigns/admin/review-queue` returns campaigns pending admin review
- `POST /api/v1/campaigns/admin/{campaignId}/approve` approves a campaign
- `POST /api/v1/campaigns/admin/{campaignId}/reject` rejects a campaign with a reason

Campaign lifecycle supported in code:

- `DRAFT`
- `PENDING_REVIEW`
- `ACTIVE`
- `COMPLETED`
- `CANCELLED`
- `EXPIRED`
- `SUSPENDED`

Important business rules:

- Campaign content fields remain editable until the campaign ends
- Goal, currency, and deadline are frozen once a campaign leaves draft
- Donations are accepted only for `ACTIVE` campaigns
- Launch is tied to creator verification readiness
- Campaigns cannot accept donations after deadline or if they are not active

### 5) Donations and payment flow

Available functionality:

- `POST /api/v1/donations` initiates a donation checkout session
- `POST /api/v1/donations/webhook` accepts CyberPay webhook callbacks

Donation flow:

1. User submits donation request for an active campaign
2. Donation record is created in a pending state with a generated merchant reference
3. The backend opens a hosted CyberPay checkout session
4. Donor completes payment on CyberPay
5. CyberPay notifies the webhook endpoint
6. Backend verifies the signature and re-queries CyberPay to confirm final status
7. Donation is settled and ledger entries are created if successful

Key protections in current implementation:

- Webhooks are verified using the CyberPay signature header
- Duplicate webhook deliveries are tolerated via idempotent settlement logic
- The system re-queries CyberPay before crediting money, rather than trusting the webhook alone
- A pending donation sweep job re-checks donations that remain unresolved

### 6) Earnings and ledger

Available functionality:

- `GET /api/v1/earnings` returns the authenticated creator's earnings summary
- `GET /api/v1/earnings/admin/platform-revenue` returns platform revenue for admin use

Ledger rules implemented:

- Ledger entries are append-only
- User balances are derived from ledger entries rather than stored as mutable balances
- The system tracks the money path in a controlled, auditable way
- Donations and withdrawals are tied to ledger logic rather than direct wallet mutation

### 7) Admin operations

Admin routes currently include:

- campaign review queue and approve/reject
- verification review queue and approve/reject
- platform revenue lookup

Admins are granted authority via the JWT roles claim and enforced by `SecurityConfig`.

## What is not available yet

The following functionality is not currently implemented in the codebase as a working feature set:

- Payout request creation and approval flow
- Linking and managing bank accounts for creators
- Withdrawal request lifecycle
- Public campaign discovery / listing pages for donors
- User-to-user public profiles
- Notification delivery via email, SMS, or push
- Extended creator dashboard features beyond current campaign/earnings basics

## Current practical state for stakeholders

The platform is currently operational for:

- authenticated user sign-in
- creator onboarding and verification
- campaign drafting and review
- live donation collection through CyberPay
- financial reporting for earnings

It is not yet a complete end-to-end crowdfunding product with payouts, public marketplace browsing, and notification workflows.

## Suggested stakeholder message

The current release is best described as a functional crowdfunding backend foundation with verified creator onboarding, campaign workflows, and payment settlement. The next major milestones are payout processing, public discovery features, and outbound notifications.
