---
type: procedure
title: Warranty Claim Procedure
description: Operational steps an agent follows to authorise and process a confirmed defect return.
tags: [rma, authorisation, inspection, disposition]
status: stable
generated: { by: "human:smishra", at: "2026-11-01T09:00:00Z" }
verified:
  - { by: "human:smishra", at: "2026-11-01T09:05:00Z" }
---

# Warranty Claim Procedure

> Note: this file is deliberately written in operational vocabulary (RMA,
> disposition, triage) rather than customer vocabulary. Vector search on a
> customer-phrased question will not surface it. It is reachable only by
> following the authored link from the shipping or eligibility policy —
> which is exactly the behaviour this POC demonstrates.

1. **Raise the authorisation.** The agent creates an RMA record and the system
   allocates a sequential RMA number. The RMA must be raised before any label
   is generated.

2. **Set the triage class.** Select class A (functional fault), class B
   (cosmetic fault) or class C (fulfilment error). The triage class drives the
   downstream disposition.

3. **Observe the submission window.** The RMA must be raised no later than 30
   days after the delivery date. Records raised outside this window are
   rejected automatically and require supervisor override.

4. **Inspection on receipt.** The fulfilment centre inspects within 5 business
   days of receipt and confirms or reverses the triage class.

5. **Disposition.** Class A goes to the vendor recovery queue, class B to
   refurbishment, class C back to sellable stock.

The customer-facing settlement — what is refunded and who bears carriage — is
governed by policy, not by this procedure.

## Related Concepts

- [Refund Eligibility](/policies/refund-eligibility.md) — determines whether an RMA may be raised at all
- [Return Shipping Costs](/policies/return-shipping.md) — governs carriage for the authorised return
- [Order Management System](/systems/order-management.md) — where RMA records live
