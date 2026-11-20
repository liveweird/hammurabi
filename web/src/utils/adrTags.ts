import type { ComboboxItem, ComboboxItemGroup } from "@mantine/core";
import type { AdrTag } from "../api/adrs";
import type { TagCategory } from "../api/tagCategories";

/** The most tags one ADR carries (the server's `MAX_ADR_TAGS`). */
export const MAX_ADR_TAGS = 20;

/** The most `tag` filters one list request names (the server's `MAX_TAG_FILTERS`; more is a 400). */
export const MAX_TAG_FILTERS = 20;

/** The tag picker's options: one group per registry category, its live tags inside (the value and the label are the tag name). */
export function tagOptionGroups(categories: readonly TagCategory[]): ComboboxItemGroup<ComboboxItem>[] {
  return categories
    .filter((category) => category.tags.length > 0)
    .map((category) => ({ group: category.name, items: category.tags.map((tag) => ({ value: tag, label: tag })) }));
}

/** The names the editor starts from: a retired tag is not in the registry, so a save that kept it would be refused (400) - it is dropped. */
export function liveTagNames(tags: readonly AdrTag[]): string[] {
  return tags.filter((tag) => !tag.retired).map((tag) => tag.name);
}

export function hasRetiredTag(tags: readonly AdrTag[]): boolean {
  return tags.some((tag) => tag.retired);
}
