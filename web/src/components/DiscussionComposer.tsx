import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, SegmentedControl, Stack, Text } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { postDiscussionEntry, type DiscussionKind } from "../api/adrDiscussion";
import { DISCUSSION_BODY_MAX } from "../utils/adrDiscussion";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import AdrWriteError from "./AdrWriteError";
import MarkdownField from "./MarkdownField";

/**
 * The composer of a discussion section: a kind switch when the section takes two kinds (comment/question, issue/escalation), a Markdown
 * field with its Write/Preview toggle and one submit button named for the kind. Anyone signed in may post; the server judges the window
 * (a typed 409 becomes the orange reload notice). A post refreshes the `["adrs"]` prefix (the thread, the counts, the history) and tells
 * the thread to jump to its last page.
 */
export default function DiscussionComposer({
  adrId,
  kinds,
  composeLabel,
  onPosted,
}: {
  adrId: number;
  kinds: readonly DiscussionKind[];
  composeLabel: string;
  onPosted: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [kind, setKind] = useState<DiscussionKind>(kinds[0]);
  const [body, setBody] = useState("");
  const post = useMutation({
    mutationFn: () => postDiscussionEntry(adrId, kind, body.trim()),
    onSuccess: async () => {
      setBody("");
      await refreshQueriesAfterMutation(queryClient, ["adrs"]);
      showSuccessToast(t("adrs.discussion.toast.posted"));
      onPosted();
    },
  });
  const tooLong = body.length > DISCUSSION_BODY_MAX;

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        if (body.trim() !== "" && !tooLong) post.mutate();
      }}
      noValidate
    >
      <Stack gap="xs">
        {kinds.length > 1 && (
          <SegmentedControl
            size="xs"
            value={kind}
            onChange={(next) => setKind(next as DiscussionKind)}
            aria-label={t("adrs.discussion.kindAria")}
            data={kinds.map((value) => ({ value, label: t(`adrs.discussion.kind.${value}`) }))}
          />
        )}
        <Text size="xs" c="dimmed">
          {t(`adrs.discussion.kindHint.${kind}`)}
        </Text>
        <MarkdownField
          label={composeLabel}
          value={body}
          onChange={setBody}
          maxLength={DISCUSSION_BODY_MAX}
          minRows={3}
          error={tooLong ? t("adrs.validation.textLength", { max: DISCUSSION_BODY_MAX }) : undefined}
        />
        {post.isError && <AdrWriteError error={post.error} kind="discussion" onReloaded={post.reset} />}
        <Group justify="flex-end">
          <Button type="submit" loading={post.isPending} disabled={body.trim() === "" || tooLong}>
            {t(`adrs.discussion.submit.${kind}`)}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
