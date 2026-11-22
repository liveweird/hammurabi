import { useId, type ReactNode } from "react";
import { Anchor, Badge, Button, Group, Paper, Select, Stack, Text, Title, VisuallyHidden } from "@mantine/core";
import { IconActivity, IconAlertTriangle, IconBriefcase, IconClipboardCheck, IconPlus } from "@tabler/icons-react";
import { useQuery } from "@tanstack/react-query";
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
import { ADR_ROLES, isAdrRole } from "../utils/adrStatus";
import { epochMillisToIsoDate } from "../utils/isoDate";
import styles from "./MyWork.module.css";

const DAY_MS = 86_400_000;

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

function AttentionReason({ item, generatedAt, watching }: { item: AdrMyWorkAttention; generatedAt: number; watching: boolean }) {
  const { t } = useTranslation();
  let reason: string;
  if (item.reason === "EXPIRING" && item.adr.expiresOn !== null) {
    const days = daysFromToday(item.adr.expiresOn, generatedAt);
    reason = days === 0
      ? t("myWork.reason.EXPIRING_today")
      : days > 0
        ? t("myWork.reason.EXPIRING_future", { count: days })
        : t("myWork.reason.EXPIRING_overdue", { count: -days });
  } else if (item.reason === "STALLED_DRAFT" || item.reason === "STALLED_REVIEW") {
    reason = t(`myWork.reason.${item.reason}`, { count: wholeDays(item.since, generatedAt) });
  } else if (item.reason === "REVIEW") {
    reason = t("myWork.reason.REVIEW");
  } else if (item.reason === "RETURNED") {
    reason = t("myWork.reason.RETURNED");
  } else if (item.reason === "ESCALATION") {
    reason = t("myWork.reason.ESCALATION");
  } else {
    reason = t("myWork.reason.EXPIRING_today");
  }
  const label = watching && item.reason === "EXPIRING" && item.approverName
    ? t("myWork.reason.EXPIRING_owner", { reason, name: item.approverName })
    : reason;
  const color = item.reason === "ESCALATION" ? "red" : item.reason === "EXPIRING" ? "orange" : item.reason === "STALLED_DRAFT" ? "gray" : "grape";
  return <Badge color={color} variant="light" size="sm" classNames={{ root: styles.reason, label: styles.reasonLabel }}>{label}</Badge>;
}

function AdrIdentity({ adr, status = false }: { adr: AdrMyWorkAdr; status?: boolean }) {
  const { t } = useTranslation();
  return <Text size="xs" c="dimmed">{adr.key} · {t(`adrs.role.${adr.role}`)}{status ? ` · ${t(`adrs.status.${adr.status}`)}` : ""}</Text>;
}

function Section({ title, detail, icon: Icon, children, className }: { title: string; detail?: string; icon: typeof IconBriefcase; children: ReactNode; className?: string }) {
  const id = useId();
  return (
    <Paper component="section" withBorder className={className} aria-labelledby={id}>
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
    <ul className={styles.list}>
      {items.map((item) => (
        <li key={item.adr.id} className={styles.row}>
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
  return visible ? <Text size="xs" c="dimmed" className={styles.more}>{t(`myWork.more.${kind}`)}</Text> : null;
}

function WorkContent({ work, role }: { work: AdrMyWork; role?: AdrRole }) {
  const { t } = useTranslation();
  const listPath = adrListPath({ mine: true, participantRole: role ?? null });
  return (
    <>
      <div className={styles.primaryGrid}>
        <Section title={t("myWork.actions.title")} icon={IconClipboardCheck} className={styles.attention}>
          {work.actions.items.length === 0 ? <EmptyState compact icon={IconClipboardCheck} label={t("myWork.actions.empty")} /> : <AttentionList items={work.actions.items} generatedAt={work.generatedAt} watching={false} />}
          <MoreNotice visible={work.actions.hasMore} kind="attention" />
          <div className={styles.subsection}>
            <Title order={4} size="h5">{t("myWork.watching.title")}</Title>
          </div>
          {work.watching.items.length === 0 ? <EmptyState compact icon={IconAlertTriangle} label={t("myWork.watching.empty")} /> : <AttentionList items={work.watching.items} generatedAt={work.generatedAt} watching />}
          <MoreNotice visible={work.watching.hasMore} kind="attention" />
        </Section>
        <Section title={t("myWork.activity.title")} detail={t("myWork.activity.period")} icon={IconActivity} className={styles.activity}>
          {work.activity.items.length === 0 ? <EmptyState compact icon={IconActivity} label={t("myWork.activity.empty")} /> : (
            <ol className={styles.list}>
              {work.activity.items.map(({ adr, event }) => (
                <li key={event.id} className={styles.activityRow}>
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
      <Section title={t("myWork.adrs.title")} detail={t("myWork.adrs.order")} icon={IconBriefcase} className={styles.myAdrs}>
        {work.adrs.items.length === 0 ? <EmptyState compact icon={IconBriefcase} label={t("myWork.adrs.empty")} /> : (
          <ul className={styles.list}>
            {work.adrs.items.map((adr) => (
              <li key={adr.id} className={styles.row}>
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

/** `/my-work`: the signed-in user's role-aware action queue, activity feed and current ADRs. */
export default function MyWork() {
  const { t } = useTranslation();
  const [search, setSearch] = useSearchParams();
  const rawRole = search.get("participantRole");
  const role = isAdrRole(rawRole) ? rawRole : undefined;
  const query = useQuery({ queryKey: ["adrs", "my-work", role ?? "all"], queryFn: () => getAdrMyWork(role) });
  const roleOptions = [
    { value: "", label: t("myWork.role.all") },
    ...ADR_ROLES.map((value) => ({ value, label: t(`adrs.role.${value}`) })),
  ];
  return (
    <Stack gap="lg" className={styles.page}>
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
      {query.data && <WorkContent work={query.data} role={role} />}
      <VisuallyHidden aria-live="polite">{role ? t(`adrs.role.${role}`) : t("myWork.role.all")}</VisuallyHidden>
    </Stack>
  );
}
