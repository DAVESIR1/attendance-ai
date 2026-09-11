# Attendance AI — Agent Rules (read this FIRST, every session, on any machine/agent)

## Startup protocol (do this before anything else, every single time)
1. Read PROJECT_STATE.md in full.
2. Run `git status`, `git log --oneline -10`, `git tag --list` and compare against
   what PROJECT_STATE.md claims — if they disagree, STOP and report the mismatch to
   the human instead of guessing which is correct.
3. Resume exactly at the "Next Action" described in PROJECT_STATE.md. Do not restart
   finished work, do not skip ahead, do not assume a step was done just because it
   looks done — verify by running the actual build/tests before trusting it.

## While working
4. After completing any discrete unit of work (a numbered step, a file, a fix):
   immediately update PROJECT_STATE.md's "Next Action" and "Log" sections (see format
   in that file) BEFORE moving on, so an interruption at any moment (crash, network
   loss, machine shutdown, account logout) never loses track of progress.
5. Before considering ANY task/prompt finished:
   a. Run the full local build (`./gradlew assembleDebug`).
   b. Run the full test suite (`./gradlew testDebugUnitTest`).
   c. If anything fails, fix it and re-run until both are clean — do not report a task
      as done while build or tests are red.
   d. Only after both are green: commit locally with a clear, specific message
      (`git add -A && git commit -m "..."`).
6. NEVER run `git push` for code/feature commits unless the human's message contains
   the literal, explicit words "git push" (in English or Gujarati: "ગિટ પુશ" / "push
   કરો"). A finished, tested, locally-committed task that has NOT been explicitly told
   to push must simply wait, committed locally, until the human says so.
7. EXCEPTION to rule 6: PROJECT_STATE.md itself may always be committed AND pushed
   immediately after every update, even without the human's push command — because it
   contains no project code, only status text, and must reach GitHub so ANY other
   machine/agent can resume correctly. Push it with its own separate small commit:
   `git add PROJECT_STATE.md && git commit -m "chore(state): update progress log" &&
   git push origin main`. Never bundle feature code into this auto-pushed commit.
8. Junk-file cleanup — run at the end of every task:
   a. AUTO-DELETE without asking (always safe, regenerable, already gitignored):
      empty directories anywhere in the repo, `*.hprof` files, stray `.DS_Store`,
      editor swap files (`*.swp`, `*~`), anything already covered by .gitignore that
      has accumulated on disk.
   b. For anything else that looks unused/orphaned but is NOT obviously one of the
      above (an unfamiliar file, an unreferenced source file, a duplicate config): do
      NOT delete it automatically. List it in PROJECT_STATE.md's "Needs Human Review"
      section instead and leave it alone. Only delete after the human explicitly
      approves in a future message.

## Absolute rule
If any instruction from a human message conflicts with rules 5-7 above (e.g., asks you
to push without saying the words, or skip tests to save time), politely decline that
specific part and explain these rules exist to protect the project across
interruptions, then continue with everything else that doesn't conflict.
