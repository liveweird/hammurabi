import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Anchor, Button, Group, Stack, Text } from "@mantine/core";
import { IconLink, IconLinkOff } from "@tabler/icons-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { listAdrs, setAdrSupersedes, type AdrRef, type AdrResponse, type AdrStatus } from "../api/adrs";
import { adrPath } from "../utils/adrLinks";
import { isPreActive, isTerminal } from "../utils/adrStatus";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import AdrStatusBadge from "./AdrStatusBadge";
import AdrWriteError from "./AdrWriteError";
import SearchPicker, { type PickedOption } from "./SearchPicker";

/** The ADRs a successor may replace: in force, or still on their way (a terminal one has nothing left to replace). */
const LINKABLE: readonly AdrStatus[] = ["PROBLEM_RAISED", "SOLUTION_DRAFT", "IN_REVIEW", "ACTIVE"];
const PICKER_PAGE_SIZE = 10;
/** The longest search term the list takes (the server's `MAX_ADR_QUERY_LENGTH`). */
const PICKER_MAX_TERM_LENGTH = 100;

function Ref({ adr }: { adr: AdrRef }) {
  return (
    <Group gap={6} wrap="nowrap" align="flex-start">
      <Anchor component={RouterLink} to={adrPath(adr.id)} size="sm" style={{ overflowWrap: "anywhere" }}>
        {adr.key} {adr.name}
      </Anchor>
      <AdrStatusBadge status={adr.status} />
    </Group>
  );
}

/**
 * The Successor section: both ends of the link - the ADR this one supersedes (`supersedes`) and the one replacing it (`supersededBy`,
 * pending or done). Whoever the server says may edit the link (`canEditSuccessor`: the Driver, the Approver or an ADMIN, while the ADR is
 * pre-active) gets a picker that searches ADRs by key or name over the list endpoint and a button to remove an existing link. The link
 * takes effect at activation, which the hint says. A cycle, a rival successor or a stale revision is the write's error notice.
 */
export default function AdrSuccessorPanel({ adr }: { adr: AdrResponse }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [picked, setPicked] = useState<PickedOption | null>(null);
  const write = useMutation({
    mutationFn: (target: number | null) => setAdrSupersedes(adr.id, adr.revision, target),
    onSuccess: async (_result, target) => {
      setPicked(null);
      await refreshQueriesAfterMutation(queryClient, ["adrs"]);
      showSuccessToast(target === null ? t("adrs.toast.unlinked") : t("adrs.toast.linked"));
    },
  });

  return (
    <Stack gap="xs">
      <div>
        <Text size="xs" c="dimmed">
          {t("adrs.successor.supersedes")}
        </Text>
        {adr.supersedes ? (
          <Ref adr={adr.supersedes} />
        ) : (
          <Text size="sm" c="dimmed">
            {t("adrs.successor.none")}
          </Text>
        )}
        {/* A predecessor that ended (withdrawn, expired, cancelled) leaves nothing to replace: activation is refused until the link goes. */}
        {adr.supersedes && isTerminal(adr.supersedes.status) && isPreActive(adr.status) && (
          <Text size="xs" c="orange" role="status">
            {t("adrs.successor.predecessorOver", { key: adr.supersedes.key, status: t(`adrs.status.${adr.supersedes.status}`) })}
          </Text>
        )}
      </div>
      {adr.supersededBy && (
        <div>
          <Text size="xs" c="dimmed">
            {adr.status === "SUPERSEDED" ? t("adrs.successor.supersededBy") : t("adrs.successor.pending")}
          </Text>
          <Ref adr={adr.supersededBy} />
        </div>
      )}
      {adr.canEditSuccessor && (
        <>
          {adr.supersedes ? (
            <Button
              variant="default"
              size="xs"
              style={{ alignSelf: "flex-start" }}
              leftSection={<IconLinkOff size={14} />}
              onClick={() => write.mutate(null)}
              loading={write.isPending}
            >
              {t("adrs.successor.unlink")}
            </Button>
          ) : (
            <Stack gap="xs">
              <SearchPicker
                label={t("adrs.successor.pickerLabel")}
                description={t("adrs.successor.hint")}
                placeholder={t("adrs.successor.pickerPlaceholder")}
                clearLabel={t("adrs.successor.pickerClear")}
                noMatchLabel={t("adrs.successor.noMatch")}
                queryKey={["adrs", "picker"]}
                search={async (term) => {
                  const page = await listAdrs({ page: 1, pageSize: PICKER_PAGE_SIZE, sort: "-number", q: term || undefined, status: LINKABLE });
                  return page.items.map((item) => ({ value: String(item.id), label: `${item.key} ${item.name}` }));
                }}
                exclude={new Set([String(adr.id)])}
                lazy
                maxLength={PICKER_MAX_TERM_LENGTH}
                picked={picked}
                onPick={setPicked}
              />
              <Button
                size="xs"
                style={{ alignSelf: "flex-start" }}
                leftSection={<IconLink size={14} />}
                disabled={picked === null}
                loading={write.isPending}
                onClick={() => picked && write.mutate(Number(picked.value))}
              >
                {t("adrs.successor.link")}
              </Button>
            </Stack>
          )}
        </>
      )}
      {write.isError && <AdrWriteError error={write.error} kind="supersedes" onReloaded={write.reset} />}
    </Stack>
  );
}
