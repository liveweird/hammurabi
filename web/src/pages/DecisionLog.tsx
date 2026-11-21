import { Anchor, Badge, Stack, Text, VisuallyHidden } from "@mantine/core";
import { IconArrowRight, IconCalendarDue, IconCalendarOff, IconCircleCheck, IconCircleOff, IconHourglass, IconHourglassOff } from "@tabler/icons-react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { getAdrOverview, type AdrOverview } from "../api/adrs";
import AdrLogRow from "../components/AdrLogRow";
import DecisionLogCard, { LogList } from "../components/DecisionLogCard";
import DecisionLogCounts from "../components/DecisionLogCounts";
import DecisionLogInForce from "../components/DecisionLogInForce";
import styles from "../components/DecisionLog.module.css";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import { adrListPath } from "../utils/adrListParams";
import { adrValidityPath } from "../utils/adrLinks";
import { browserTimeZone, formatDate } from "../utils/formatDate";
import { addDays, epochMillisToIsoDate } from "../utils/isoDate";

function Dim({ children }: { children: string }) {
  return (
    <Text size="xs" c="dimmed">
      {children}
    </Text>
  );
}

function MoreLink({ more, to, label }: { more: number; to: string; label: string }) {
  const { t } = useTranslation();
  if (more <= 0) return null;
  return (
    <Anchor component={RouterLink} to={to} size="sm" className={styles.moreLink}>
      {t("decisionLog.more", { count: more })}
      <VisuallyHidden>: {label}</VisuallyHidden>
    </Anchor>
  );
}

/** "Awaiting decision": ADRs in review, the longest wait first; the caller's own (they are the Approver) are tinted and badged, and counted above the list. */
function Awaiting({ awaiting }: { awaiting: AdrOverview["awaitingDecision"] }) {
  const { t } = useTranslation();
  return (
    <DecisionLogCard icon={IconHourglass} title={t("decisionLog.awaiting.title")} hint={t("decisionLog.awaiting.hint")} className={styles.lane}>
      {awaiting.mineTotal > 0 && (
        <Anchor component={RouterLink} to={adrListPath({ status: ["IN_REVIEW"], mine: true, participantRole: "APPROVER" })} size="sm" fw={600} className={styles.reviewLink}>
          <span>{t("decisionLog.awaiting.mineLink", { count: awaiting.mineTotal })}</span>
          <IconArrowRight size={16} stroke={1.7} aria-hidden />
        </Anchor>
      )}
      {awaiting.items.length === 0 ? (
        <EmptyState compact icon={IconHourglassOff} label={t("decisionLog.awaiting.empty")} />
      ) : (
        <LogList label={t("decisionLog.awaiting.title")}>
          {awaiting.items.map((item) => (
            <AdrLogRow
              key={item.id}
              item={item}
              highlight={item.mine}
              note={
                <>
                  {item.mine && (
                    <Badge color="grape" variant="light" size="sm">
                      {t("decisionLog.awaiting.mine")}
                    </Badge>
                  )}
                  <Dim>{t("decisionLog.awaiting.since", { date: formatDate(item.statusChangedAt, "—", browserTimeZone()) })}</Dim>
                  <Dim>{item.approver ? t("decisionLog.awaiting.approver", { name: item.approver.name }) : t("decisionLog.awaiting.noApprover")}</Dim>
                </>
              }
            />
          ))}
        </LogList>
      )}
      <MoreLink more={awaiting.more} to={adrListPath({ status: ["IN_REVIEW"] })} label={t("decisionLog.awaiting.all")} />
    </DecisionLogCard>
  );
}

/** "Expiring soon": the decisions in force whose last day is within the server's horizon, overdue ones (the sweep has not acted yet) first and red. */
function Expiring({ expiring, generatedAt }: { expiring: AdrOverview["expiringSoon"]; generatedAt: number }) {
  const { t } = useTranslation();
  // "Today" is the server's (the UTC day it generated the answer on, the day its horizon counts from), not this browser's clock: it stays a UTC calendar
  // date on purpose, because the server judges expiry in UTC and `expiresOn` is a calendar day, even though timestamps elsewhere render in the browser's zone.
  const today = epochMillisToIsoDate(generatedAt);
  // "Strictly before" the day after the horizon, so the list link shows exactly the span the section covers.
  const beyond = addDays(today, expiring.days + 1);
  return (
    <DecisionLogCard icon={IconCalendarDue} title={t("decisionLog.expiring.title")} hint={t("decisionLog.expiring.hint", { count: expiring.days })} className={styles.lane}>
      {expiring.items.length === 0 ? (
        <EmptyState compact icon={IconCalendarOff} label={t("decisionLog.expiring.empty")} />
      ) : (
        <LogList label={t("decisionLog.expiring.title")}>
          {expiring.items.map((item) => (
            <AdrLogRow
              key={item.id}
              item={item}
              to={adrValidityPath(item.id)}
              note={
                <>
                  {item.expiresOn !== null && item.expiresOn < today && (
                    <Badge color="red" variant="light" size="sm">
                      {t("decisionLog.expiring.overdue")}
                    </Badge>
                  )}
                  {item.expiresOn !== null && <Dim>{t("decisionLog.expiring.lastDay", { date: item.expiresOn })}</Dim>}
                </>
              }
            />
          ))}
        </LogList>
      )}
      <MoreLink more={expiring.more} to={adrListPath({ status: ["ACTIVE"], expiresBefore: beyond })} label={t("decisionLog.expiring.all")} />
    </DecisionLogCard>
  );
}

/** "Recently decided": the ADRs that became active most recently (the server dates them by their activation). */
function Recent({ recent }: { recent: AdrOverview["recentlyDecided"] }) {
  const { t } = useTranslation();
  return (
    <DecisionLogCard icon={IconCircleCheck} title={t("decisionLog.recent.title")} hint={t("decisionLog.recent.hint")} className={styles.lane}>
      {recent.items.length === 0 ? (
        <EmptyState compact icon={IconCircleOff} label={t("decisionLog.recent.empty")} />
      ) : (
        <LogList label={t("decisionLog.recent.title")}>
          {recent.items.map((item) => (
            <AdrLogRow key={item.id} item={item} note={<Dim>{t("decisionLog.recent.on", { date: formatDate(item.statusChangedAt, "—", browserTimeZone()) })}</Dim>} />
          ))}
        </LogList>
      )}
      <MoreLink more={recent.more} to={adrListPath({ status: ["ACTIVE"] })} label={t("decisionLog.recent.all")} />
    </DecisionLogCard>
  );
}

/**
 * The decision log (`/decision-log`, 0.9.0): a read-only overview of the whole log in one read - the count of ADRs per status, what waits for
 * a decision (the caller's own highlighted), what expires soon, what was decided lately, and what is in force by tag. Every row opens its
 * ADR and every "+N more" opens the ADR list filtered to what the section covers; nothing here pages (the server caps every section).
 */
export default function DecisionLog() {
  const { t } = useTranslation();
  const { data, isLoading, isError, error } = useQuery({ queryKey: ["adrs", "overview"], queryFn: getAdrOverview });
  return (
    <Stack gap="lg" className={styles.page}>
      <PageHeader title={t("decisionLog.title")} description={t("decisionLog.intro")} />
      {isError && <ErrorAlert error={error} title={t("decisionLog.loadFailed")} />}
      {isLoading && <LoadingBlock />}
      {data && (
        <>
          <DecisionLogCounts counts={data.counts} />
          <div className={styles.activity}>
            <Awaiting awaiting={data.awaitingDecision} />
            <Expiring expiring={data.expiringSoon} generatedAt={data.generatedAt} />
            <Recent recent={data.recentlyDecided} />
          </div>
          <DecisionLogInForce inForce={data.inForce} />
        </>
      )}
    </Stack>
  );
}
