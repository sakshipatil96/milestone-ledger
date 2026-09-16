# Milestone Ledger — Before Day 1 Checklist

Complete this once before writing application code. This creates a clean, reproducible starting point and a visible first commit.

## Already complete

- [x] GitHub repository created: `sakshipatil96/milestone-ledger`.
- [x] Local Git identity configured as `sakshipatil96 <patilsakshi.siya@gmail.com>`.
- [x] Product name selected: **Milestone Ledger**.
- [x] Product, MVP, architecture, data, API, AI rules, risk review, and build checklists written.
- [x] Main local branch exists: `main`.

## Git and repository hygiene

- [x] Add the GitHub remote as `origin`: `https://github.com/sakshipatil96/milestone-ledger.git`.
- [x] Verify the remote and authenticated GitHub access with `git remote -v` and `git ls-remote origin`.
- [x] Configure GitHub CLI's existing authenticated HTTPS credential with `gh auth setup-git`.
- [x] Create `.gitignore` before adding files. It excludes `.DS_Store`, `.env`, `.env.*` except `.env.example`, `target/`, IDE files, logs, and generated coverage/build output.
- [x] Add a concise `README.md` with the product purpose, stack, local status, and links to the planning documents.
- [x] Confirm no real client/bank data, passwords, API keys, signing secrets, or downloaded data exist in the initial tracked files.
- [ ] Review `git status` so `.DS_Store` is excluded and only intended documentation/configuration is staged.
- [ ] Create the first commit: `docs: add milestone ledger product plan`.
- [ ] Push `main` to `origin` and verify the GitHub repository shows the commit.

## Confirm build decisions

- [x] Use one Java/Spring Boot modular monolith, not microservices.
- [x] Use Java 21, Spring Boot, Spring JDBC, Flyway, PostgreSQL 17, Maven, Docker Compose, and Testcontainers.
- [x] Use API/demo collection only; no frontend in the MVP.
- [x] Use synthetic data only, one project/client, INR, and a simulated bank.
- [x] Use Docker locally first; defer public deployment until the five-day build and CI pass.
- [x] Treat allocation reversal as a buffer-day/7-day feature, after core correctness gates pass.

## Local machine readiness

- [x] Install or verify Java 21: `java -version` reports OpenJDK 21.0.11.
- [ ] Start Docker Desktop, then verify Docker Engine with `docker version`; Docker Compose 5.3.0 is installed.
- [x] Choose one IDE and install its Java/Spring support. VS Code 1.135.0 and the Java Extension Pack are installed.
- [ ] Install no database locally; PostgreSQL will run through Docker Compose.
- [ ] Do not create real credentials. Day 1 will add a local-only `.env.example` and document required variables.

## Day 1 start gate

Start Day 1 only when these are checked:

- [ ] GitHub remote connected and first documentation commit pushed.
- [x] `.gitignore` prevents accidental secret/build-file commits.
- [x] Java 21, Docker Engine 29.6.1, and Docker Compose 5.3.0 are available.
- [x] Core loop and Day 1 exit gate are documented in [BUILD_CHECKLIST.md](BUILD_CHECKLIST.md).

## Explicitly defer

- [x] Do not deploy or create cloud resources yet.
- [x] Do not add a frontend, payment gateway, real bank integration, broker, Redis, or microservices.
- [x] Do not use Eagle, client, or bank data.
