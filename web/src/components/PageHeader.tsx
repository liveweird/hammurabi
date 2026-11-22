import { type ReactNode } from "react";
import { Anchor, Group, Stack, Text, Title } from "@mantine/core";
import { IconArrowLeft } from "@tabler/icons-react";
import { Link as RouterLink } from "react-router-dom";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import classes from "../theme.module.css";
import styles from "./PageHeader.module.css";

/**
 * Every page's first row (v1.19.0): the title (an `order={2}` heading — the name tests and
 * the e2e accessibility scan locate pages by), an optional dimmed description, an optional
 * back link above the title, the page's primary action(s) right-aligned on the SAME row,
 * and an optional toolbar row underneath (the list pages' filter/lens/kind row). One
 * component, so a "New …" button can only ever be in one place. It also titles the browser tab ("{title} · Hammurabi", or
 * `documentTitle` when the tab needs more than the heading says - an ADR's key): every page with a header has a tab title, with no per-page call to forget.
 */
export default function PageHeader({
  title,
  documentTitle,
  description,
  backTo,
  actions,
  toolbar,
}: {
  title: string;
  documentTitle?: string;
  description?: ReactNode;
  backTo?: { to: string; label: string };
  actions?: ReactNode;
  toolbar?: ReactNode;
}) {
  useDocumentTitle(documentTitle ?? title);
  return (
    <Stack component="header" gap="sm" className={styles.header}>
      <Group justify="space-between" align="center" wrap="wrap" gap="sm">
        <Stack gap={4} className={styles.heading}>
          {backTo && (
            <Anchor component={RouterLink} to={backTo.to} size="xs" c="dimmed">
              <Group gap={4} wrap="nowrap" component="span">
                <IconArrowLeft size={12} aria-hidden />
                {backTo.label}
              </Group>
            </Anchor>
          )}
          <Title order={2} className={`${classes.breakAnywhere} ${styles.title}`}>{title}</Title>
          {description && (
            <Text c="dimmed" size="sm" className={`${classes.breakAnywhere} ${styles.description}`}>
              {description}
            </Text>
          )}
        </Stack>
        {actions && (
          <Group gap="xs" wrap="wrap" className={styles.actions}>
            {actions}
          </Group>
        )}
      </Group>
      {toolbar}
    </Stack>
  );
}
