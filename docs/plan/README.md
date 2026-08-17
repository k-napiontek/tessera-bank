# docs/plan

The planning system for Tessera Bank. It lives in the repository, not in a chat log, so that any
session - human or AI - can open the repo and know what the project is, what is finished, and what
to do next.

## The four documents

| File | Purpose | When to read it |
|---|---|---|
| [`STATUS.md`](STATUS.md) | What is done, in progress and next. Follow-ups. Decision log. | **First, every session** |
| [`master-plan.md`](master-plan.md) | What the project is and why it is shaped this way | When you need context |
| [`PROTOCOL.md`](PROTOCOL.md) | How work is executed here. Binding. | Before starting any package |
| [`wp/`](wp/) | One file per work package, self-contained and executable | When executing that package |

## How to start work

```
/work-package WP-03
```

Or, without the skill: read `STATUS.md`, take the lowest-numbered package that is `Not started` and
whose dependencies are all `Done`, then follow [`PROTOCOL.md`](PROTOCOL.md) exactly.

## Work-package file format

Every package file carries the same nine sections, so a session with no prior context can execute it
without asking questions:

| Section | Contains |
|---|---|
| Header | Ticket, branch, stratum, dependencies, status |
| Objective | One paragraph: what this package delivers and why |
| In scope | Explicit list of what gets built |
| Out of scope | Explicit list of what does not - the anti-scope-creep clause |
| Constraints | Pinned versions, patterns to follow, what must not be touched |
| Tasks | Ordered, each roughly one commit |
| Definition of Done | Checkable list specific to this package |
| Verification | Exact commands and expected results |
| Traceability | Requirement IDs this package satisfies |

**Out of scope carries real weight.** It is what stops a package quietly growing into an unreviewable
pull request, and it is the section to reread when tempted to fix something on the way past.

## Keeping this current

`STATUS.md` is updated twice per package - once when it starts, once when it merges. A stale
`STATUS.md` breaks the only mechanism that lets a fresh session resume work correctly, so treat it as
part of the change rather than as paperwork afterwards.
