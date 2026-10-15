# Shared AI Agent Operating Rules

These rules apply to AI-assisted development in this repository. Preserve existing project-specific instructions, source files, data compatibility, and user decisions. If rules conflict, ask the user before taking an irreversible action.

## Transparent, interruptible work
- Break long tasks into analysis, implementation, tests, verification, and GitHub integration. Report concrete findings and completed checkpoints during work, not only at the end.
- Give the user meaningful opportunities to redirect work between short tool/action batches. Do not claim to expose private chain-of-thought; instead communicate evidence, assumptions, decisions, blockers, and next steps.
- Proceed autonomously on routine work. Ask before consequential design changes, unexpected scope changes, destructive operations, or compatibility risks.

## Resource blocker: suspend and ask
- When a required resource has a known acquisition path but cannot realistically be obtained because of permissions, tool limits, size, availability, or similar constraints, do NOT spend extended time on speculative workarounds or silently substitute an inferior resource.
- Preserve modified files, Git status, test outputs, failure details, remaining tasks, and the exact resumption point. Where practical, create a checkpoint commit on a separate work branch without overwriting unrelated changes.
- Suspend only the dependent task; independent work may continue.
- Explicitly ask the user: **"도와줘요!"** Provide the exact filename/path/link, how to obtain it, why it is needed, and what will resume after it is provided.
- Resume from the checkpoint when the resource becomes available; avoid repeating completed work unnecessarily.

## Verification integrity
- Distinguish static review, mocked/simulated tests, isolated execution, real host testing, device testing, and external service testing. Never report a level of verification that was not actually performed.
- Distinguish local edits, commits, pushes, pull requests, merges, and CI results. Include actual commit IDs/links where available.
- Preserve backward compatibility and user data unless the user explicitly approves a breaking change. Never treat a missing dependency as evidence that a test passed.

These shared rules supplement, rather than replace, repository-specific instructions.
