import { useId, type ReactNode } from "react";
import { Anchor, Badge, Button, Group, Paper, Select, Stack, Text, Title, VisuallyHidden } from "@mantine/core";
import { IconActivity, IconAlertTriangle, IconBriefcase, IconClipboardCheck, IconPlus } from "@tabler/icons-react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useSearchParams } from "react-router-dom";
import {
  getAdrMyWork,
  type AdrEventResponse,
  type AdrMyWork,
  type AdrMyWorkAdr,
  type AdrMyWorkAttention,
  type AdrRole,
} from "../api/adrs";
import AdrStatusBadge from "../components/AdrStatusBadge";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import { adrEventActor, adrEventLines, describeAdrEvent } from "../utils/adrEvents";
import { adrListPath } from "../utils/adrListParams";
import { adrDiscussionPath, adrIssuesPath, adrPath, adrValidityPath, newAdrPath } from "../utils/adrLinks";
import { ADR_ROLES, isAdrRole, MY_WORK_REASON_COLOR } from "../utils/adrStatus";
import { DAY_MS, epochMillisToIsoDate } from "../utils/isoDate";
import classes from "../theme.module.css";
import styles from "./MyWork.module.css";

/** My work is a reading page: its cap (78rem) sits between the registry pages and the form pages. */
const MY_WORK_MAX_WIDTH = 1248;

function wholeDays(from: number, to: number): number {
  return Math.max(0, Math.floor((to - from) / DAY_MS));
}

function daysFromToday(day: string, generatedAt: number): number {
  const today = Date.parse(`${epochMillisToIsoDate(generatedAt)}T00:00:00Z`);
  return Math.round((Date.parse(`${day}T00:00:00Z`) - today) / DAY_MS);
}

function relativeTime(at: number, generatedAt: number, t: TFunction): string {
  const minutes = Math.max(0, Math.floor((generatedAt - at) / 60_000));
  if (minutes < 1) return String(t("myWork.relative.now"));
  if (minutes < 60) return String(t("myWork.relative.minutes", { count: minutes }));
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return String(t("myWork.relative.hours", { count: hours }));
  return String(t("myWork.relative.days", { count: Math.floor(hours / 24) }));
}

function attentionPath(item: AdrMyWorkAttention): string {
  if (item.reason === "EXPIRING") return adrValidityPath(item.adr.id);
  if (item.reason === "ESCALATION") return adrIssuesPath(item.adr.id);
  return adrPath(item.adr.id);
}

function activityPath(adrId: number, event: AdrEventResponse): string {
  if (event.type === "COMMENTED" || event.type.startsWith("QUESTION_")) return adrDiscussionPath(adrId);
  if (event.type === "ESCALATED" || event.type.startsWith("ISSUE_")) return adrIssuesPath(adrId);
  if (event.type === "EXPIRED" || (event.type === "UPDATED" && (event.params.changed ?? "").split(",").includes("expiresOn"))) return adrValidityPath(adrId);
  return adrPath(adrId);
}

/** The words for one attention item. Exhaustive over the schema's reasons: a new server reason fails the type check here, not at runtime. */
function reasonText(item: AdrMyWorkAttention, generatedAt: number, t: TFunction): string {
  switch (item.reason) {
    case "EXPIRING": {
      // The server reports EXPIRING for a dated decision only; an undated one (not expected) gets the plain word, not a made-up day count.
      if (item.adr.expiresOn === null) return t("myWork.reason.EXPIRING");
      const days = daysFromToday(item.adr.expiresOn, generatedAt);
      if (days === 0) return t("myWork.reason.EXPIRING_today");
      return days > 0 ? t("myWork.reason.EXPIRING_future", { count: days }) : t("myWork.reason.EXPIRING_overdue", { count: -days });
    }
    case "STALLED_DRAFT":
    case "STALLED_REVIEW":
      return t(`myWork.reason.${item.reason}`, { count: wholeDays(item.since, generatedAt) });
    case "REVIEW":
    case "RETURNED":
    case "ESCALATION":
      return t(`myWork.reason.${item.reason}`);
  }
}

function AttentionReason({ item, generatedAt, watching }: { item: AdrMyWorkAttention; generatedAt: number; watching: boolean }) {
  const { t } = useTranslation();
  const reason = reasonText(item, generatedAt, t);
  const label = watching && item.reason === "EXPIRING" && item.approverName
    ? t("myWork.reason.EXPIRING_owner", { reason, name: item.approverName })
    : reason;
  return (
    <Badge color={MY_WORK_REASON_COLOR[item.reason]} variant="light" size="sm" classNames={{ root: styles.reason, label: `${classes.breakAnywhere} ${styles.reasonLabel}` }}>
      {label}
    </Badge>
  );
}

function AdrIdentity({ adr, status = false }: { adr: AdrMyWorkAdr; status?: boolean }) {
  const { t } = useTranslation();
  return <Text size="xs" c="dimmed">{adr.key} · {t(`adrs.role.${adr.role}`)}{status ? ` · ${t(`adrs.status.${adr.status}`)}` : ""}</Text>;
}

function Section({ title, detail, icon: Icon, children, className, busy }: { title: string; detail?: string; icon: typeof IconBriefcase; children: ReactNode; className?: string; busy?: boolean }) {
  const id = useId();
  return (
    <Paper component="section" withBorder className={`${classes.surface} ${styles.section} ${className ?? ""}`} aria-labelledby={id} aria-busy={busy}>
      <header className={styles.sectionHeader}>
        <Group gap="xs" wrap="nowrap"><Icon size={18} stroke={1.7} aria-hidden /><Title id={id} order={3} size="h4">{title}</Title></Group>
        {detail && <Text size="xs" c="dimmed">{detail}</Text>}
      </header>
      {children}
    </Paper>
  );
}

function AttentionList({ items, generatedAt, watching }: { items: AdrMyWorkAttention[]; generatedAt: number; watching: boolean }) {
  return (
    <ul className={`${classes.plainList} ${classes.divided}`}>
      {items.map((item) => (
        <li key={item.adr.id} className={`${styles.row} ${classes.breakAnywhere}`}>
          <div className={styles.rowMain}>
            <Anchor component={RouterLink} to={attentionPath(item)} fw={600} size="sm">{item.adr.name}</Anchor>
            <AdrIdentity adr={item.adr} status />
          </div>
          <AttentionReason item={item} generatedAt={generatedAt} watching={watching} />
        </li>
      ))}
    </ul>
  );
}

function MoreNotice({ visible, kind }: { visible: boolean; kind: "attention" | "activity" | "adrs" }) {
  const { t } = useTranslation();
  return visible ? <Text size="xs" c="dimmed" className={`${classes.ruleAbove} ${styles.more}`}>{t(`myWork.more.${kind}`)}</Text> : null;
}

/** `role` is the one `work` was fetched for (not the filter now selected); `stale` says the next answer is still loading. */
function WorkContent({ work, role, stale }: { work: AdrMyWork; role?: AdrRole; stale: boolean }) {
  const { t } = useTranslation();
  const listPath = adrListPath({ mine: true, participantRole: role ?? null });
  return (
    <>
      <div className={`${styles.primaryGrid} ${stale ? classes.stale : ""}`} aria-busy={stale}>
        <Section title={t("myWork.actions.title")} icon={IconClipboardCheck}>
          {work.actions.items.length === 0 ? <EmptyState compact icon={IconClipboardCheck} label={t("myWork.actions.empty")} /> : <AttentionList items={work.actions.items} generatedAt={work.generatedAt} watching={false} />}
          <MoreNotice visible={work.actions.hasMore} kind="attention" />
          <div className={styles.subsection}>
            <Title order={4} size="h5">{t("myWork.watching.title")}</Title>
          </div>
          {work.watching.items.length === 0 ? <EmptyState compact icon={IconAlertTriangle} label={t("myWork.watching.empty")} /> : <AttentionList items={work.watching.items} generatedAt={work.generatedAt} watching />}
          <MoreNotice visible={work.watching.hasMore} kind="attention" />
        </Section>
        <Section title={t("myWork.activity.title")} detail={t("myWork.activity.period")} icon={IconActivity}>
          {work.activity.items.length === 0 ? <EmptyState compact icon={IconActivity} label={t("myWork.activity.empty")} /> : (
            <ol className={`${classes.plainList} ${classes.divided}`}>
              {work.activity.items.map(({ adr, event }) => (
                <li key={event.id} className={`${styles.activityRow} ${classes.breakAnywhere}`}>
                  <Anchor component={RouterLink} to={activityPath(adr.id, event)} size="sm">
                    <strong>{adrEventActor(event, t)}</strong> · {describeAdrEvent(event, t)}
                    <Text component="span" display="block" size="xs" c="dimmed">
                      {adr.key} · {adr.name} · {t(`adrs.role.${adr.role}`)} · {relativeTime(event.createdAt, work.generatedAt, t)}
                    </Text>
                  </Anchor>
                  {event.type === "UPDATED" && (
                    <Text size="xs" c="dimmed">{adrEventLines(event, t).join(" · ")}</Text>
                  )}
                </li>
              ))}
            </ol>
          )}
          <MoreNotice visible={work.activity.hasMore} kind="activity" />
        </Section>
      </div>
      <Section title={t("myWork.adrs.title")} detail={t("myWork.adrs.order")} icon={IconBriefcase} className={`${styles.myAdrs} ${stale ? classes.stale : ""}`} busy={stale}>
        {work.adrs.items.length === 0 ? <EmptyState compact icon={IconBriefcase} label={t("myWork.adrs.empty")} /> : (
          <ul className={`${classes.plainList} ${classes.divided}`}>
            {work.adrs.items.map((adr) => (
              <li key={adr.id} className={`${styles.row} ${classes.breakAnywhere}`}>
                <div className={styles.rowMain}>
                  <Anchor component={RouterLink} to={adrPath(adr.id)} fw={600} size="sm">{adr.name}</Anchor>
                  <AdrIdentity adr={adr} />
                  <Text size="xs" c="dimmed">{t("myWork.adrs.lastActivity", { time: relativeTime(adr.lastActivityAt, work.generatedAt, t) })}</Text>
                </div>
                <AdrStatusBadge status={adr.status} />
              </li>
            ))}
          </ul>
        )}
        <MoreNotice visible={work.adrs.hasMore} kind="adrs" />
        <Anchor component={RouterLink} to={listPath} size="sm" className={styles.viewAll}>{t("myWork.adrs.viewAll")}</Anchor>
      </Section>
    </>
  );
}

/** What the live region says once the answer is in: the role in view and how many rows each section holds (`n+` when the server cut it). */
function resultAnnouncement(work: AdrMyWork, role: AdrRole | undefined, t: TFunction): string {
  const count = (section: { items: unknown[]; hasMore: boolean }) => `${section.items.length}${section.hasMore ? "+" : ""}`;
  return [
    role ? t(`adrs.role.${role}`) : t("myWork.role.all"),
    `${t("myWork.actions.title")}: ${count(work.actions)}`,
    `${t("myWork.watching.title")}: ${count(work.watching)}`,
    `${t("myWork.activity.title")}: ${count(work.activity)}`,
    `${t("myWork.adrs.title")}: ${count(work.adrs)}`,
  ].join(". ");
}

/** `/my-work`: the signed-in user's role-aware action queue, activity feed and current ADRs. */
export default function MyWork() {
  const { t } = useTranslation();
  const [search, setSearch] = useSearchParams();
  const rawRole = search.get("participantRole");
  const role = isAdrRole(rawRole) ? rawRole : undefined;
  // The previous role's answer stays on screen (dimmed, `aria-busy`) while the next one loads: changing the filter never blanks the page.
  // It travels with the role it was fetched for, so its links never follow a filter it does not answer.
  const query = useQuery({ queryKey: ["adrs", "my-work", role ?? "all"], queryFn: async () => ({ role, work: await getAdrMyWork(role) }), placeholderData: keepPreviousData });
  const roleOptions = [
    { value: "", label: t("myWork.role.all") },
    ...ADR_ROLES.map((value) => ({ value, label: t(`adrs.role.${value}`) })),
  ];
  return (
    <Stack gap="lg" maw={MY_WORK_MAX_WIDTH} pb="md" className={styles.page}>
      <PageHeader
        title={t("myWork.title")}
        description={t("myWork.description")}
        actions={<Button component={RouterLink} to={newAdrPath} leftSection={<IconPlus size={16} />}>{t("myWork.raise")}</Button>}
        toolbar={
          <Select
            label={t("myWork.role.label")}
            data={roleOptions}
            value={role ?? ""}
            allowDeselect={false}
            className={styles.roleFilter}
            onChange={(value) => {
              const next = new URLSearchParams(search);
              if (isAdrRole(value)) next.set("participantRole", value);
              else next.delete("participantRole");
              setSearch(next, { replace: true });
            }}
          />
        }
      />
      {query.isError && (
        <Stack gap="xs">
          <ErrorAlert error={query.error} title={t("myWork.loadFailed")} />
          <Group><Button variant="default" size="xs" onClick={() => void query.refetch()}>{t("common.action.retry")}</Button></Group>
        </Stack>
      )}
      {query.isLoading && <LoadingBlock />}
      {query.data && <WorkContent work={query.data.work} role={query.data.role} stale={query.isPlaceholderData} />}
      <VisuallyHidden aria-live="polite">{query.data && !query.isPlaceholderData ? resultAnnouncement(query.data.work, query.data.role, t) : ""}</VisuallyHidden>
    </Stack>
  );
}
