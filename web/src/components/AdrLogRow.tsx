import type { ReactNode } from "react";
import { Anchor } from "@mantine/core";
import { IconChevronRight } from "@tabler/icons-react";
import { Link as RouterLink } from "react-router-dom";
import type { AdrLogItem } from "../api/adrs";
import { adrPath } from "../utils/adrLinks";
import styles from "./DecisionLog.module.css";

/** The reference and name share a link. The section's metadata sits below the name. */
export default function AdrLogRow({ item, note, highlight, to = adrPath(item.id) }: { item: Pick<AdrLogItem, "id" | "key" | "name">; note?: ReactNode; highlight?: boolean; to?: string }) {
  return (
    <li className={styles.logRow} data-highlight={highlight || undefined}>
      <Anchor component={RouterLink} to={to} underline="never" className={styles.rowLink}>
        <span className={styles.rowKey}>{item.key}</span>
        <span className={styles.rowTitle}>{item.name}</span>
        <IconChevronRight className={styles.rowChevron} size={15} stroke={1.7} aria-hidden />
      </Anchor>
      {note && <div className={styles.rowMeta}>{note}</div>}
    </li>
  );
}
