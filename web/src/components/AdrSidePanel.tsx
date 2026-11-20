import { useId, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Stack, Title } from "@mantine/core";
import type { AdrResponse } from "../api/adrs";
import AdrDetailsSection from "./AdrDetailsSection";
import AdrHistory from "./AdrHistory";
import AdrParticipantsPanel from "./AdrParticipantsPanel";
import AdrStatusControl from "./AdrStatusControl";

/** One panel section: its h3 (the page title is the h2) and its body. */
function Section({ title, children }: { title: string; children: ReactNode }) {
  const id = useId();
  return (
    <Stack gap="xs" component="section" aria-labelledby={id}>
      <Title order={3} size="h4" id={id}>
        {title}
      </Title>
      {children}
    </Stack>
  );
}

/** The ADR's side panel (Adder's `ThreadSidePanel` shape): Status, DACI, Details and History, each an h3 under the page's h2. */
export default function AdrSidePanel({ adr }: { adr: AdrResponse }) {
  const { t } = useTranslation();
  return (
    <Stack gap="lg">
      <Section title={t("adrs.side.status")}>
        <AdrStatusControl adr={adr} />
      </Section>
      <Section title={t("adrs.side.daci")}>
        <AdrParticipantsPanel adr={adr} />
      </Section>
      <Section title={t("adrs.side.details")}>
        <AdrDetailsSection adr={adr} />
      </Section>
      <Section title={t("adrs.side.history")}>
        <AdrHistory adrId={adr.id} />
      </Section>
    </Stack>
  );
}
