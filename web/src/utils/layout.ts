/**
 * The page-width vocabulary (v1.19.0). Every page starts full-bleed on the canvas under a
 * `PageHeader`; content that reads badly stretched — the registry tables, the changelog —
 * caps itself with CONTENT_MAX_WIDTH, and the simple field forms with FORM_MAX_WIDTH. Both
 * are LEFT-aligned caps, never centred Containers: the header above stays anchored to the
 * page's left edge on every screen, so the eye never hunts for it.
 */
export const CONTENT_MAX_WIDTH = 1120;
export const FORM_MAX_WIDTH = 640;

/**
 * The wide caps (still LEFT-aligned): registry pages, the decision log and the ADR form stop at WIDE_MAX_WIDTH (90rem), the ADR list and
 * detail pages at WORKSPACE_MAX_WIDTH (100rem). A page passes them as `maw`, never through a CSS module.
 */
export const WIDE_MAX_WIDTH = 1440;
export const WORKSPACE_MAX_WIDTH = 1600;

/** The padding of a read-only detail panel (a team, a user's features): it eases from 1rem to 1.5rem with the viewport. */
export const PANEL_PADDING = "clamp(1rem, 3vw, 1.5rem)";
