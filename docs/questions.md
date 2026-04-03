# Business Logic Questions Log

### 1. Offline-first conflict resolution

**Question:** Multiple users (e.g., Billing Clerk, Dispatcher) may perform actions offline—how are conflicts resolved when syncing later?
**My Understanding:** Last-write-wins is risky; domain-specific conflict resolution is needed (e.g., financial data should not be overwritten blindly).
**Solution:** Implement versioning + conflict detection (optimistic locking). For critical entities (Bill, Payment), reject conflicting updates and require manual reconciliation.

---

### 2. Scheduled publishing in offline mode

**Question:** How does scheduled publishing work if the system is offline and system time changes or restarts?
**My Understanding:** Scheduling must rely entirely on local system time.
**Solution:** Use a local scheduler (e.g., Quartz) with persisted jobs and recovery on restart.

---

### 3. Sensitive-word filtering scope

**Question:** Does the sensitive-word dictionary apply globally or per organization/tenant?
**My Understanding:** Likely per organization for flexibility.
**Solution:** Store dictionary scoped by organization_id and allow overrides.

---

### 4. Comment moderation thresholds

**Question:** “2+ hits → hold for review” is defined, but what about 1 hit?
**My Understanding:** 1 hit should still allow posting but flagged.
**Solution:** Add moderation_state = FLAGGED for 1 hit; HOLD for ≥2.

---

### 5. Immutable publish history granularity

**Question:** What exactly is stored in publish history—full snapshots or diffs?
**My Understanding:** Full snapshots are safer for audit.
**Solution:** Store versioned snapshots of ContentItem on each publish/unpublish.

---

### 6. Billing cycle edge cases

**Question:** What happens if billing generation at 12:05 AM fails?
**My Understanding:** It must retry to avoid missing A/R.
**Solution:** Add retry mechanism with idempotency keys for bill generation.

---

### 7. Late fee calculation timing

**Question:** Is late fee applied once or continuously after the grace period?
**My Understanding:** Applied once after 10 days.
**Solution:** Add a scheduled job that applies a single late fee capped at $50.

---

### 8. Discount application conflicts

**Question:** What if multiple discounts apply to a single bill?
**My Understanding:** Only one discount should be applied to avoid complexity.
**Solution:** Enforce single discount per bill or define priority rules.

---

### 9. Driver eligibility calculation timing

**Question:** “15 minutes online today” — is this cumulative or continuous?
**My Understanding:** Cumulative time for the day.
**Solution:** Track driver session logs and aggregate duration per day.

---

### 10. Dispatch rejection handling

**Question:** What happens after a driver rejects a forced dispatch?
**My Understanding:** It should be reassigned to another eligible driver.
**Solution:** Implement reassignment queue excluding the rejecting driver for 30 minutes.

---

### 11. Zone capacity enforcement

**Question:** What happens when a zone exceeds its capacity?
**My Understanding:** New orders should be blocked or queued.
**Solution:** Add queueing system when capacity is reached.

---

### 12. Payment split rounding issues

**Question:** How is rounding handled when splitting payments evenly?
**My Understanding:** Minor discrepancies must be distributed.
**Solution:** Allocate remainder cents to the first payer.

---

### 13. Refund and reversal constraints

**Question:** Can partial refunds be issued or only full reversals?
**My Understanding:** Both should be supported.
**Solution:** Allow partial reversal linked to original payment with validation.

---

### 14. Discrepancy workflow ownership

**Question:** Who resolves discrepancies > $1.00?
**My Understanding:** Likely Auditor or Billing Clerk.
**Solution:** Assign discrepancy cases to Billing Clerk with Auditor oversight.

---

### 15. Search history retention enforcement

**Question:** How is the 90-day search history retention enforced?
**My Understanding:** Requires cleanup job.
**Solution:** Implement scheduled job to delete records older than 90 days.

---
