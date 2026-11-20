import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Anchor, Badge, Button, MultiSelect, Select, Stack, Switch, Table, Text, VisuallyHidden } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconGavel, IconPlus } from "@tabler/icons-react";
import { listAdrs } from "../api/adrs";
import { getUserId } from "../api/session";
import AdrStatusBadge from "../components/AdrStatusBadge";
import ClearableTextInput from "../components/ClearableTextInput";
import FilterPanel from "../components/FilterPanel";
import PageHeader from "../components/PageHeader";
import RegistryListTable from "../components/RegistryListTable";
import SortHeader from "../components/SortHeader";
import { FILTER_DEBOUNCE_MS, usePagedSort } from "../hooks/usePagedSort";
import { useAdrListParams } from "../hooks/useAdrListParams";
import { DISCUSSION_KIND_COLOR } from "../utils/adrDiscussion";
import { activeAdrFilterCount } from "../utils/adrListParams";
import { adrPath, newAdrPath } from "../utils/adrLinks";
import { ADR_ROLES, ADR_STATUSES, isAdrRole, isAdrStatus } from "../utils/adrStatus";
import { formatDate } from "../utils/formatDate";

// `number` is the key's order (ADR-0042 is number 42); the default is newest first, like the server's.
const SORT_FIELDS = ["number", "name", "status", "updatedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];

const SETTINGS_KEY = "adrs";

/**
 * The ADR list (`/adrs`): key, name, status badge, Driver, Approver, open issues and last update, newest first. The filters — a name-or-key
 * search, any-of statuses and "mine" (the ADRs the signed-in user takes part in, optionally in one DACI role) — live in the URL
 * (`useAdrListParams`); the page, the page size and the sort are `usePagedSort`'s. The name opens the ADR; anyone may raise one. A badge
 * counts the open issues and escalations of an ADR (the server's `openIssueCount`).
 */
export default function Adrs() {
  const { t } = useTranslation();
  const { params, update } = useAdrListParams();
  const [debouncedQ] = useDebouncedValue(params.q, FILTER_DEBOUNCE_MS);
  const participant = params.mine ? (getUserId() ?? undefined) : undefined;
  const statusKey = params.status.join(",");
  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } = usePagedSort<SortField>(
    "number",
    [debouncedQ, statusKey, params.mine, params.role],
    { key: SETTINGS_KEY, sortFields: SORT_FIELDS },
    "desc",
  );

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["adrs", "list", page, pageSize, sortParam, debouncedQ.trim(), statusKey, participant, params.role],
    queryFn: () =>
      listAdrs({
        page,
        pageSize,
        sort: sortParam,
        q: debouncedQ.trim() || undefined,
        status: params.status,
        participant,
        role: params.role ?? undefined,
      }),
    placeholderData: keepPreviousData,
  });

  const sortHeader = (field: SortField, label: string) => (
    <SortHeader field={field} label={label} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
  );

  return (
    <Stack gap="md">
      <PageHeader
        title={t("adrs.title")}
        description={t("adrs.intro")}
        actions={
          <Button component={RouterLink} to={newAdrPath} leftSection={<IconPlus size={16} />}>
            {t("adrs.newAdr")}
          </Button>
        }
      />

      <FilterPanel activeFilterCount={activeAdrFilterCount(params)} storageKey={SETTINGS_KEY}>
        <ClearableTextInput
          label={t("adrs.filter.q")}
          value={params.q}
          onChange={(q) => update({ q })}
          clearLabel={t("adrs.filter.clearQ")}
        />
        <MultiSelect
          label={t("adrs.filter.status")}
          placeholder={params.status.length === 0 ? t("common.state.any") : undefined}
          data={ADR_STATUSES.map((status) => ({ value: status, label: t(`adrs.status.${status}`) }))}
          value={params.status}
          onChange={(values) => update({ status: values.filter(isAdrStatus) })}
          clearable
          clearButtonProps={{ "aria-label": t("adrs.filter.clearStatus") }}
        />
        <Switch
          label={t("adrs.filter.mine")}
          checked={params.mine}
          onChange={(e) => update({ mine: e.currentTarget.checked })}
          mb={6}
        />
        <Select
          label={t("adrs.filter.role")}
          placeholder={t("common.state.any")}
          data={ADR_ROLES.map((role) => ({ value: role, label: t(`adrs.role.${role}`) }))}
          value={params.role}
          onChange={(value) => update({ role: isAdrRole(value) ? value : null })}
          disabled={!params.mine}
          clearable
          clearButtonProps={{ "aria-label": t("adrs.filter.clearRole") }}
        />
      </FilterPanel>

      <RegistryListTable
        label={t("adrs.title")}
        errorTitle={t("adrs.loadFailed")}
        error={error}
        isError={isError}
        isLoading={isLoading}
        hasData={Boolean(data)}
        rowCount={data?.items.length ?? 0}
        columnCount={7}
        emptyIcon={IconGavel}
        emptyLabel={activeAdrFilterCount(params) > 0 ? t("adrs.emptyFiltered") : t("adrs.empty")}
        header={
          <Table.Tr>
            {sortHeader("number", t("adrs.column.key"))}
            {sortHeader("name", t("common.field.name"))}
            {sortHeader("status", t("adrs.column.status"))}
            <Table.Th>{t("adrs.role.DRIVER")}</Table.Th>
            <Table.Th>{t("adrs.role.APPROVER")}</Table.Th>
            <Table.Th>{t("adrs.column.openIssues")}</Table.Th>
            {sortHeader("updatedAt", t("adrs.column.updated"))}
          </Table.Tr>
        }
        rows={data?.items.map((adr) => (
          <Table.Tr key={adr.id}>
            <Table.Td>
              <Text size="sm" ff="monospace" span>
                {adr.key}
              </Text>
            </Table.Td>
            <Table.Td>
              {/* The name is the way into the ADR — a real link (the detail-link rule). */}
              <Anchor component={RouterLink} to={adrPath(adr.id)} fw={500} size="sm" aria-label={t("adrs.openAria", { key: adr.key, name: adr.name })}>
                {adr.name}
              </Anchor>
            </Table.Td>
            <Table.Td>
              <AdrStatusBadge status={adr.status} />
            </Table.Td>
            <Table.Td>
              <Text size="sm" c={adr.driver ? undefined : "dimmed"}>
                {adr.driver?.name ?? "—"}
              </Text>
            </Table.Td>
            <Table.Td>
              <Text size="sm" c={adr.approver ? undefined : "dimmed"}>
                {adr.approver?.name ?? "—"}
              </Text>
            </Table.Td>
            <Table.Td>
              {adr.openIssueCount > 0 ? (
                <Badge color={DISCUSSION_KIND_COLOR.ISSUE} variant="light">
                  <span aria-hidden>{adr.openIssueCount}</span>
                  <VisuallyHidden>{t("adrs.openIssuesAria", { count: adr.openIssueCount })}</VisuallyHidden>
                </Badge>
              ) : (
                <Text size="sm" c="dimmed">
                  —
                </Text>
              )}
            </Table.Td>
            <Table.Td>
              <Text size="sm">{formatDate(adr.updatedAt)}</Text>
            </Table.Td>
          </Table.Tr>
        ))}
        total={data?.total ?? 0}
        page={page}
        pageSize={pageSize}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
      />
    </Stack>
  );
}
