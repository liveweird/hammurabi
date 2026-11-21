import { useId, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Stack, Title } from "@mantine/core";
import type { AdrResponse } from "../api/adrs";
import { ADR_VALIDITY_ID } from "../utils/adrLinks";
import AdrDetailsSection from "./AdrDetailsSection";
import AdrHistory from "./AdrHistory";
import AdrParticipantsPanel from "./AdrParticipantsPanel";
import AdrStatusControl from "./AdrStatusControl";
import AdrSuccessorPanel from "./AdrSuccessorPanel";
import AdrTagsPanel from "./AdrTagsPanel";
import AdrValidity from "./AdrValidity";

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

/** The ADR's side panel (Adder's `ThreadSidePanel` shape), with each section an h3 under the page h2. */
export default function AdrSidePanel({ adr }: { adr: AdrResponse }) {
  const { t } = useTranslation();
  const validityHeading = useId();
  return (
    <Stack gap="lg">
      <Section title={t("adrs.side.status")}>
        <AdrStatusControl adr={adr} />
      </Section>
      <Stack
        gap="xs"
        component="section"
        id={ADR_VALIDITY_ID}
        tabIndex={-1}
        aria-labelledby={validityHeading}
        style={{ scrollMarginBlockStart: "calc(var(--app-shell-header-height, 3rem) + 1rem)" }}
      >
        <Title order={3} size="h4" id={validityHeading} tabIndex={-1}>{t("adrs.validity.title")}</Title>
        <AdrValidity adr={adr} headingId={validityHeading} />
      </Stack>
      <Section title={t("adrs.side.daci")}>
        <AdrParticipantsPanel adr={adr} />
      </Section>
      <Section title={t("adrs.side.tags")}>
        <AdrTagsPanel adr={adr} />
      </Section>
      <Section title={t("adrs.side.successor")}>
        <AdrSuccessorPanel adr={adr} />
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
