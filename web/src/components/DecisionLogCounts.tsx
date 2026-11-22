import { useId } from "react";
import { Anchor, Box, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import type { AdrOverview } from "../api/adrs";
import { adrListPath } from "../utils/adrListParams";
import { ADR_STATUS_COLOR } from "../utils/adrStatus";
import classes from "../theme.module.css";
import styles from "./DecisionLog.module.css";

/** The count strip uses the same status colours and names as the ADR list. */
export default function DecisionLogCounts({ counts }: { counts: AdrOverview["counts"] }) {
  const { t } = useTranslation();
  const headingId = useId();
  return (
    <section className={classes.card} aria-labelledby={headingId}>
      <Title order={3} size="h5" id={headingId} className={styles.statusHeading}>
        {t("decisionLog.counts.title")}
      </Title>
      <ul className={`${classes.plainList} ${styles.statusGrid}`} role="list">
        {counts.map(({ status, count }) => (
          <li key={status}>
            <Anchor component={RouterLink} to={adrListPath({ status: [status] })} underline="never" className={styles.statusLink}>
              <span className={styles.statusLabel}>
                {/* The dot wears the status's hue through DecisionLog.module.css's `[data-hue]` rules (theme tokens, both schemes). */}
                <Box component="span" className={styles.statusDot} data-hue={ADR_STATUS_COLOR[status]} aria-hidden />
                {t(`adrs.status.${status}`)}
              </span>
              <span className={styles.statusCount}>{count}</span>
            </Anchor>
          </li>
        ))}
      </ul>
    </section>
  );
}
