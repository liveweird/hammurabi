# ADR Markdown export (one ADR, and a ZIP of the filtered list)

- **Spec**: [tests/adr-export.spec.ts](../tests/adr-export.spec.ts)
- **Actors**: the seed administrator, only through the API to create and delete the cast; one throwaway regular user who raises the ADRs
  through the API and does the exports in the browser
- **Owns** (exclusive server-side state): its throwaway user (a unique `E2E Exporter-*` name) and the ADRs (unique `e2e-adr-*` names) it
  raises — all deleted through the API after each test, the ADRs first

## Scenario: the ADR page downloads the ADR as a Markdown file with an ASCII name, its content and its Driver

1. The user raises an ADR through the API whose name holds Polish letters ("e2e-adr-Żółw-…") with a decision and consequences.
2. They open its page and click **Export Markdown**.
   - *Expected*: a file is downloaded named `ADR-NNNN-e2e-adr-zolw-….md` (the key, then the name folded to lower-case ASCII).
3. They read the downloaded file.
   - *Expected*: it opens with `# <number>. <name>`, its **Status** says "Problem raised", its **Decision** and **Consequences** hold the
     written text, **Roles (DACI)** names the user as Driver, and the footer comment carries the export date only.

## Scenario: the list downloads a ZIP of exactly the filtered ADRs, with an index in number order

1. The user raises three ADRs through the API that share a unique marker in their names, and cancels the third.
2. They open the list filtered by the marker and the **Problem raised** status (`?q=…&status=PROBLEM_RAISED`).
   - *Expected*: the first two are listed, the cancelled one is not.
3. They click **Export (ZIP)**.
   - *Expected*: a file `adr-export-YYYY-MM-DD.zip` is downloaded.
4. They open the archive.
   - *Expected*: it holds exactly the two matching ADRs' files (`ADR-NNNN-<marker>-first.md`, `…-second.md`) and `index.md`; each file holds its
     decision; the index links both in number order with their status and does not mention the cancelled ADR.

## Not covered here (and why)

- **The 500-ADR cap, the filter validation, the rate bucket, the audit and a missing or deleted ADR** — pinned by `AdrExportTest` (server);
  the document layout and the slug rules by `AdrMarkdownTest`.
- **The failure message** — pinned by `AdrExportButton.test.tsx`, `Adrs.test.tsx` and `AdrDetails.test.tsx` (web).
