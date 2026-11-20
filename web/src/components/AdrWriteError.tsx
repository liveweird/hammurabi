import { useTranslation } from "react-i18next";
import { Alert, Button, Group } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { adrConflict, adrWriteError, type AdrWriteKind } from "../utils/adrErrors";
import FormErrorAlert from "./FormErrorAlert";

/**
 * The inline failure of an ADR write. A typed conflict (the document moved on, the content froze, the lifecycle refused the move) means
 * the page shows a stale ADR: it renders as an orange notice with a button that reloads the ADR (and its history) and then calls
 * `onReloaded` so the caller drops the failed attempt. Every other failure is the usual red alert in fixed words.
 */
export default function AdrWriteError({
  error,
  kind,
  title,
  onReloaded,
}: {
  error: unknown;
  kind: AdrWriteKind;
  title?: string;
  onReloaded: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const message = adrWriteError(error, t, kind);
  if (adrConflict(error) === null) return <FormErrorAlert title={title}>{message}</FormErrorAlert>;
  return (
    <Alert color="orange" variant="light" title={title ?? t("adrs.error.conflictTitle")} role="alert">
      {message}
      <Group mt="xs">
        <Button
          size="compact-sm"
          variant="default"
          onClick={() => {
            void queryClient.invalidateQueries({ queryKey: ["adrs"] }).then(onReloaded);
          }}
        >
          {t("adrs.error.reload")}
        </Button>
      </Group>
    </Alert>
  );
}
