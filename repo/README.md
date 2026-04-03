# CivicWorks Community Services Backend

Offline-first municipal community services platform.

## Quick Start

```bash
docker compose up --build
```

The application is published on the host at **http://localhost:18080** by default (container still listens on 8080 inside the network). Another process on your machine may already use 8080; this mapping avoids that conflict. To use host port 8080 instead, run: `HOST_APP_PORT=8080 docker compose up` (PowerShell: `$env:HOST_APP_PORT=8080; docker compose up`).

The PostgreSQL database runs on host port **5432**.

## Default Credentials

All users have password `admin123`.

| Username   | Role           |
|------------|----------------|
| admin      | SYSTEM_ADMIN   |
| editor     | CONTENT_EDITOR |
| clerk      | BILLING_CLERK  |
| driver1    | DRIVER         |
| moderator  | MODERATOR      |
| dispatcher | DISPATCHER     |
| auditor    | AUDITOR        |

## API Base URL

`http://localhost:18080/api/v1` (or your `HOST_APP_PORT` if set)

## Authentication

HTTP Basic Auth on every request.

## Key Endpoints

### Content Management
- POST /api/v1/content/items - Create content item (CONTENT_EDITOR, SYSTEM_ADMIN)
- GET /api/v1/content/items - List content items (authenticated)
- PATCH /api/v1/content/items/{id} - Update content item (CONTENT_EDITOR, SYSTEM_ADMIN)
- POST /api/v1/content/items/{id}/publish - Publish item (CONTENT_EDITOR, SYSTEM_ADMIN)
- POST /api/v1/content/items/{id}/unpublish - Unpublish item (CONTENT_EDITOR, SYSTEM_ADMIN, MODERATOR)
- GET /api/v1/content/items/{id}/publish-history - Publish history (authenticated)

### Billing
- POST /api/v1/billing/fee-items - Create fee item (BILLING_CLERK, SYSTEM_ADMIN)
- POST /api/v1/billing/billing-runs - Create billing run (BILLING_CLERK, SYSTEM_ADMIN)
- POST /api/v1/billing/bills/{billId}/late-fee/apply - Apply late fee (BILLING_CLERK)
- POST /api/v1/billing/bills/{billId}/discount - Apply discount (BILLING_CLERK)
- POST /api/v1/billing/bills/{billId}/settlements - Create settlement (BILLING_CLERK)
- POST /api/v1/billing/payments/{paymentId}/refunds - Issue refund (BILLING_CLERK)
- POST /api/v1/billing/shifts/{shiftId}/handover - Submit shift handover (BILLING_CLERK)
- GET /api/v1/billing/discrepancies - List discrepancy cases (BILLING_CLERK, AUDITOR, SYSTEM_ADMIN)
- POST /api/v1/billing/discrepancies/{caseId}/resolve - Resolve discrepancy (BILLING_CLERK, SYSTEM_ADMIN)

### Dispatch
- POST /api/v1/dispatch/orders - Create order (DISPATCHER, DRIVER)
- POST /api/v1/dispatch/orders/{orderId}/accept - Accept order (DRIVER)
- POST /api/v1/dispatch/orders/{orderId}/reject - Reject order (DRIVER)
- GET /api/v1/drivers/{driverId}/eligibility - Driver eligibility (DISPATCHER, DRIVER)

#### Create order — required fields

`pickupLat` and `pickupLng` are **required** at order creation.  Orders without a
pickup location cannot be accepted (the 3-mile distance check requires a trusted
server-side coordinate source).

```json
POST /api/v1/dispatch/orders
{
  "zoneId":     "<uuid>",
  "mode":       "GRAB",
  "pickupLat":  37.77,
  "pickupLng":  -122.41,
  "dropoffLat": 37.78,
  "dropoffLng": -122.42
}
```

#### Accept order — required request body

`driverLat` and `driverLng` are **required** in the request body.  The server
performs a Haversine distance check and rejects the acceptance (HTTP 400) if
either the driver coordinates or the order pickup coordinates are missing, or if
the driver is more than 3 miles from the pickup location.

```
POST /api/v1/dispatch/orders/{orderId}/accept
Headers:
  X-Entity-Version: <version>          (required — optimistic locking)
  Idempotency-Key:  <uuid>             (required)
Body:
{
  "driverLat": 37.77,
  "driverLng": -122.41
}
```

### Search
- GET /api/v1/search/typeahead?q={query} - Full-text search (authenticated)
- GET /api/v1/search/typeahead/filter?q={query}&category=&origin=&minPrice=&maxPrice=&sortBy=&sortDir= - Filtered search with category, origin, price range, and sort (authenticated)

### Audit
- GET /api/v1/audit/logs - Audit logs (AUDITOR, SYSTEM_ADMIN)

## Backups

A `backup` service runs alongside the application in `docker-compose.yml`.
Every 24 hours it calls `pg_dump` against the `db` service and writes a
gzip-compressed snapshot to the `civicworks_backups` named Docker volume:

```
/backups/civicworks_YYYYMMDD_HHMMSS.sql.gz
```

**Retention policy:** backup files older than 7 days are automatically deleted
by the same container process (`find … -mtime +7 -delete`), so the volume
never accumulates more than ~8 snapshots under normal operation.

To inspect or restore a backup, attach to the volume or copy a file out:

```bash
# List available snapshots
docker run --rm -v civicworks_backups:/backups alpine ls /backups

# Restore a specific snapshot (replace <file> with the desired filename)
docker run --rm -v civicworks_backups:/backups postgres:16-alpine \
  sh -c "gunzip -c /backups/<file> | psql -h db -U civicworks civicworks"
```

## Final Fixes (Acceptance Blockers)

The following changes were made to resolve final acceptance blockers:

| Blocker | File(s) changed | Why |
|---------|----------------|-----|
| Flyway duplicate DDL | `V1__initial_schema.sql`, `V3__extensions.sql` | V1 and V3 both defined `notification_outbox`, causing Flyway to fail on every fresh DB. V1's definition removed; V3 is the single source of truth with `IF NOT EXISTS` guard. |
| Dispatch bypass via missing pickup coords | `CreateDispatchOrderRequest.java`, `DispatchService.java` | `pickupLat`/`pickupLng` are now `@NotNull` at order creation. `acceptOrder` rejects with 400 if the order has no pickup location, closing the last bypass path. |
| API test contract drift | `API_tests/tests/test_dispatch.py` | Accept-order tests updated to include required `driverLat`/`driverLng` body; new negative test asserts 400 for missing driver coords; new test asserts 422 for missing pickup coords at order creation. |
| SYSTEM_ADMIN global billing scope | `BillingController.java` | Replaced `actor.getOrganization() != null ? orgId : null` with an explicit role check: `SYSTEM_ADMIN` always passes `null` orgId (global), regardless of whether the account has an org set. |
| Template-driven reminder notifications | `NotificationTemplateService.java` (new), `ReminderNotificationJob.java` | Hardcoded subject/body strings in the scheduler replaced with a `{{placeholder}}`-based template engine. `OVERDUE_BILL_REMINDER` is a built-in template; new templates can be registered at runtime. |

## Security and Integrity Fixes

| Fix | File(s) changed | Why |
|-----|----------------|-----|
| Billing tenant isolation | `BillingService.java`, `PaymentService.java` | `applyLateFee`, `applyDiscount`, `createSettlement`, `createRefund` now use `findByIdAndOrganizationId` when the actor is non-admin. Cross-org mutations return 404. SYSTEM_ADMIN bypasses. |
| Content org-ownership | `ContentService.java`, `ContentController.java` | All content mutations (`updateItem`, `scheduleItem`, `publishItem`, `unpublishItem`) verify the actor's org matches the item's org. SYSTEM_ADMIN bypasses. `updateItem` now requires actor parameter. |
| Forced-dispatch integrity | `DispatchService.java`, `DispatchOrder.java`, `V4__dispatch_assigned_at.sql` | Only the designated driver may accept a `DISPATCHER_ASSIGNED` order (others get 403). New `assignedAt` column tracks assignment time. |
| 30-minute reassignment | `DispatchService.reassignOrder()`, `DispatchController.java`, `ReassignOrderRequest.java` | New `POST /api/v1/dispatch/orders/{orderId}/reassign` endpoint. Enforces 30-minute wait before a forced-dispatch order can be reassigned to another driver. |
| Event notifications | `BillingService.java`, `ContentService.java` | Late-fee and discount events notify org users. Publish/unpublish events notify content creator. All offline/in-app only. |
| Report-grade handover | `PaymentService.processHandover()`, `ShiftHandoverSummary.java` | Handover endpoint now returns a full `ShiftHandoverSummary` with submitted/posted breakdowns by method, delta, and discrepancy case details. |

### New Dispatch Endpoint: Reassign Order

```
POST /api/v1/dispatch/orders/{orderId}/reassign
Roles: DISPATCHER, SYSTEM_ADMIN
Headers:
  Idempotency-Key: <uuid>
Body:
{
  "newDriverId": "<uuid>",
  "entityVersion": <int>
}
```

Returns 422 if the order is not in ASSIGNED state or if fewer than 30 minutes have elapsed since the original assignment.

## Testing

From the `repo` directory:

- **Git Bash / Linux / macOS:** `./run_tests.sh`
- **Windows PowerShell:** `.\run_tests.ps1`

The script waits for the API on the **host** port (default **18080**, same as `docker compose`). If you set `HOST_APP_PORT` when starting Compose, export the same value before running the shell script (PowerShell reads `HOST_APP_PORT` automatically).

## Architecture

- Spring Boot 3.2 + Java 17
- PostgreSQL 16 - all persistent state, money as integer cents, UUIDs as PKs
- Flyway - database migrations on startup
- Quartz - JDBC job store for scheduled/recurring tasks
- Spring Security - HTTP Basic auth backed by DB users
- Optimistic Locking - @Version on Bill, Payment, DispatchOrder, ContentItem, Comment
