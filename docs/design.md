# System Design

This document describes the backend architecture, data model, scheduling strategy, and offline-first behaviors for the CivicWorks Community Services platform.
It explicitly answers each item in `docs/questions.md` (questions 1-15) with concrete design decisions.

## 1. Architectural Overview

### Technology

* Backend: Spring Boot (REST APIs)
* Persistence: JPA with transactional persistence
* Durable local storage: PostgreSQL
* Deployment: single-node Docker (offline-friendly)

### High-Level Modules

1. Identity & RBAC
2. Content & Resource Management (content publish/unpublish, publish history snapshots, comments)
3. Moderation (sensitive-word dictionary, moderation thresholds, moderator actions)
4. Billing Center (fee setup, billing runs, late fee, discounts)
5. Payments & Settlement (payments, split settlement rounding, refunds)
6. Dispatch (dispatch orders, acceptance constraints, zone capacity queues)
7. Reconciliation (end-of-day handover, discrepancies > $1.00 workflow)
8. Search (typeahead, search history retention)
9. Notifications (offline-only records + receipts)
10. Audit Logs

## 2. Offline-First Sync and Conflict Resolution

### Client Change-Log Model

Offline-capable clients record intended mutations as a local operation log.
When connectivity exists, they replay operations to the backend.

### Idempotency

All mutating endpoints require `Idempotency-Key`.
Server stores results keyed by `(user_id, idempotency_key, action_type)` so retries are safe across restarts.

### Optimistic Concurrency Control (Questions 1)

For critical entities, all state-changing requests include an `entity_version` (optimistic locking).

Rules:
* For mismatched versions:
  * Reject with `409 Conflict` and provide:
    * server current version
    * server current state summary
    * client operation type
  * Client then routes to manual reconciliation screens or replays after refreshing state.

Scope:
* Apply strict version checking to `Bill`, `Payment`, `DispatchOrder`, and publish/unpublish transitions.

This avoids risky last-write-wins overwrites and forces domain-aware reconciliation for money and assignment workflows.

## 3. Scheduling and Time Semantics (Questions 2, 6, 7, 15)

### Local Scheduler

All timed behaviors use a local scheduler such as Quartz:
* Jobs are persisted so they survive restart.
* Scheduler triggers use local system time only.
* On restart, the scheduler recovers persisted jobs.

### Scheduled Publishing (Questions 2)

* `ContentItem.scheduled_at` stores local-time intent.
* A scheduled job is created when the item is scheduled.
* If the system was offline or restarted:
  * On recovery, the job checks whether `scheduled_at <= now_local` and the item is still not published.
  * If due and allowed, publish immediately (once) and record publish history.

### Billing Runs at 12:05 AM (Question 6)

* A daily/periodic job triggers billing at `12:05 AM` local time for each `billing_cycle`.
* If generation fails:
  * The job retries with backoff.
  * Bill generation uses idempotency keys (e.g., `billingRun:cycleDate:billingCycle`) so retries do not duplicate A/R.

### Late Fees at 10 Days, Applied Once (Question 7)

Late fee application is done by a scheduled job that:
* Selects bills whose due date passed grace period:
  * `now_local >= bill.due_date + 10 days`
* Applies late fee exactly once:
  * Introduce `late_fee_applied_at` or `late_fee_event` table to prevent continuous application.
* Cap late fee per bill at $50.

### Search History Retention (Question 15)

* A cleanup job runs daily and deletes `search_history` rows older than 90 days.
* Retention enforcement is deterministic and does not rely on application-layer logic.

## 4. Moderation and Content Rules (Questions 3, 4, 5)

### Sensitive-word Dictionary Scope (Question 3)

Dictionary entries are stored with:
* `organization_id` scope
* supported matching forms:
  * exact match
  * case-insensitive variants

Override policy:
* Organization admins can add/remove entries without affecting other tenants.

### Comment Moderation Thresholds (Question 4)

When creating a comment:
* Compute `filter_hit_count`.
* If `filter_hit_count == 1`:
  * `moderation_state = FLAGGED`
* If `filter_hit_count >= 2`:
  * `moderation_state = HOLD`

Moderator actions update comment state and write `AuditLog`.

### Immutable Publish History Granularity (Question 5)

Publish/unpublish creates immutable history entries with full snapshots:
* Store `ContentItem` snapshot fields at each transition:
  * title
  * sanitized body HTML
  * tags
  * scheduled_at/published_at
  * state and version

This provides an auditable record and avoids ambiguity of diff-based reconstruction.

## 5. Billing Rules (Questions 6, 7, 8)

### Billing Cycle Edge Cases (Question 6)

Billing generation at `12:05 AM` uses:
* persistent scheduler triggers
* idempotency keys to prevent duplicates
* retry logic to avoid missing A/R when failures occur

### Late Fee Timing (Question 7)

Late fee is applied once after the grace period:
* The fee event is recorded with `late_fee_applied_at`.
* Subsequent job runs skip bills that already have an event recorded.

### Discount Conflicts (Question 8)

Enforce single discount per bill:
* Model constraint: `bill_discounts` has a unique constraint for `bill_id`.
* Discount application endpoint rejects a second discount (or requires explicit replacement with clear semantics).

Minimum bill total:
* Discount application never reduces below $0.00.

## 6. Dispatch, Eligibility, and Capacity (Questions 9, 10, 11)

### Driver Eligibility Timing (Question 9)

The "15 minutes online today" rule is cumulative:
* Track `driver_online_session`:
  * `driver_id`
  * session start/end timestamps (local time)
* Daily aggregation job computes:
  * total online minutes per driver per day
* Eligibility endpoint reads the aggregated total.

### Forced Dispatch Rejection Handling (Question 10)

When a driver rejects a forced dispatch:
* Mark `DispatchOrder` with `rejection_reason` and status `REJECTED`.
* Create a cooldown record for `(driver_id, order_id or zone_id)`:
  * `cooldown_until = now_local + 30 minutes`
* Reassignment queue selects eligible drivers excluding:
  * rejecting driver while cooldown is active

### Zone Capacity Enforcement (Question 11)

Each zone has concurrent capacity (`max_concurrent_orders`):
* When creating dispatch orders:
  * if capacity available: assign/create active order
  * if capacity reached: create queued order record with `queue_position`

A dispatcher/worker advances queued orders when capacity frees up.

## 7. Payments, Splits, and Refunds (Questions 12, 13)

### Payment Split Rounding (Question 12)

Even split rounding to nearest $0.01 is implemented by cents arithmetic:
* Compute each payer's base cents amount via integer division.
* Compute remainder cents.
* Allocate remainder cents to the first payer in deterministic order:
  * smallest `payer_seq`

This guarantees totals match bill amount exactly.

### Refund and Reversal Constraints (Question 13)

Partial refunds are supported:
* Refunds create new reversal transactions linked to the original payment.
* Validation:
  * sum of refunds per payment must not exceed original amount (minus existing refunds)

This supports both full reversals and partial refunds with traceability.

## 8. Reconciliation Ownership (Question 14)

Discrepancies workflow for end-of-day handover:
* When handover totals differ from posted A/R by more than $1.00:
  * Create a `discrepancy_case` with:
    * delta amount
    * related bills/payments references
    * status `OPEN`
  * Assign to `BILLING_CLERK`
  * Require `AUDITOR` oversight for final sign-off (read-only review plus approval endpoint).

## 9. Data Model (Core Tables)

The following are the essential fields needed to implement the rules above.

### Common

* `version` (integer) on entities requiring optimistic locking
* `created_at`, `updated_at`
* `organization_id` where dictionary/moderation scope matters

### Publish History

* `content_item_publish_snapshots`
  * `content_item_id`
  * snapshot payload fields
  * transition type: `PUBLISHED|UNPUBLISHED`
  * snapshot `version`
  * `created_at`

### Moderation

* `comments`
  * `moderation_state`
  * `filter_hit_count`

### Billing

* `billing_runs`
  * `cycle_date`, `billing_cycle`
  * `status`
  * idempotency key reference

* `bills`
  * `status`, `balance_cents`, `due_date`, `version`

* `late_fee_events`
  * `bill_id`
  * `applied_at`
  * `late_fee_cents`

### Discounts

* `bill_discounts`
  * `bill_id` (unique)
  * discount type and value

### Dispatch

* `dispatch_orders`
  * `status`
  * `assigned_driver_id`
  * `forced_flag`
  * `rejection_reason`
  * `version`

* `driver_online_session`
  * driver id + start/end

* `driver_daily_online_aggregate`
  * driver id + date + total_minutes

* `zone_queues`
  * `zone_id`, `order_id`, `queue_position`, `status`

### Payments and Refunds

* `payments`
  * `bill_id`, `payment_method`, `amount_cents`, `received_at`, `shift_id`

* `refunds`
  * `payment_id`, `refund_amount_cents`, `reason`, `created_at`

### Reconciliation

* `discrepancy_cases`
  * delta amount
  * assigned billing clerk user id
  * auditor review status

### Search History

* `search_history`
  * `user_id`, `query`, filters, `created_at`
  * cleanup removes rows older than 90 days

## 10. Audit and Observability

### AuditLog

Every moderator action, billing run state change, payment posting, and dispatch assignment/rejection writes an `AuditLog` row:
* `actor_id`
* `action`
* `entity_ref`
* `timestamp`

### Logging and Metrics

Structured logs and KPI/report generation stored locally:
* detect anomalies such as arrears increasing > 15% week-over-week

## 11. How Each Question Is Addressed

This is a direct mapping from `docs/questions.md` to the design choices in this document:

1. Optimistic locking + conflict detection for offline conflicts (reject and reconcile)
2. Local-time scheduling using persisted local Quartz jobs with restart recovery
3. Sensitive-word dictionary scoped by `organization_id`
4. Comment moderation: 1 hit => `FLAGGED`, 2+ hits => `HOLD`
5. Publish history stores full snapshots on publish/unpublish
6. Billing run failures at 12:05 AM trigger retry with idempotency keys
7. Late fee applied once after grace period; capped at $50
8. Only one discount per bill enforced by model constraints
9. Driver eligibility uses cumulative online minutes for the day
10. Forced dispatch rejection triggers reassignment with 30-minute cooldown exclusion
11. Zone capacity uses queues when capacity is reached
12. Split settlement rounding: remainder cents to first payer
13. Partial refunds supported via linked reversal transactions
14. Discrepancy cases (> $1.00) assigned to Billing Clerk with Auditor oversight
15. Search history enforced by daily cleanup job (delete older than 90 days)

