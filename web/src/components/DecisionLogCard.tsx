import { useId, type ReactNode } from "react";
import { Text, Title } from "@mantine/core";
import type { Icon } from "@tabler/icons-react";
import styles from "./DecisionLog.module.css";

type Props = { icon: Icon; title: string; hint: string; className: string; detail?: string; children: ReactNode };

export default function DecisionLogCard({ icon: CardIcon, title, hint, className, detail, children }: Props) {
  const headingId = useId();
  return (
    <section className={className} aria-labelledby={headingId}>
      <header className={styles.sectionHeader}>
        <div className={styles.sectionIcon}><CardIcon size={18} stroke={1.7} aria-hidden /></div>
        <div className={styles.sectionHeading}>
          <Title order={3} size="h5" id={headingId}>
            {title}
          </Title>
          <Text size="xs" c="dimmed">{hint}</Text>
        </div>
        {detail && <Text className={styles.sectionDetail} size="sm" fw={600}>{detail}</Text>}
      </header>
      {children}
    </section>
  );
}

/** A decision-log list: a real `<ul>` of `AdrLogRow`s with no bullets (the rows are the structure), `label` naming it for assistive technology. */
export function LogList({ label, children }: { label: string; children: ReactNode }) {
  return (
    <ul className={styles.logList} role="list" aria-label={label}>
      {children}
    </ul>
  );
}
