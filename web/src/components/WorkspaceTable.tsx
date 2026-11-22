import type { ReactNode } from "react";
import { Table } from "@mantine/core";
import classes from "../theme.module.css";
import ScrollRegion from "./ScrollRegion";

/** A native data table with a dedicated scrolling area when the columns need more space. The frame is the theme's (`Table.extend`). */
export default function WorkspaceTable({ label, minWidth = 480, children }: {
  label: string;
  minWidth?: number | string;
  children: ReactNode;
}) {
  return (
    <div className={classes.tableViewport}>
      <ScrollRegion label={label} minWidth={minWidth}>
        <Table aria-label={label}>{children}</Table>
      </ScrollRegion>
    </div>
  );
}
