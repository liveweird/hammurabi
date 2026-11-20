import { ActionIcon, Anchor, AppShell, Autocomplete, Badge, Chip, Code, createTheme, Fieldset, Menu, Modal, MultiSelect, NavLink, Select, Table, TagsInput, Tooltip, type MantineColorsTuple } from "@mantine/core";
import classes from "./theme.module.css";
import { foldedOptionsFilter } from "./utils/text";

// "Hammurabi" brand palette = LAPIS LAZULI INDIGO — the deep blue of the glazed bricks of Babylon's
// Ishtar Gate (hue about 228°; light → dark, indices 0–9). Contrast pins (theme.test.ts): white on 7
// (#3a4cb0) is 7.39:1 and white on 8 is 9.29:1, both well past AA for filled-button text, and
// 3 (#93a4e2) clears AA on every dark surface (6.79:1 on the dark canvas). Lapis is a DARK hue, so
// the primary shades sit mid-scale — 7 (light) and 8 (dark), as Covenant's violet and Lettuce do —
// not at the deep end of the scale.
const hammurabi: MantineColorsTuple = [
  "#eef1fb",
  "#dce2f6",
  "#b8c3ec",
  "#93a4e2", // accent ink in the dark scheme (--hammurabi-accent-ink)
  "#7d90de",
  "#5a70d2", // the categorical chart colour (utils/chartColors.ts)
  "#4459c8",
  "#3a4cb0", // primary (light scheme); accent ink in the light scheme
  "#2f3f94", // primary (dark scheme); light-variant ink
  "#263378",
];

// Inter is bundled (via @fontsource-variable/inter, imported in main.tsx) so it loads same-origin
// and satisfies the CSP `font-src 'self'`; the system stack is the fallback (e.g. in tests).
const sans =
  "'Inter Variable', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";

// Design language (the Lettuce/Toadie "clean enterprise SaaS" posture): the brand lapis is
// the interactive accent — primary CTAs, the active nav item, focus — and NOTHING else: links
// are dark text with a hover underline, chips and badges are light tints, icon controls are
// neutral gray. One colour at one intensity for one job, so the few lapis elements on a
// screen are the ones that matter. Semantic colours never impersonate the brand: red =
// blocking, orange = waived/warning, gray = neutral, cyan = in progress, grape = waiting,
// teal = success. Lapis sits in the blue-indigo-violet band, so those stock hues stay out of
// the status vocabulary (in progress is cyan, never stock blue or indigo; a blue badge would
// read as the brand). The canvas is a quiet near-white with a faint lapis cast (dark: dark-8)
// that lets white surfaces lift on a soft, diffuse shadow scale. A filled 8-shade carries white
// text in the dark scheme, but is too dark to be SEEN against dark-8 — every place the hue
// itself must read on the dark canvas (nav marker, hover underline, focus) goes through
// `--hammurabi-accent-ink` (themeVariables.ts), which flips to the 3-shade there.
export const theme = createTheme({
  primaryColor: "hammurabi",
  // Shade 7 in light mode, 8 in dark — lapis is a dark hue, so white CTA text clears 4.5:1 from the
  // middle of the scale (see the pins above).
  primaryShade: { light: 7, dark: 8 },
  // Pick readable text color on filled brand surfaces automatically.
  autoContrast: true,
  // Mantine transitions and animations (Modal, Menu, Collapse, Loader…) stand still under the OS
  // "reduce motion" preference — off by default in Mantine, so it is opted into here (WCAG 2.3.3).
  respectReducedMotion: true,
  defaultRadius: "md",
  colors: { hammurabi },
  fontFamily: sans,
  headings: {
    fontFamily: sans,
    fontWeight: "600",
    // Tighter than Mantine's defaults — pages title themselves with order={2}, and the
    // stock 1.625rem reads oversized next to 14-px body text.
    sizes: {
      h1: { fontSize: "1.625rem", lineHeight: "1.3" },
      h2: { fontSize: "1.3rem", lineHeight: "1.35" },
      h3: { fontSize: "1.125rem", lineHeight: "1.4" },
    },
  },
  // Soft, diffuse elevation (the modern-SaaS ambient look) — every `shadow="sm"` Paper picks
  // this up with zero call-site changes.
  shadows: {
    xs: "0 1px 2px rgba(16, 24, 40, 0.04)",
    sm: "0 1px 2px rgba(16, 24, 40, 0.05), 0 1px 3px rgba(16, 24, 40, 0.07)",
    md: "0 4px 8px -2px rgba(16, 24, 40, 0.08), 0 2px 4px -2px rgba(16, 24, 40, 0.06)",
    lg: "0 12px 16px -4px rgba(16, 24, 40, 0.1), 0 4px 6px -2px rgba(16, 24, 40, 0.05)",
    xl: "0 20px 24px -4px rgba(16, 24, 40, 0.1), 0 8px 8px -4px rgba(16, 24, 40, 0.04)",
  },
  components: {
    // Every data table in the app: a card-like frame on the quiet canvas, hoverable rows,
    // and a neutral, compact header row (see theme.module.css). New tables inherit all of it.
    // verticalSpacing xs (10px) + 14px/1.55 text = ~40px rows: the density of a data grid,
    // not a marketing table. Pages never pass a spacing of their own.
    Table: Table.extend({
      defaultProps: { highlightOnHover: true, verticalSpacing: "xs", horizontalSpacing: "md", fz: "sm" },
      classNames: { table: classes.table, thead: classes.tableHead },
    }),
    // The shell surfaces: white header/navbar over a tinted main canvas, crisp separators.
    AppShell: AppShell.extend({
      classNames: {
        main: classes.appMain,
        header: classes.appHeader,
        navbar: classes.appNavbar,
      },
    }),
    NavLink: NavLink.extend({ classNames: { root: classes.navLink, section: classes.navLinkSection } }),
    // Links are text-coloured and underline on hover — the accent is reserved for actions.
    Anchor: Anchor.extend({ defaultProps: { underline: "hover" }, classNames: { root: classes.anchor } }),
    // Filter chips (types, lifecycles, finding severities) are toggles, not calls to action: a
    // light tint when checked, never the filled brand colour.
    Chip: Chip.extend({ defaultProps: { variant: "light" } }),
    // Icon-only controls (row kebabs, edit/delete, tree toggles) are neutral by default;
    // a destructive one passes color="red" explicitly.
    ActionIcon: ActionIcon.extend({ defaultProps: { variant: "subtle", color: "gray" } }),
    Menu: Menu.extend({ defaultProps: { position: "bottom-end", withinPortal: true, shadow: "md" } }),
    // Every searchable Select/MultiSelect/TagsInput matches accent-insensitively ("zolw" finds
    // "Żółw"), mirroring the server-side unaccent list filters. A per-site `filter` prop still
    // wins — don't pass one unless it preserves the diacritics folding (see utils/text.ts).
    Select: Select.extend({ defaultProps: { filter: foldedOptionsFilter } }),
    Autocomplete: Autocomplete.extend({ defaultProps: { filter: foldedOptionsFilter } }),
    MultiSelect: MultiSelect.extend({ defaultProps: { filter: foldedOptionsFilter } }),
    TagsInput: TagsInput.extend({ defaultProps: { filter: foldedOptionsFilter } }),
    Badge: Badge.extend({ defaultProps: { radius: "sm", variant: "light" } }),
    // Form sections (contract and registry forms): flat, headed by a small caps
    // legend over a hairline — the fieldset/legend semantics stay, the boxes go.
    Fieldset: Fieldset.extend({
      defaultProps: { variant: "unstyled" },
      classNames: { root: classes.formSection, legend: classes.formSectionLegend },
    }),
    Tooltip: Tooltip.extend({ defaultProps: { radius: "md" } }),
    Modal: Modal.extend({ defaultProps: { radius: "md" } }),
    // Mantine's Code root scrolls (`overflow: auto`), so a long finding code or schema value in a
    // narrow flex item becomes a scroll region with no keyboard access (axe
    // scrollable-region-focusable, found by the e2e sweep). Code here is read, never scrolled: wrap it.
    Code: Code.extend({ styles: { root: { overflow: "visible", whiteSpace: "pre-wrap", overflowWrap: "anywhere" } } }),
  },
});
