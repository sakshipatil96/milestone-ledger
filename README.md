# Milestone Ledger

Milestone Ledger is an API-first collections backend for certified infrastructure-project milestones. It helps an accounts executive answer three questions: what is due, what money arrived, and which differences need investigation.

This is a synthetic-data portfolio prototype inspired by a contractor workflow. It is not a production billing platform and does not represent Eagle Infra's internal systems.

## Current status

Planning is complete; implementation starts with the local project foundation.

## Planned stack

- Java 21, Spring Boot, Spring JDBC, Flyway, Maven
- PostgreSQL 17
- Docker Compose, Testcontainers, GitHub Actions
- Simulated bank webhook and reconciliation feed
- API demo collection; no frontend in the MVP

## Product documents

- [Product requirements](PRD.md)
- [System design](SYSTEM_DESIGN.md)
- [Data model](DATA_MODEL.md)
- [API contract](API_CONTRACT.md)

AI guidance and working checklists are intentionally local-only and excluded from Git.

## MVP proof

The completed demo will show that a receipt delivered five times changes a demand balance once, while two genuine same-amount receipts both remain recorded. It will also show reconciliation recovering a receipt whose notification was omitted.

## Scope boundaries

The MVP supports one synthetic project, one synthetic client, INR, approved net collectible amounts, exact-reference matching, explicit exceptions, and reconciliation. It excludes real bank connections, payment initiation, GST/TDS calculations, certification approval workflows, and client data.
