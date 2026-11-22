# Application releases and tags

Hammurabi starts at **0.1.0** (the foundation; `APP_VERSION` in `web/src/changelog/version.ts` and `info.version` in `documentation.yaml`). The Gradle `1.0.0-SNAPSHOT` group/version stays (inherited from the donor scaffold; renaming is out of scope). The per-version scope is `product.md` "Roadmap".

## One version per milestone, a patch for a later fix

The user's decision (2026-11-20, Adder's checkup C-DOC-6, adopted here by checkup C-DOC-3): a version is a **milestone** of the roadmap
(`product.md`): the milestone's release commit bumps `APP_VERSION` (a **minor** bump, or the major for 1.0.0) and adds the one English and
Polish entry for everything the milestone ships. Work inside a milestone does not bump. A user-visible fix that lands
**after** a version's release commit is either folded in before that version is tagged (the entry is edited in the same
change, so the tagged changelog describes the tagged code) or, once the tag exists, ships as a **patch** version
(`1.0.1`) with its own entry. Documentation-, test-, CI- and dependency-only changes keep the version. Once a version
is tagged, its entry is never reopened.

**A bump edits three places in the same commit:** the `APP_VERSION` literal (`web/src/changelog/version.ts`), the new
entry at the top of `web/src/changelog/entries.ts`, and `info.version` in
`server/src/main/resources/openapi/documentation.yaml` (the OpenAPI document describes the build that serves it).
`OpenApiSpecTest` ("spec info version equals the app version") fails when `info.version` and `APP_VERSION` differ.
Regenerating `web/src/api/schema.ts` is not needed for a version-only change.

Bumping is not releasing: a bump never creates a tag or a GitHub release. Tagging and publishing (below) happen only
when the user asks for them.


`web/src/changelog/version.ts` is the source of the application version. A release changes
`APP_VERSION` and adds the matching English and Polish entry to `web/src/changelog/entries.ts` in
the same commit. Gradle and npm package versions are separate: `web/package.json` and `e2e/package.json` are `"private": true` workspaces that are never published, so their `version` is the placeholder `0.0.0` (lockfile roots included) and is not bumped with a release; nothing reads it (`APP_VERSION` is the one product version).

## Publishing a new version

1. Merge the reviewed version/changelog change into `master`. Record the full release commit SHA
   and verify that commit's `APP_VERSION` and newest changelog entry match the intended version.
   **CI must be green on that exact commit before the tag** (the user's decision, 2026-11-20: a
   release-checklist rule, not a GitHub ruleset): `gh run list --workflow ci --commit <sha>` shows
   `success`; a red or still-running run blocks the tag, and a transient red is re-run, never waived.
   Exceptions so far: v0.1.0 (`6c4c0a2`), v0.2.0 (`606c5dc`) and v0.3.0 (`ddc1b87`) were tagged while the
   only `master` run was red or absent (run 44833044813 on `ddc1b87`, issue #10; checkup C-GATE-3); v0.4.0
   onwards are green. The nightly `e2e` workflow also runs green on the release candidate before 1.0.0 (C-GATE-1).
   **From 1.2.0 on, the `e2e` workflow must also be green on that exact commit before the tag:** dispatch it
   (`gh workflow run e2e.yml --ref master`, then `gh run watch <id> --exit-status`); a scheduled run on the same
   SHA counts. Exceptions: v1.2.2, v1.2.3 and v1.3.0 were tagged without one (v1.3.0's ran green afterwards, run 45032144933).
   **The release commit carries only release material** (the three version places, the changelog entry, `product.md`,
   README, the `CLAUDE.md` roadmap line): the feature lands in its own commit first, so the tagged diff reads as a release.
2. Create an annotated `v<APP_VERSION>` tag at that explicit SHA, then push that tag only.
   Never let a moving branch name choose the release commit. Existing published tags are
   immutable by convention: do not move, replace or force-push them.
3. Create the GitHub release using `gh release create` with `--verify-tag`. Supply a reviewed
   notes file containing that version's English body, a separator, and its Polish body from
   the tagged changelog. Do not generate product notes from commit messages. Mark only the
   highest stable version as Latest; use `--latest=false` for older releases.
4. Read back the remote tag's peeled commit, release body, draft/prerelease status and Latest
   selection. A local tag or a draft release alone does not complete publication.
5. When deployment is in scope, build from the recorded commit using the
   [run-stack playbook](../skills/run-stack/SKILL.md), verify the displayed version and SHA,
   and preserve existing data. Publishing a GitHub release does not itself deploy containers.

Documentation and test-only changes may retain the app version. They do not create another
release with the same version or move its tag; the displayed commit identifies the newer build.
The existing CI workflows check source/build behavior but do not publish GitHub releases.
Release/tag verification is part of the publishing agent's completion checklist.
