import { describe, expect, test } from "vitest";
import { render } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import BrandLogo from "./BrandLogo";
import { theme } from "../theme";

describe("BrandLogo", () => {
  test.each([
    ["light", "/logo-light.svg"],
    ["dark", "/logo-dark.svg"],
  ] as const)("the %s scheme draws %s, decorative and sized", (scheme, src) => {
    const { container } = render(
      <MantineProvider env="test" theme={theme} forceColorScheme={scheme}>
        <BrandLogo size={32} />
      </MantineProvider>,
    );
    const img = container.querySelector("img")!;
    expect(img).toHaveAttribute("src", src);
    expect(img).toHaveAttribute("alt", "");
    expect(img).toHaveStyle({ height: "32px", width: "32px" });
  });
});
