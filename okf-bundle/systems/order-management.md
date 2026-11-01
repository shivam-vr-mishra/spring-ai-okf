---
type: system
title: Order Management System
description: The system of record for orders, RMA records, refund transactions and return labels.
resource: https://oms.internal.northwind.example
tags: [oms, systems, orders]
status: stable
generated: { by: "ollama/llama3.2:3b", at: "2026-11-01T09:10:00Z" }
---

# Order Management System

The Order Management System (OMS) is the system of record for every order and
every return. RMA records, generated return labels and refund transactions are
all persisted here and are the authoritative source for dispute resolution.

Refunds settle to the original payment method within 5 to 7 business days of
the disposition being recorded.

## Related Concepts

- [Warranty Claim Procedure](/procedures/warranty-claim.md) — creates the RMA records stored here
- [Return Rate](/metrics/return-rate.md) — computed from OMS data
