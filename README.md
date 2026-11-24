<p align="center">
  <img src="assets/boodoo-logo.png" alt="Logo BOODOO" width="360">
</p>

# BOODOO — Mobile Money Platform

**BOODOO** is an open-source, microservices-based Mobile Money platform under development, designed to support digital wallet management, peer-to-peer money transfers, merchant payments, and reliable financial transaction processing.

The project aims to build the core capabilities of a Mobile Money ecosystem, with a strong focus on financial integrity, scalability, security, and fault tolerance.

## Vision

Build a modular Core Mobile Money platform capable of supporting financial operations across consumers, merchants, and, in future phases, agents and external financial service providers.

## Planned Core Capabilities

- **Wallet Management:** Create and manage digital wallets and their lifecycle.
- **Money Transfers:** Support secure peer-to-peer transfers between wallets.
- **Financial Ledger:** Maintain auditable double-entry accounting records.
- **Transaction Processing:** Ensure idempotency, concurrency control, and reliable transaction execution.
- **Merchant Payments:** Support payments to merchants.
- **Financial Reconciliation:** Detect and investigate discrepancies between transactions and accounting records.
- **Resilience:** Handle retries, service failures, and uncertain transaction outcomes safely.

## Technology Stack

- Java and Spring Boot
- Microservices architecture
- PostgreSQL
- Apache Kafka 
- Docker and Kubernetes 
- CI/CD and observability

## Project Status

**Active development — onboarding and financial workflow foundations.**

Customer onboarding, transaction PINs and operator KYC review are implemented. The current feature batch adds accounting-backed wallet activation, balanced ledger journals, controlled P2P transfers, pricing/allowance/risk checks, audit/inbox records, internal integrity reports and public API routing. Full-stack release validation, external settlement/payment flows, recovery operations and deployment controls remain release gates.

BOODOO is currently an engineering project and is not intended to process real customer funds. The capabilities listed above represent the planned product scope and will be implemented incrementally.
