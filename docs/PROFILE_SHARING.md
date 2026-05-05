# Profile Sharing Baseline

OpenTasker v0.2.28 adds an offline profile sharing manifest on top of OpenTasker JSON bundles. It prepares community review data but does not publish, download, import, or verify community submissions.

## Active Scope

- Builds `ProfileShareManifest` from a `ProfileShareDraft` and an `OpenTaskerBundle`.
- Requires stable lowercase slugs for future URLs and file names.
- Summarizes bundle schema, app version, profile count, task count, action count, context count, variable count, scene count, and screenshot count.
- Marks all community shares as `CommunityUnverified`.
- Reports setup-required and unsupported action capabilities as safety findings.
- Reports unsupported schema, import warnings, lossy references, and missing screenshots.
- Generates Markdown for the planned GitHub Discussions submission channel.

## Non-Goals

- No network publishing.
- No remote download or URL import.
- No signed or verified template workflow.
- No screenshot capture or upload.
- No local Room import from the sharing manifest.
- No trust bypass for unsupported actions, setup-required actions, or lossy bundle references.

## Safety Rules

- Unsupported actions are blockers in the share manifest.
- Setup-required actions are warnings.
- Unknown future schema versions are blockers.
- Missing task/scene references are warnings because the bundle importer may skip or drop those links.
- Missing screenshots are warnings so community reviewers know inspection evidence is incomplete.
- A share manifest can describe a bundle, but the bundle validation rules remain the source of import truth.

## Next Work

1. Add a local share preview UI for bundle manifests.
2. Add screenshot attachment and preview support.
3. Add copy/share URL and GitHub Discussions draft helpers.
4. Add local import review that shows the manifest and bundle import plan before writing to Room.
5. Add signed/verified template metadata only after a concrete signing and review workflow exists.
