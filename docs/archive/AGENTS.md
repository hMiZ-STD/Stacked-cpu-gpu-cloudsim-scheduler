# AGENTS.md: Rules for the AI agent (read fully at the start of every session)

## 0. Session start (mandatory)
1. Read this file, then PROJECT_MEMORY.md, fully.
2. Tell me in 3-5 lines: current phase, last completed step, next step, open questions.
3. Do not start work until I confirm.

## 1. Never guess
- If a fact, API, library behavior, paper claim or number is not verified, say "I don't know / not verified" and verify it, or ask me.
- Never invent citations, benchmark numbers, function signatures or file contents. If unsure a method exists in CloudSim Plus, check its source or official docs first.
- Label every non-trivial claim as VERIFIED (with source) or ASSUMPTION. Record assumptions in PROJECT_MEMORY.md.
- If requirements are ambiguous, ask. Do not pick silently.

## 2. Research first, from trusted sources only
- Before designing or implementing anything non-trivial, search the web and read primary sources.
- Trusted: official docs and source repos (CloudSim Plus, Java, Maven, Python, pandas), peer-reviewed papers and proceedings (IEEE, ACM, USENIX, Springer), dataset owners' own pages. Not trusted without cross-checking: blogs, forums, AI-generated summaries.
- For every researched decision, record in PROJECT_MEMORY.md: what was decided, the source URL, and the date.

## 3. Best coding practices
- Small, single-purpose functions and classes; clear names; no dead code; no magic numbers (use config).
- Separate concerns: model / scheduler / metrics / IO. Schedulers sit behind one interface.
- Config-driven experiments (YAML), fixed random seeds, deterministic runs. Every result must be reproducible with one command.
- Type hints and docstrings in Python; Javadoc on public Java APIs.
- Linting and formatting (e.g. Checkstyle/Spotless for Java, ruff/black for Python).
- Git: small commits with meaningful messages, feature branches, never commit secrets, large data or build artifacts. Keep .gitignore correct.
- Don't add a dependency without telling me why and what the alternative was.

## 4. Edge cases: always check
For every component list the edge cases BEFORE coding, write tests for them, then implement. At minimum consider:
- Empty/zero input (no jobs, no hosts, zero GPUs), single-item input, very large input
- Job requests exceeding any host's total capacity (must be rejected or queued explicitly, never silently dropped)
- Simultaneous arrivals and ties (deterministic tie-breaking)
- Resource conservation: no negative or over-capacity allocation; everything released on job completion or failure
- Fractional GPU rounding, floating-point error, integer overflow
- Invalid/missing config values and malformed trace rows
- Cluster saturation, starvation of large jobs, and deadlock in gang scheduling
- Reproducibility across runs with the same seed

## 5. Work like a careful human engineer, step by step
- Follow this loop for every task: (1) understand and restate, (2) research, (3) plan and list edge cases, (4) write tests/spec, (5) implement a small piece, (6) run it and show actual output, (7) verify against the spec, (8) update PROJECT_MEMORY.md, (9) report and wait.
- One logical change at a time. Don't batch large unreviewed changes.
- Never claim something works without running it. Paste the real command and output. If it failed, say so.
- If a test fails, find the root cause. Do not weaken the test or hardcode around it.
- Explain each design decision in plain language so I can defend it in an individual viva.

## 6. Scope and honesty
- Environment: laptop, i7 13th gen, 16 GB RAM, NO GPU. Everything is simulation-based, local, and must fit in memory.
- Don't expand scope without asking. Feature freeze at the date in PROJECT_MEMORY.md.
- Report negative or inconclusive results honestly. Never tune results to look better.
- If you're blocked or unsure, stop and ask rather than continue.