---
type: policy
title: Return Shipping Costs
description: Determines whether the customer or Northwind pays to ship a returned item back.
tags: [returns, shipping, costs, refunds]
status: stable
generated: { by: "human:smishra", at: "2026-11-01T09:00:00Z" }
verified:
  - { by: "human:smishra", at: "2026-11-01T09:05:00Z" }
sources:
  - id: returns-handbook-2026
    resource: /uploads/returns-handbook-2026.pdf
    title: Northwind Returns Handbook 2026
    last_modified: "2026-10-02T00:00:00Z"
---

# Return Shipping Costs

When a customer returns an item, the customer pays the return shipping cost
and the amount is deducted from the refund.

This does **not** apply when the return qualifies as a *defect return*. For a
defect return, Northwind issues a prepaid return label at no cost to the
customer, and the full purchase price including original outbound shipping is
refunded.

The criteria that make a return a defect return are defined separately — this
policy does not restate them.

## Related Concepts

- [Refund Eligibility](/policies/refund-eligibility.md) — defines what counts as a defect return
- [Warranty Claim Procedure](/procedures/warranty-claim.md) — the steps to follow once a defect return is confirmed
- [Order Management System](/systems/order-management.md) — where the label and refund are recorded
