# API Specification

This document defines the REST API surface for the offline-first CivicWorks Community Services backend.
It is written to directly implement the business rules captured in `docs/questions.md` (numbered 1-15).

## 1. Conventions

### Base URL

`/api/v1`

### Authentication and Authorization (RBAC)

* Every request is authenticated and associated with a `user_id` and `role`.
* Access is controlled by role:
  * `SYSTEM_ADMIN`: global configuration and audits.
  * `CONTENT_EDITOR`: create/schedule content.
  * `MODERATOR`: comment review and sensitive-word moderation.
  * `BILLING_CLERK`: fee setup, billing runs, posting and reversals, reconciliation workflows.
  * `DISPATCHER`: zone capacity rules and forced assignments.
  * `DRIVER`: accept/decline work with constraints.
  * `AUDITOR`: read-only financial and activity reporting.

### Idempotency (Offline-safe)

Offline clients may retry requests when the network is unavailable or when connectivity resumes.
The server must treat the same logical operation as a single action.

* For all mutating requests (`POST`, `PATCH`, `PUT`, `DELETE`), clients MUST send:
  * `Idempotency-Key: <uuid>`
* Server behavior:
  * If the same key is seen again for the same authenticated user, return the original result (HTTP 200/201/204/409 as appropriate).
  * Idempotency keys MUST be persisted locally (PostgreSQL) so retries remain correct across restarts.

### Optimistic Concurrency Control (Conflict Detection)

To resolve offline-first conflicts safely, the API uses optimistic locking for critical entities.

* Entities that support conflict detection include: `ContentItem`, `Comment`, `Bill`, `Payment`, `DispatchOrder`.
* For updates/mutations that change state:
  * Client sends `entity_version` in the request body.
  * Server compares to current `version` stored in the database.
  * On mismatch:
    * Return `409 Conflict` with a payload describing the server’s current state and a suggested reconciliation path.

### Money Types

* Use integer cents everywhere in requests/responses:
  * `amount_cents`, `balance_cents`, `fee_cents`, `discount_cents`.
* When the UI operates with decimals, convert to cents client-side to avoid floating errors.

## 2. Error Model

All errors return JSON:

```json
{
  "error_code": "VALIDATION_FAILED",
  "message": "Human readable summary",
  "details": { }
}
```

Common `error_code` values:

* `UNAUTHORIZED`, `FORBIDDEN`
* `VALIDATION_FAILED`
* `CONFLICT_VERSION_MISMATCH`
* `IDEMPOTENCY_CONFLICT`
* `BUSINESS_RULE_VIOLATION`
* `SCHEDULE_NOT_ALLOWED`

## 3. Content and Resource Management

Rich-text is stored as sanitized HTML. The server must sanitize and store the final HTML.

### 3.1 Create Content Item

`POST /content/items`

Request:

```json
{
  "type": "NEWS|POLICY|EVENT|CLASS",
  "title": "string",
  "sanitized_body_html": "<html>...</html>",
  "tags": ["string"],
  "scheduled_at": "ISO-8601|null",
  "entity_version": 0
}
```

Response `201`:

```json
{
  "id": "content_item_id",
  "state": "DRAFT|SCHEDULED|PUBLISHED",
  "published_at": "ISO-8601|null",
  "version": 1
}
```

### 3.2 Query Content Items

`GET /content/items?type=&tag=&state=&from=&to=&q=`

Supports filtering and full-text search (server-side).

### 3.3 Update Content Item

`PATCH /content/items/{id}`

Request:

```json
{
  "title": "string|null",
  "sanitized_body_html": "<html>...</html>|null",
  "tags": ["string"]|null,
  "scheduled_at": "ISO-8601|null",
  "entity_version": 5
}
```

Response `200` includes updated `version`.

### 3.4 Schedule Publish

`POST /content/items/{id}/schedule`

Request:
```json
{
  "scheduled_at": "ISO-8601",
  "entity_version": 3
}
```

Server uses local time and persists a local scheduler job (see `docs/design.md`).

### 3.5 Publish (Immutable History Snapshot)

`POST /content/items/{id}/publish`

Behavior:
* On publish, store an immutable snapshot of the content item state for audit.
* Publish history stores snapshots (not diffs).

Request:
```json
{
  "publish_at": "ISO-8601|null",
  "entity_version": 3,
  "idempotency_reason": "string|null"
}
```

Response includes:
* `published_at`
* `publish_history_entry_id`
* updated `version`

### 3.6 Unpublish

`POST /content/items/{id}/unpublish`

Creates a new immutable publish history entry capturing the unpublish event and the snapshot at unpublish time.

### 3.7 Publish History

`GET /content/items/{id}/publish-history`

Returns ordered snapshot entries with timestamps.

## 4. Comments and Moderation

### 4.1 Create Comment (Threaded)

`POST /content/items/{contentId}/comments`

Request:
```json
{
  "parent_id": "comment_id|null",
  "content_text": "string",
  "entity_version": 0
}
```

Server-side sensitive-word filtering:
* Dictionary is scoped by `organization_id` from the authenticated context.
* Matching is exact and case-insensitive variants.

Moderation thresholds:
* `filter_hit_count == 1` => `moderation_state = "FLAGGED"` (still visible to posting workflow as allowed, but marked for review).
* `filter_hit_count >= 2` => `moderation_state = "HOLD"` (held for review).

Response `201`:
```json
{
  "comment_id": "id",
  "moderation_state": "FLAGGED|HOLD|APPROVED",
  "filter_hit_count": 0,
  "version": 1
}
```

### 4.2 Query Comments

`GET /content/items/{contentId}/comments?state=&threaded=true`

Returns moderation-filtered views based on caller role.

### 4.3 Moderate Comment

`POST /comments/{commentId}/moderate`

Request:
```json
{
  "action": "APPROVE|HOLD|REJECT|UNHOLD",
  "moderator_notes": "string|null",
  "entity_version": 2
}
```

Requires `MODERATOR`.

## 5. Sensitive Word Dictionary

### 5.1 Add or Update Word

`POST /moderation/sensitive-words`

Request:
```json
{
  "word": "string",
  "replacement": "string|null",
  "entity_version": 0
}
```

Dictionary is scoped per `organization_id`.

### 5.2 Query Words

`GET /moderation/sensitive-words`

Requires `MODERATOR` or `SYSTEM_ADMIN`.

## 6. Billing and A/R

### 6.1 Fee Item Setup

`POST /billing/fee-items`

Request:
```json
{
  "code": "string",
  "calculation_type": "FLAT|PER_UNIT|METERED",
  "rate_cents": 1200,
  "taxable_flag": true
}
```

### 6.2 Run Billing (Idempotent, Offline-safe)

`POST /billing/billing-runs`

Request:
```json
{
  "cycle_date": "YYYY-MM-DD",
  "billing_cycle": "MONTHLY|QUARTERLY",
  "idempotency_reason": "string|null",
  "requested_by": "string|null"
}
```

Behavior:
* Billing generation is scheduled for `12:05 AM` on `cycle_date` using local time.
* If billing generation fails at `12:05 AM`, the billing job MUST retry (idempotent) to avoid missing A/R.

Response:
```json
{
  "billing_run_id": "id",
  "status": "PENDING|RUNNING|SUCCESS|FAILED",
  "created_at": "ISO-8601"
}
```

### 6.3 Query Bills

`GET /billing/bills?account_id=&cycle_date=&status=&q=`

### 6.4 Apply Late Fee (Single Application)

Late fee rules:
* After 10-day grace period, apply 5% late fee.
* Apply once (not continuously).
* Cap late fee at $50 per bill.

`POST /billing/bills/{billId}/late-fee/apply`

Request:
```json
{
  "entity_version": 4,
  "applied_for_date": "YYYY-MM-DD|null"
}
```

### 6.5 Discount (Single Discount per Bill)

Only one discount may be applied to a single bill.

`POST /billing/bills/{billId}/discount`

Request:
```json
{
  "discount_type": "PERCENTAGE|FIXED",
  "value_cents_or_basis_points": 5000,
  "entity_version": 2
}
```

Server validation:
* If a discount already exists for the bill and the request would create another conflicting discount, return `409` or `422 BUSINESS_RULE_VIOLATION`.
* Discounts cannot reduce bill below $0.00.

## 7. Payments and Settlement

Payment methods supported offline:
* `CASH|CHECK|VOUCHER|OTHER`

### 7.1 Create Settlement (Full or Split)

`POST /billing/bills/{billId}/settlements`

Request:
```json
{
  "shift_id": "string",
  "settlement_mode": "FULL|SPLIT_EVEN|SPLIT_CUSTOM",
  "discount_allocation_strategy": "PROPORTIONAL",
  "allocations": [
    { "payment_method": "CASH|CHECK|VOUCHER|OTHER", "amount_cents": 1234, "payer_seq": 1 }
  ],
  "entity_version": 3
}
```

Rounding rule (even split):
* When splitting evenly produces rounding discrepancies:
  * Allocate remainder cents to the first payer (`payer_seq` lowest).

Discount allocation:
* Allocate discounts proportionally across allocations.

Response:
```json
{
  "settlement_id": "id",
  "payment_ids": ["..."],
  "balance_cents": 0,
  "version": 4
}
```

### 7.2 Refund / Reversal (Partial Supported)

`POST /billing/payments/{paymentId}/refunds`

Request:
```json
{
  "refund_amount_cents": 5000,
  "reason": "string|null",
  "entity_version": 2
}
```

Behavior:
* Partial refunds are allowed.
* Refunds are linked to the original payment and validated so totals remain consistent.

### 7.3 Ledger and Reconciliation

`GET /billing/bills/{billId}/ledger`

Used by auditing and discrepancy resolution workflows.

### 7.4 End-of-Day Shift Handover and Discrepancies

`POST /billing/shifts/{shiftId}/handover`

Used by `BILLING_CLERK` to submit end-of-day totals by payment method for a shift.

Request:
```json
{
  "totals_by_method": [
    { "payment_method": "CASH|CHECK|VOUCHER|OTHER", "total_amount_cents": 12345 }
  ],
  "entity_version": 0
}
```

Server behavior:
* Compare submitted totals against posted A/R for the shift.
* If absolute delta > $1.00, create a `discrepancy_case` and:
  * assign it to `BILLING_CLERK`
  * require `AUDITOR` oversight (read-only review + explicit sign-off)

Response:
```json
{
  "handover_id": "id",
  "discrepancy_case_id": "id|null",
  "status": "RECORDED|DISCREPANCY_OPEN"
}
```

`GET /billing/discrepancies?status=&from=&to=`

Requires `BILLING_CLERK` (and `AUDITOR` can view without mutation).

`POST /billing/discrepancies/{caseId}/resolve`

Request:
```json
{
  "resolution": "APPROVED|REJECTED|NEEDS_MORE_INFO",
  "notes": "string|null",
  "entity_version": 0
}
```

Requires `AUDITOR` to finalize sign-off.

## 8. Dispatch Orders and Driver Eligibility

Acceptance constraints:
* Max 3 miles (distance computed from driver location vs pickup/drop-off).
* Minimum driver rating: 4.2/5.0.
* Driver must have at least 15 minutes online today (cumulative).

### 8.1 Create Dispatch Order

Two modes:

* Grab-order (driver selects an available order)
* Dispatcher-assigned order (dispatcher assigns a forced dispatch)

`POST /dispatch/orders`

Request:
```json
{
  "mode": "GRAB|DISPATCHER_ASSIGNED",
  "zone_id": "string",
  "forced_flag": true,
  "rejection_reason_enum": null,
  "entity_version": 0
}
```

Zone capacity enforcement:
* If zone is at capacity, new orders are queued rather than dropped.

Server returns:
* `status = QUEUED` with queue position when capacity is reached.

### 8.2 Driver Eligibility Check

`GET /drivers/{driverId}/eligibility?date=YYYY-MM-DD`

Server aggregates driver online session logs to compute cumulative online minutes for the day.

### 8.3 Accept Dispatch Order

`POST /dispatch/orders/{orderId}/accept`

Requires:
* eligibility constraints pass
* optimistic concurrency version check when changing assignment state

### 8.4 Reject Dispatch Order (Forced Dispatch Cooldown)

`POST /dispatch/orders/{orderId}/reject`

Request:
```json
{
  "reason": "FORCED_DISPATCH_REJECTION_ENUM_VALUE",
  "entity_version": 5
}
```

Behavior:
* For forced dispatches, after a driver rejects:
  * Reassign to another eligible driver.
  * Exclude rejecting driver for 30 minutes.

### 8.5 Dispatch Order Status

`GET /dispatch/orders/{orderId}`

## 9. Zone Capacity Queues

`GET /dispatch/queues?zone_id=&status=`

Dispatcher/clients can monitor queue progression while offline clients reconnect.

## 10. Search and Search History Retention

### 10.1 Typeahead Search

`GET /search/typeahead?q=&filters=...`

### 10.2 Record Search History

`POST /search/history`

Request:
```json
{
  "query": "string",
  "filters": { },
  "entity_version": 0
}
```

### 10.3 Retention (90 days)

Search history retention is enforced by a scheduled cleanup job that deletes records older than 90 days.

Endpoint:
* `GET /search/history/retention` (read-only config/status; admin/moderator only)

## 11. Notifications (Offline Only)

No email/SMS/IM network sending is performed by the backend.
Channels are configurable but disabled by default and only write to an outbox table.

`GET /notifications`

`POST /notifications/{notificationId}/ack`

## 12. Audit Logs

`GET /audit/logs?entity_ref=&from=&to=&action=&q=`

## 13. Question-to-API Mapping (1-15)

1. Offline-first conflict resolution: optimistic locking via `entity_version` + `409 Conflict` for critical entities.
2. Scheduled publishing in offline mode: `POST /content/items/{id}/schedule` uses local-time scheduler with persisted/recovered jobs.
3. Sensitive-word filtering scope: sensitive words are scoped by `organization_id` and used during comment creation.
4. Comment moderation thresholds: `POST /content/items/{contentId}/comments` sets `FLAGGED` for 1 hit and `HOLD` for 2+ hits.
5. Immutable publish history granularity: publish/unpublish creates snapshot-based history entries.
6. Billing cycle edge cases: `POST /billing/billing-runs` is idempotent; the `12:05 AM` scheduled run retries on failure.
7. Late fee calculation timing: `POST /billing/bills/{billId}/late-fee/apply` applies once and is capped.
8. Discount application conflicts: `POST /billing/bills/{billId}/discount` enforces single-discount-per-bill.
9. Driver eligibility calculation timing: `GET /drivers/{driverId}/eligibility` uses cumulative online minutes for the day.
10. Dispatch rejection handling: `POST /dispatch/orders/{orderId}/reject` enforces 30-minute exclusion and reassignment.
11. Zone capacity enforcement: `POST /dispatch/orders` returns `status=QUEUED` when capacity is reached.
12. Payment split rounding issues: `POST /billing/bills/{billId}/settlements` allocates remainder cents to the first payer.
13. Refund and reversal constraints: `POST /billing/payments/{paymentId}/refunds` supports partial refunds.
14. Discrepancy workflow ownership: `POST /billing/shifts/{shiftId}/handover` creates discrepancy cases assigned to Billing Clerk with Auditor sign-off.
15. Search history retention enforcement: search history is purged by scheduled deletion older than 90 days (see `GET /search/history/retention`).

