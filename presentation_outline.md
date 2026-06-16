# Final Presentation Outline — "Vibe Coding" Process Review
**Format:** 7 min talk + 3 min Q&A → export to PDF for Moodle
**Focus:** Demo + AI-assisted "activities" (design decisions, code smells/refactoring, debugging, architecture)

---

## Slide 1 — Title
- Project name (raycasting maze game), course name, team members, date
- One-line description: "Java Swing raycasting maze game built with AI-assisted ('Vibe Coding') development"

## Slide 2 — Project Overview / Demo
- 2–3 screenshots of the running game: 3D raycast view (with stars/moon/grass), minimap/HUD, pause menu
- Quick feature bullet list: multiplayer (host/join), multiple levels, stamina/sprint system, monster AI with chase/wander, textured walls/doors
- Keep this short (~30 sec) — the rubric says demo is *not* the main point

## Slide 3 — Development Workflow
- How the team worked: Vibe Coding sessions (6/3, 6/10), AI pair-programming via chat, Git for history/merging
- Tooling: VSCode + Claude/Copilot-style AI assistant
- Briefly mention the AI was used for: planning, design decisions, debugging, refactoring, and architecture diagrams (sets up the rest of the deck)

## Slide 4 — Architecture / UML Class Diagram
- Show `ClassDiagram.mermaid` (rendered) — GamePanel / Renderer / Monster / NetworkClient / etc.
- 1–2 sentences on the overall structure: model–view-ish split (GamePanel = state, Renderer = drawing), networking layer for multiplayer
- Optional: mention `ClassDiagram_Simple.mermaid` as the "high-level" version for quick orientation

## Slide 5 — Design Decision: Monster AI State Machine
- Briefly describe the AI design before diving into the bug: Monster has two states — **wandering** and **pursuing**
- Show the state transition rules in plain language:
  - Idle → Pursuing: requires `visible` (line of sight) AND `ahead` AND within range
  - Pursuing → Idle: *(this is where the bug was — next slide)*
- Sets up the "before/after" story

## Slide 6 — Code Smell: Asymmetric State Guard (Before)
**"The pursuing state's exit condition didn't match its entry condition"**
- Show the **before** code for the `pursuitActive` branch:
```java
if (pursuitActive) {
    if (distance <= MAX_CHASE_DISTANCE) {
        chasing = true;
        chasePlayer(...);
    } else {
        pursuitActive = false;
        chasing = false;
        walk(...);
    }
}
```
- Call out: entry required `visible`, but persistence only checked `distance` → monster could "see through walls" once aggro'd
- This breaks the front/back sprite mechanic (designed to signal "safe to walk behind")

## Slide 7 — The Fix (After) + AI Chat Excerpt
- Show **after** code (same branch, with `visible &&` added + `currentPath.clear()` + `wander()`):
```java
if (pursuitActive) {
    if (visible && distance <= MAX_CHASE_DISTANCE) {
        chasing = true;
        chasePlayer(...);
    } else {
        pursuitActive = false;
        chasing = false;
        currentPath.clear();
        wander(...);
    }
}
```
- Include a short excerpt/screenshot of the actual prompt that drove this:
  > "for when the monster see u and is agro make it so it can only be agro when it sees you and if not then it ignores you now"
- 1-line "lesson": entry/exit guards for the same state must enforce the same invariant

## Slide 8 — Debugging Story: Broken Merge After `git pull`
- Context: pulled a teammate's commit adding A* pathfinding (`Pathfinder.java`) + Monster.java rewrite
- Show the **error/symptom** — screenshot or excerpt of the compiler error:
```
The method chasePlayer(double, double, int[][], int) is undefined for the type Monster
The method walk(int[][], int) is undefined for the type Monster
ahead cannot be resolved to a variable
```
- Briefly explain root cause: one method's body got spliced into the middle of another method during the merge (dead code / incomplete refactor — "shotgun surgery" residue)

## Slide 9 — The Fix + AI Chat Excerpt
- Show the corrected structure (high level, not full code):
  - `chasePlayer()` now delegates to `followAStarPath()`
  - Restored missing `isPlayerAhead()`
  - Replaced dead `walk()` calls with `wander()`
- Include a short excerpt of the AI conversation: pasted the terminal error → AI diagnosed the splice + missing method, then fixed it
- 1-line "lesson": fast-forward merges can silently bring in non-compiling code — always rebuild/retest after `git pull`

## Slide 10 — Design Pattern Reflection
- Name the pattern: **State pattern (informal FSM)** — `pursuitActive`/`chasing` flags drive behavior branching
- Other patterns spotted in the codebase (quick mention): Strategy (wander vs. A* chase), Model/View split (GamePanel vs Renderer), Factory methods (`Textures.createBrick()`, etc.)
- Tie back to the bug: state machines need consistent entry/exit invariants — a good general takeaway for the class

## Slide 11 — Wrap-up / Takeaways
- 3 bullets: (1) AI helped catch a subtle state-machine logic flaw, (2) AI helped diagnose a non-obvious merge-induced compile error from raw stack traces, (3) Git + AI chat history together made it easy to reconstruct "why" a change was made
- Thank you / Q&A

---

## Notes on assets needed before building the deck
- Screenshots: gameplay (3D view with stars/moon/grass + monster), pause menu, minimap
- Render `ClassDiagram.mermaid` to an image (e.g. via mermaid CLI or an online renderer) for Slide 4
- Pull exact before/after code blocks from `Monster.java` git history (`git show <old-commit>:Monster.java` for the "before" version of `update()`)
- Screenshot or text excerpt of the actual git pull error message and the two relevant AI chat prompts (Slides 7 & 9)

Let me know if you want topics swapped, slides added/removed, or if you'd like me to proceed to build the .pptx from this outline.
