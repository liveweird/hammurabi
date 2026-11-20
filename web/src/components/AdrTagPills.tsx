import { Badge, Group, Text, VisuallyHidden } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { AdrTag } from "../api/adrs";

/**
 * An ADR's tags as light neutral pills (gray: a tag is a label, not a state, so it never takes a status colour - and never blue, indigo
 * or violet, which sit beside the brand). A retired tag - the registry dropped it - is an outlined, struck-through pill whose accessible
 * name says so, so colour and style are never the only carrier of the difference. `none` is what an empty set reads as (dimmed text);
 * omit it for a table cell that wants nothing.
 */
export default function AdrTagPills({ tags, none }: { tags: readonly AdrTag[]; none?: string }) {
  const { t } = useTranslation();
  if (tags.length === 0) {
    return none ? (
      <Text size="sm" c="dimmed">
        {none}
      </Text>
    ) : null;
  }
  return (
    <Group gap={4}>
      {tags.map((tag) => (
        <Badge
          key={tag.name}
          size="sm"
          color="gray"
          variant={tag.retired ? "outline" : "light"}
          style={{ textTransform: "none", textDecoration: tag.retired ? "line-through" : undefined }}
        >
          <span aria-hidden={tag.retired || undefined}>{tag.name}</span>
          {tag.retired && <VisuallyHidden>{t("adrs.tags.retiredAria", { name: tag.name })}</VisuallyHidden>}
        </Badge>
      ))}
    </Group>
  );
}
