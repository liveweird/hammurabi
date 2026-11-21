import { Anchor, Badge, Group, SimpleGrid, Stack, Text, Title, VisuallyHidden } from "@mantine/core";
import { IconTags } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import type { AdrOverview } from "../api/adrs";
import { adrListPath } from "../utils/adrListParams";
import AdrLogRow from "./AdrLogRow";
import DecisionLogCard, { LogList } from "./DecisionLogCard";

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
    <Stack gap={4}>
      <LogList label={label}>
        {items.map((item) => (
          <AdrLogRow key={item.id} item={item} />
        ))}
      </LogList>
      {more > 0 && (
        <Anchor component={RouterLink} to={to} size="sm">
          {moreText ?? t("decisionLog.more", { count: more })}
          {moreOf && <VisuallyHidden>: {moreOf}</VisuallyHidden>}
        </Anchor>
      )}
    </Stack>
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
    <DecisionLogCard icon={IconTags} title={t("decisionLog.inForce.title")} hint={t("decisionLog.inForce.hint")}>
      <Text size="sm" fw={500}>
        {t("decisionLog.inForce.total", { count: inForce.total })}
      </Text>
      {inForce.total === 0 && (
        <Text size="sm" c="dimmed">
          {t("decisionLog.inForce.empty")}
        </Text>
      )}
      {inForce.categories.map((category) => (
        <Stack key={category.id} gap="sm">
          <Title order={4} size="h5">
            {category.name}
          </Title>
          <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
            {category.tags.map((tag) => (
              <Stack key={tag.name} gap={4}>
                <Group gap="xs">
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
              </Stack>
            ))}
          </SimpleGrid>
        </Stack>
      ))}
      {inForce.untagged.total > 0 && (
        <Stack gap="sm">
          <Title order={4} size="h5">
            {t("decisionLog.inForce.untagged")}
          </Title>
          <Text size="sm" c="dimmed">
            {t("decisionLog.inForce.untaggedHint")}
          </Text>
          <GroupList
            label={t("decisionLog.inForce.untagged")}
            items={inForce.untagged.items}
            more={inForce.untagged.more}
            moreText={t("decisionLog.inForce.all")}
            to={activeList}
          />
        </Stack>
      )}
      {inForce.hiddenTags > 0 && (
        <Text size="sm" c="dimmed">
          {t("decisionLog.inForce.hiddenTags", { count: inForce.hiddenTags })}
        </Text>
      )}
      {inForce.total > 0 && inForce.untagged.more === 0 && (
        <Anchor component={RouterLink} to={activeList} size="sm" fw={500}>
          {t("decisionLog.inForce.all")}
        </Anchor>
      )}
    </DecisionLogCard>
  );
}
