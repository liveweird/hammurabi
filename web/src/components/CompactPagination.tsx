import { useTranslation } from "react-i18next";
import { Group, Pagination } from "@mantine/core";

/**
 * The pager of a sub-collection in a narrow column (the history in the side panel, a discussion thread, the notifications drawer): centred,
 * small, no edges and no page-size control, unlike the list footer `PaginationBar`. Renders nothing for one page. Shares the control names
 * with `PaginationBar` (`common.table.*Page`): the arrows are icon-only and need them (WCAG 4.1.2; the axe e2e spec pins it).
 */
export default function CompactPagination({
  page,
  totalPages,
  onChange,
  siblings,
}: {
  page: number;
  /** The number of pages, not of rows. */
  totalPages: number;
  onChange: (page: number) => void;
  /** Page buttons shown either side of the current one (Mantine's default 1; the side panel is narrow enough for 0). */
  siblings?: number;
}) {
  const { t } = useTranslation();
  if (totalPages <= 1) return null;
  return (
    <Group justify="center">
      <Pagination
        size="sm"
        value={page}
        onChange={onChange}
        total={totalPages}
        siblings={siblings}
        getControlProps={(control) => ({ "aria-label": t(`common.table.${control}Page`) })}
      />
    </Group>
  );
}
