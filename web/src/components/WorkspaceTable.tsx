import type { ReactNode } from "react";
import { Table } from "@mantine/core";
import ScrollRegion from "./ScrollRegion";
import styles from "./WorkspaceTable.module.css";

/** A native data table with a dedicated scrolling area when the columns need more space. */
export default function WorkspaceTable({ label, minWidth = 480, children }: {
  label: string;
  minWidth?: number | string;
  children: ReactNode;
}) {
  return (
    <div className={styles.viewport}>
      <ScrollRegion label={label} minWidth={minWidth}>
        <Table aria-label={label} className={styles.table}>{children}</Table>
      </ScrollRegion>
    </div>
  );
}
