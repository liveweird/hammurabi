import { Anchor, Badge, Group, Text, Title, VisuallyHidden } from "@mantine/core";
import { IconTags, IconTagsOff } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import type { AdrOverview } from "../api/adrs";
import { adrListPath } from "../utils/adrListParams";
import AdrLogRow from "./AdrLogRow";
import DecisionLogCard, { LogList } from "./DecisionLogCard";
import classes from "../theme.module.css";
import styles from "./DecisionLog.module.css";
import EmptyState from "./EmptyState";

type InForce = AdrOverview["inForce"];
type Items = InForce["untagged"]["items"];

/**
 * One group's list and its "more" link (the list's own filters, not a page). The link's visible text is "+N more" and a visually hidden
 * suffix says more of what, so the accessible name CONTAINS the visible text (WCAG 2.5.3); a group whose target is not its own list passes
 * `moreText` instead and says so in the visible words.
 */
function GroupList({ label, items, more, moreOf, moreText, to }: { label: string; items: Items; more: number; moreOf?: string; moreText?: string; to: string }) {
  const { t } = useTranslation();
  return (
    <>
      <LogList label={label}>
        {items.map((item) => (
          <AdrLogRow key={item.id} item={item} />
        ))}
      </LogList>
      {more > 0 && (
        <Anchor component={RouterLink} to={to} size="sm" className={styles.moreLink}>
          {moreText ?? t("decisionLog.more", { count: more })}
          {moreOf && <VisuallyHidden>: {moreOf}</VisuallyHidden>}
        </Anchor>
      )}
    </>
  );
}

/**
 * "In force by tag" (the whole width): the ACTIVE ADRs by tag category (h4) and tag (h5), each tag's newest few as links with a "+N more" link
 * to the ADR list filtered to that tag, then the untagged ones (the list has no untagged filter, so that group's link opens all the active ADRs). An ADR sits under every tag it carries, so the groups overlap by design.
 */
export default function DecisionLogInForce({ inForce }: { inForce: InForce }) {
  const { t } = useTranslation();
  const activeList = adrListPath({ status: ["ACTIVE"] });
  return (
    <DecisionLogCard
      icon={IconTags}
      title={t("decisionLog.inForce.title")}
      hint={t("decisionLog.inForce.hint")}
      className={`${classes.card} ${styles.catalog}`}
      detail={t("decisionLog.inForce.total", { count: inForce.total })}
    >
      {inForce.total === 0 && (
        <EmptyState compact icon={IconTagsOff} label={t("decisionLog.inForce.empty")} />
      )}
      {inForce.categories.map((category) => (
        <div key={category.id} className={styles.category}>
          <Title order={4} size="h6" className={`${classes.breakAnywhere} ${styles.categoryHeading}`}>
            {category.name}
          </Title>
          <div className={styles.tagGrid}>
            {category.tags.map((tag) => (
              <div key={tag.name} className={styles.tagGroup}>
                <Group gap={8} className={`${classes.breakAnywhere} ${styles.tagHeading}`}>
                  <Title order={5} size="sm">
                    {tag.name}
                  </Title>
                  <Badge color="gray" variant="light" size="sm">
                    {tag.total}
                  </Badge>
                </Group>
                <GroupList
                  label={`${category.name}: ${tag.name}`}
                  items={tag.items}
                  more={tag.more}
                  moreOf={t("decisionLog.inForce.moreFor", { tag: tag.name })}
                  to={adrListPath({ status: ["ACTIVE"], tag: [tag.name] })}
                />
              </div>
            ))}
          </div>
        </div>
      ))}
      {inForce.untagged.total > 0 && (
        <div className={styles.category}>
          <Title order={4} size="h6" className={`${classes.breakAnywhere} ${styles.categoryHeading}`}>
            {t("decisionLog.inForce.untagged")}
          </Title>
          <div className={styles.tagGroup}>
            <GroupList
              label={t("decisionLog.inForce.untagged")}
              items={inForce.untagged.items}
              more={inForce.untagged.more}
              moreText={t("decisionLog.inForce.all")}
              to={activeList}
            />
          </div>
        </div>
      )}
      {inForce.hiddenTags > 0 && (
        <Text size="sm" c="dimmed" className={styles.catalogFootnote}>
          {t("decisionLog.inForce.hiddenTags", { count: inForce.hiddenTags })}
        </Text>
      )}
      {inForce.total > 0 && inForce.untagged.more === 0 && (
        <Anchor component={RouterLink} to={activeList} size="sm" className={`${styles.moreLink} ${styles.catalogLink}`}>
          {t("decisionLog.inForce.all")}
        </Anchor>
      )}
    </DecisionLogCard>
  );
}
