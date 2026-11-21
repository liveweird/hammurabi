import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Button, Stack, Text } from "@mantine/core";
import { useMutation } from "@tanstack/react-query";
import { IconDownload } from "@tabler/icons-react";
import type { Download } from "../api/download";
import { exportErrorMessage } from "../utils/adrExport";
import { saveFile } from "../utils/saveFile";

/**
 * The ADR export action (0.8.0): fetches the file with the session's token ([run]), hands it to the browser's save flow and says so inline
 * when it fails (errors stay next to what failed, never a toast). It is no content of its own, so it stays off the printed page.
 */
export default function AdrExportButton({
  label,
  run,
  disabled = false,
  icon = <IconDownload size={16} />,
}: {
  label: string;
  run: () => Promise<Download>;
  disabled?: boolean;
  icon?: ReactNode;
}) {
  const { t } = useTranslation();
  const exporting = useMutation({ mutationFn: run, onSuccess: saveFile });
  return (
    <Stack gap={4} align="flex-end" data-print="hide">
      <Button variant="default" leftSection={icon} loading={exporting.isPending} disabled={disabled} onClick={() => exporting.mutate()}>
        {label}
      </Button>
      {exporting.isError && (
        <Text size="xs" c="red" role="alert" maw={320} ta="right">
          {exportErrorMessage(exporting.error, t)}
        </Text>
      )}
    </Stack>
  );
}
