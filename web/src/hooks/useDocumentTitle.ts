import { useEffect } from "react";
import { useTranslation } from "react-i18next";

/** The separator of "Page · Hammurabi"; the one place the tab title's shape is spelled. */
const SEPARATOR = " · ";

/**
 * Sets the browser tab's title to "{title} · Hammurabi" for as long as the calling page is mounted (WCAG 2.4.2: a tab
 * titled "Hammurabi" on every route says nothing about where the reader is), and puts the bare brand back on unmount, so
 * the lazy-chunk fallback between pages shows "Hammurabi", never the last page's name.
 * A page titled with the brand itself gets the bare brand. Called once per page, by `PageHeader`
 * and `AuthCard`; the brand is `appShell.brand`, so a language switch re-titles the tab on the next render.
 */
export function useDocumentTitle(title: string): void {
  const { t } = useTranslation();
  const brand = t("appShell.brand");
  useEffect(() => {
    document.title = title && title !== brand ? `${title}${SEPARATOR}${brand}` : brand;
    return () => {
      document.title = brand;
    };
  }, [title, brand]);
}
