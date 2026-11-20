/**
 * Chart colours (telemetry trend charts today).
 *
 * The categorical palette: the colour of the Nth line of a chart whose series are arbitrary NAMES (a process role, a database
 * schema). Such a name must not wear a colour that MEANS something (teal = success, orange = warning, red = blocking, cyan = in
 * progress), so those hues are excluded, and so are the stock `blue` and `indigo` (the brand's lapis sits right beside them: a line
 * in one of them would read as the brand). Each entry is the ONE shade of its hue that clears 3:1 on all four chart surfaces, so
 * there is no per-scheme pair. Series are coloured by position (`palette[index % length]`), and the order maximises the CIEDE2000
 * distance between NEIGHBOURS (dataviz floor ≥ 15; violet is only ΔE 11 from the brand, so it never sits next to it).
 * `chartColors.test.ts` recomputes every ratio and distance from the theme (white / canvas | dark paper / dark canvas | ΔE to the
 * next entry):
 *
 *   hammurabi.5  4.48 / 4.12 | 3.03 / 3.68   ΔE 22.8
 *   grape.5      3.33 / 3.06 | 4.07 / 4.94   ΔE 21.0
 *   pink.6       3.73 / 3.43 | 3.64 / 4.42   ΔE 29.4
 *   violet.5     4.26 / 3.92 | 3.18 / 3.87   ΔE 18.4
 *   gray.6       3.32 / 3.05 | 4.09 / 4.96   ΔE 17.8 (to the first entry again)
 *
 * Five is all the passing hues give, so from the 6th series on the first colour repeats: a chart on this palette still carries
 * identity without colour through the legend and the table beside the chart.
 */
export const CATEGORY_PALETTE: ReadonlyArray<string> = ["hammurabi.5", "grape.5", "pink.6", "violet.5", "gray.6"];
