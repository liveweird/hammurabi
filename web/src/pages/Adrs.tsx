import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Anchor, Badge, Box, Button, CloseButton, Group, MultiSelect, Select, Stack, Switch, Table, Text, TextInput, VisuallyHidden } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconGavel, IconPlus } from "@tabler/icons-react";
import { listAdrs } from "../api/adrs";
import { getUserId } from "../api/session";
import AdrMatchText from "../components/AdrMatchText";
import AdrStatusBadge from "../components/AdrStatusBadge";
import AdrTagPills from "../components/AdrTagPills";
import ClearableTextInput from "../components/ClearableTextInput";
import FilterPanel from "../components/FilterPanel";
import LensPicker from "../components/LensPicker";
import PageHeader from "../components/PageHeader";
import RegistryListTable from "../components/RegistryListTable";
import SortHeader from "../components/SortHeader";
import TriStateSelect from "../components/TriStateSelect";
import { FILTER_DEBOUNCE_MS, usePagedSort } from "../hooks/usePagedSort";
import { useAdrListParams } from "../hooks/useAdrListParams";
import { useTagCategories } from "../hooks/useTagCategories";
import { DISCUSSION_KIND_COLOR } from "../utils/adrDiscussion";
import { activeAdrFilterCount, hasAdrFilters, isInvertedRange, NO_ADR_FILTERS, SEARCH_MAX_PAGE_SIZE } from "../utils/adrListParams";
import { adrPath, newAdrPath } from "../utils/adrLinks";
import { ADR_ROLES, ADR_STATUSES, isAdrRole, isAdrStatus } from "../utils/adrStatus";
import { MAX_TAG_FILTERS, tagOptionGroups } from "../utils/adrTags";
import { formatDate } from "../utils/formatDate";
import { filtersFromParams, paramsFromFilters } from "../utils/lensFilters";

// `number` is the key's order (ADR-0042 is number 42); the default is newest first, like the server's.
const SORT_FIELDS = ["number", "name", "status", "updatedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];

const SETTINGS_KEY = "adrs";

/**
 * The ADR list (`/adrs`): key, name, status badge, Driver, Approver, open issues and last update, newest first. EVERY filter lives in the URL
 * (`useAdrListParams`, so a filtered and searched list is a link): the full-text search box above the table (`text`, ranked, with highlighted
 * snippets under the names), and the Filters panel - a name-or-key substring (`q`), any-of statuses and tags, "mine" (the ADRs the signed-in user takes part in,
 * optionally in one DACI role), the created / updated ranges, the expiry bound and the three yes/no filters - all AND-combined. The lens picker
 * saves and re-applies a combination. The page, the page size and the sort are `usePagedSort`'s; while a search runs the server's relevance
 * order stands until a column is picked, and the page size stops at the server's cap. The name opens the ADR; anyone may raise one.
 */
export default function Adrs() {
  const { t } = useTranslation();
  const { params, update, search } = useAdrListParams();
  const [debouncedQ] = useDebouncedValue(params.q, FILTER_DEBOUNCE_MS);
  const participant = params.mine ? (getUserId() ?? undefined) : (params.participant ?? undefined);
  const textActive = params.text !== "";
  const categories = useTagCategories();
  // The server's relevance order is the default while searching; picking a column (while searching) takes it over until the search ends.
  const [sortPicked, setSortPicked] = useState(false);
  if (!textActive && sortPicked) setSortPicked(false);
  const relevanceOrder = textActive && !sortPicked;
  const filters = {
    q: debouncedQ.trim() || undefined,
    text: params.text || undefined,
    status: params.status,
    tag: params.tag,
    participant,
    role: params.role ?? undefined,
    createdFrom: params.createdFrom || undefined,
    createdTo: params.createdTo || undefined,
    updatedFrom: params.updatedFrom || undefined,
    updatedTo: params.updatedTo || undefined,
    expiresBefore: params.expiresBefore || undefined,
    hasOpenIssues: params.hasOpenIssues ?? undefined,
    supersedes: params.supersedes ?? undefined,
    supersededBy: params.supersededBy ?? undefined,
  };
  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } = usePagedSort<SortField>(
    "number",
    [JSON.stringify(filters), relevanceOrder],
    { key: SETTINGS_KEY, sortFields: SORT_FIELDS },
    "desc",
  );
  const effectivePageSize = textActive ? Math.min(pageSize, SEARCH_MAX_PAGE_SIZE) : pageSize;
  const sort = relevanceOrder ? undefined : sortParam;
  const createdInverted = isInvertedRange(params.createdFrom, params.createdTo);
  const updatedInverted = isInvertedRange(params.updatedFrom, params.updatedTo);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["adrs", "list", page, effectivePageSize, sort, filters],
    queryFn: () => listAdrs({ page, pageSize: effectivePageSize, sort, ...filters }),
    placeholderData: keepPreviousData,
    // An inverted range is the server's 400: the field says so instead and no request goes out.
    enabled: !createdInverted && !updatedInverted,
  });

  const sortHeader = (field: SortField, label: string) => (
    <SortHeader
      field={field}
      label={label}
      activeField={relevanceOrder ? null : sortField}
      activeDir={sortDir}
      onToggle={(picked) => {
        if (textActive) setSortPicked(true);
        toggleSort(picked);
      }}
    />
  );
  const dateField = (key: "createdFrom" | "createdTo" | "updatedFrom" | "updatedTo" | "expiresBefore", error?: string) => (
    <TextInput
      type="date"
      label={t(`adrs.filter.${key}`)}
      value={params[key]}
      onChange={(e) => update({ [key]: e.currentTarget.value })}
      error={error}
    />
  );
  const triState = (key: "hasOpenIssues" | "supersedes" | "supersededBy") => (
    <TriStateSelect label={t(`adrs.filter.${key}`)} value={params[key]} onChange={(value) => update({ [key]: value })} />
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

      <Box maw={480} data-print="hide">
        <ClearableTextInput
          label={t("adrs.filter.search")}
          value={search.value}
          onChange={search.onChange}
          clearLabel={t("adrs.filter.clearSearch")}
          placeholder={t("adrs.filter.searchPlaceholder")}
          description={search.tooShort ? t("adrs.filter.searchHint") : undefined}
          maxLength={100}
        />
      </Box>

      <FilterPanel
        activeFilterCount={activeAdrFilterCount(params)}
        storageKey={SETTINGS_KEY}
        aside={
          <Group gap="xs">
            <LensPicker filters={filtersFromParams(params, getUserId())} onApply={(lensFilters) => update(paramsFromFilters(lensFilters, getUserId()))} />
            {hasAdrFilters(params) && (
              <Button variant="subtle" size="xs" onClick={() => update(NO_ADR_FILTERS)}>
                {t("adrs.filter.clearAll")}
              </Button>
            )}
          </Group>
        }
      >
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
        <MultiSelect
          label={t("adrs.filter.tag")}
          placeholder={params.tag.length === 0 ? t("common.state.any") : undefined}
          data={tagOptionGroups(categories.data ?? [])}
          value={params.tag}
          onChange={(values) => update({ tag: values })}
          maxValues={MAX_TAG_FILTERS}
          searchable
          clearable
          clearButtonProps={{ "aria-label": t("adrs.filter.clearTag") }}
        />
        <Switch
          label={t("adrs.filter.mine")}
          checked={params.mine}
          onChange={(e) => update({ mine: e.currentTarget.checked })}
          mb={6}
        />
        {params.participant !== null && (
          <Badge
            variant="light"
            color="gray"
            mb={6}
            style={{ textTransform: "none" }}
            rightSection={<CloseButton size="xs" aria-label={t("adrs.filter.clearParticipant")} onClick={() => update({ participant: null, role: null })} />}
          >
            {t("adrs.filter.participant", { id: params.participant })}
          </Badge>
        )}
        <Select
          label={t("adrs.filter.role")}
          placeholder={t("common.state.any")}
          data={ADR_ROLES.map((role) => ({ value: role, label: t(`adrs.role.${role}`) }))}
          value={params.role}
          onChange={(value) => update({ role: isAdrRole(value) ? value : null })}
          disabled={!params.mine && params.participant === null}
          clearable
          clearButtonProps={{ "aria-label": t("adrs.filter.clearRole") }}
        />
        {dateField("createdFrom")}
        {dateField("createdTo", createdInverted ? t("adrs.filter.rangeInverted") : undefined)}
        {dateField("updatedFrom")}
        {dateField("updatedTo", updatedInverted ? t("adrs.filter.rangeInverted") : undefined)}
        {dateField("expiresBefore")}
        {triState("hasOpenIssues")}
        {triState("supersedes")}
        {triState("supersededBy")}
      </FilterPanel>

      {relevanceOrder && (
        <Text size="xs" c="dimmed">
          {t("adrs.filter.relevance")}
        </Text>
      )}

      <RegistryListTable
        label={t("adrs.title")}
        errorTitle={t("adrs.loadFailed")}
        error={error}
        isError={isError}
        isLoading={isLoading}
        hasData={Boolean(data)}
        rowCount={data?.items.length ?? 0}
        columnCount={8}
        emptyIcon={IconGavel}
        emptyLabel={hasAdrFilters(params) ? t("adrs.emptyFiltered") : t("adrs.empty")}
        header={
          <Table.Tr>
            {sortHeader("number", t("adrs.column.key"))}
            {sortHeader("name", t("common.field.name"))}
            {sortHeader("status", t("adrs.column.status"))}
            <Table.Th>{t("adrs.role.DRIVER")}</Table.Th>
            <Table.Th>{t("adrs.role.APPROVER")}</Table.Th>
            <Table.Th>{t("adrs.column.tags")}</Table.Th>
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
                {adr.match ? <AdrMatchText segments={adr.match.name} /> : adr.name}
              </Anchor>
              {adr.match && adr.match.snippet.length > 0 && (
                <Text size="xs" c="dimmed" lineClamp={2} mt={2}>
                  <AdrMatchText segments={adr.match.snippet} />
                </Text>
              )}
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
              <AdrTagPills tags={adr.tags} none="—" />
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
        pageSize={effectivePageSize}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
        maxPageSize={textActive ? SEARCH_MAX_PAGE_SIZE : undefined}
      />
    </Stack>
  );
}
