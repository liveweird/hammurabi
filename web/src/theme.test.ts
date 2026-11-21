import { DEFAULT_THEME } from "@mantine/core";
import { describe, expect, it } from "vitest";
import indexCss from "./index.css?raw";
import { contrast } from "./test/contrast";
import { theme } from "./theme";
import { cssVariablesResolver, DARK_TOKENS, LIGHT_TOKENS, LIGHT_VARIANT_INKS } from "./themeVariables";

// Mantine's own `darken(color, alpha)`: each channel scaled by (1 - alpha), rounded — the dark
// scheme's light-variant surface is `darken(<hue>-9, 0.5)`.
function darken(hex: string, alpha: number): string {
  const scaled = [1, 3, 5].map((o) => Math.round(Number.parseInt(hex.slice(o, o + 2), 16) * (1 - alpha)));
  return `#${scaled.map((c) => c.toString(16).padStart(2, "0")).join("")}`;
}
const WHITE = "#ffffff";
// Mantine 9 paints a light-variant surface with the hue's SOLID 1-shade in the light scheme
// (`--mantine-color-<hue>-light: var(--mantine-color-<hue>-1)`) — the ink must clear AA on it.
const HAMMURABI_1 = "#dce2f6";
const AA = 4.5;

describe("theme primary shades", () => {
  it("lapis is a dark hue: primary is the 7-shade in light and the 8-shade in dark", () => {
    expect(theme.primaryShade).toEqual({ light: 7, dark: 8 });
  });
});

describe("theme motion", () => {
  it("honors the OS reduced-motion preference (Mantine's default is off)", () => {
    expect(theme.respectReducedMotion).toBe(true);
  });
});

describe("theme colour tokens (WCAG AA)", () => {
  it("light text tokens clear 4.5:1 on white, the canvas, and the surface tint", () => {
    // The surface tint is the FilterPanelBody ground — dimmed labels sit on it.
    const surfaces = [WHITE, LIGHT_TOKENS.canvas, LIGHT_TOKENS.surfaceTint];
    for (const ink of [LIGHT_TOKENS.text, LIGHT_TOKENS.dimmed, LIGHT_TOKENS.error, LIGHT_TOKENS.inkWarning, LIGHT_TOKENS.inkError]) {
      for (const surface of surfaces) {
        expect(contrast(ink, surface), `${ink} on ${surface}`).toBeGreaterThanOrEqual(AA);
      }
    }
  });

  it("every light-variant ink clears 4.5:1 on its hue's light surface (the 1-shade)", () => {
    for (const [hue, ink] of Object.entries(LIGHT_VARIANT_INKS)) {
      const surface = hue === "hammurabi" ? HAMMURABI_1 : DEFAULT_THEME.colors[hue][1];
      expect(surface, `unknown hue ${hue}`).toBeDefined();
      expect(contrast(ink, surface), `${hue} ink ${ink} on ${surface}`).toBeGreaterThanOrEqual(AA);
      // The same ink also sits on white/canvas surfaces (links, filled-on-light text).
      expect(contrast(ink, WHITE), `${hue} ink ${ink} on white`).toBeGreaterThanOrEqual(AA);
    }
  });

  it("the dark scheme keeps Mantine's own light-variant pairs (0-shade over a darkened 9-shade)", () => {
    for (const hue of Object.keys(LIGHT_VARIANT_INKS)) {
      const scale = hue === "hammurabi" ? theme.colors!.hammurabi! : DEFAULT_THEME.colors[hue];
      expect(contrast(scale[0], darken(scale[9], 0.5)), `${hue} dark light-variant`).toBeGreaterThanOrEqual(AA);
    }
  });

  it("dark text tokens clear 4.5:1 on the dark surfaces", () => {
    // dark-7 = body, dark-6 = table heads, the canvas, and the surface tint (FilterPanelBody).
    const surfaces = [DEFAULT_THEME.colors.dark[7], DEFAULT_THEME.colors.dark[6], DARK_TOKENS.canvas, DARK_TOKENS.surfaceTint];
    for (const ink of [DARK_TOKENS.text, DARK_TOKENS.dimmed, DARK_TOKENS.error, DARK_TOKENS.inkWarning, DARK_TOKENS.inkError]) {
      for (const surface of surfaces) {
        expect(contrast(ink, surface), `${ink} on ${surface}`).toBeGreaterThanOrEqual(AA);
      }
    }
  });

  it("the brand accent stays usable as filled button, focus ring and accent ink in both schemes", () => {
    const hammurabi = theme.colors!.hammurabi!;
    // Light scheme: white text on the shade-7 fill, and the shade itself as a focus ring (3:1
    // floor) — lapis is a dark hue, so the white-text fills sit mid-scale.
    expect(contrast(hammurabi[7], WHITE)).toBeGreaterThanOrEqual(AA);
    expect(contrast(hammurabi[7], LIGHT_TOKENS.canvas)).toBeGreaterThanOrEqual(3);
    expect(LIGHT_TOKENS.accentInk).toBe(hammurabi[7]);
    // Dark scheme: white text on the shade-8 fill; the hue itself is too dark to be SEEN on
    // dark-8, so the accent ink flips to shade 3 there — readable as text on every dark surface.
    expect(contrast(hammurabi[8], WHITE)).toBeGreaterThanOrEqual(AA);
    expect(DARK_TOKENS.accentInk).toBe(hammurabi[3]);
    for (const surface of [DEFAULT_THEME.colors.dark[7], DEFAULT_THEME.colors.dark[6], DARK_TOKENS.canvas]) {
      expect(contrast(hammurabi[3], surface), `accent ink on ${surface}`).toBeGreaterThanOrEqual(AA);
    }
  });

  it("index.css paints the first-paint canvas with the same hexes as the canvas tokens", () => {
    // The bundle's CSS variables don't exist before Mantine mounts, so index.css repeats the
    // two canvas hexes literally — keep them in lockstep with themeVariables.ts.
    const match = /background-color:\s*light-dark\((#[0-9a-f]{6}),\s*(#[0-9a-f]{6})\)/i.exec(indexCss);
    expect(match, "index.css first-paint canvas rule").not.toBeNull();
    expect(match![1]).toBe(LIGHT_TOKENS.canvas);
    expect(match![2]).toBe(DARK_TOKENS.canvas);
  });
});

describe("cssVariablesResolver", () => {
  it("publishes the hammurabi tokens per scheme and the light-variant inks for the light scheme only", () => {
    const vars = cssVariablesResolver(DEFAULT_THEME);
    expect(vars.light["--hammurabi-accent-ink"]).toBe(LIGHT_TOKENS.accentInk);
    expect(vars.light["--hammurabi-canvas"]).toBe(LIGHT_TOKENS.canvas);
    expect(vars.light["--mantine-color-hammurabi-light-color"]).toBe(LIGHT_VARIANT_INKS.hammurabi);
    expect(vars.dark["--hammurabi-accent-ink"]).toBe(DARK_TOKENS.accentInk);
    expect(vars.dark["--mantine-color-hammurabi-light-color"]).toBeUndefined();
  });
});
