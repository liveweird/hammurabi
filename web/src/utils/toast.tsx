import { notifications } from "@mantine/notifications";
import { IconCheck } from "@tabler/icons-react";
import i18n from "../i18n";

/**
 * The one success-toast entry point (host: `<Notifications />` in main.tsx). Success only —
 * errors stay inline (red Alerts next to the form/list). Teal is the semantic-success color
 * (stock green would impersonate a brand color).
 *
 * Content rule: pass fixed vocabulary (`t("<area>.toast.*")`) only — NEVER user-entered
 * names/values.
 */
export function showSuccessToast(message: string) {
  // Mantine's close X has no default name (WCAG 4.1.2; the e2e axe scans catch it while a toast is up).
  notifications.show({
    message,
    color: "teal",
    icon: <IconCheck size={16} />,
    closeButtonProps: { "aria-label": i18n.t("common.action.close") },
  });
}
