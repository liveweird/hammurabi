import { useState } from "react";
import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import DiscussionResolveModal from "./DiscussionResolveModal";

/** The caller side: the modal is mounted only while it is open, exactly as `AdrDiscussion` does. */
function Host({ onClose = vi.fn(), onResolve = vi.fn() }: { onClose?: () => void; onResolve?: (note: string) => void }) {
  const [open, setOpen] = useState(true);
  return open ? (
    <DiscussionResolveModal
      loading={false}
      onClose={() => {
        onClose();
        setOpen(false);
      }}
      onResolve={onResolve}
    />
  ) : (
    <p>closed</p>
  );
}

const resolveDialog = () => screen.findByRole("dialog", { name: "Resolve this entry" });
const confirmDialog = () => screen.findByRole("dialog", { name: "Discard unsaved changes?" });

describe("DiscussionResolveModal discarding a note", () => {
  test("cancelling with nothing typed closes at once, without asking", async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Host onClose={onClose} />);
    await user.click(within(await resolveDialog()).getByRole("button", { name: "Cancel" }));
    expect(onClose).toHaveBeenCalledOnce();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByText("closed")).toBeInTheDocument();
  });

  test("cancelling a typed note opens the confirmation over the resolve dialog, and it can be reached and focused", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host />);
    await user.type(within(await resolveDialog()).getByRole("textbox", { name: /Resolution note/ }), "Settled in the meeting");
    await user.click(within(await resolveDialog()).getByRole("button", { name: "Cancel" }));

    const confirm = await confirmDialog();
    // Both dialogs are open: the question sits above the form it is about.
    expect(await resolveDialog()).toBeInTheDocument();
    expect(within(confirm).getByText("Your unsaved changes will be lost.")).toBeInTheDocument();
    const keep = within(confirm).getByRole("button", { name: "Keep editing" });
    const discard = within(confirm).getByRole("button", { name: "Discard changes" });
    // Keyboard focus moves into the question (the topmost dialog), not behind it in the resolve form.
    await waitFor(() => expect(confirm).toContainElement(document.activeElement as HTMLElement));
    discard.focus();
    expect(discard).toHaveFocus();
    keep.focus();
    expect(keep).toHaveFocus();
  });

  test("Discard changes closes both dialogs and reports the close once", async () => {
    const onClose = vi.fn();
    const onResolve = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Host onClose={onClose} onResolve={onResolve} />);
    await user.type(within(await resolveDialog()).getByRole("textbox", { name: /Resolution note/ }), "Settled in the meeting");
    await user.click(within(await resolveDialog()).getByRole("button", { name: "Cancel" }));
    await user.click(within(await confirmDialog()).getByRole("button", { name: "Discard changes" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByText("closed")).toBeInTheDocument();
    expect(onClose).toHaveBeenCalledOnce();
    expect(onResolve).not.toHaveBeenCalled();
  });

  test("Keep editing returns to the resolve dialog with the note intact, and it still resolves with that note", async () => {
    const onClose = vi.fn();
    const onResolve = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Host onClose={onClose} onResolve={onResolve} />);
    await user.type(within(await resolveDialog()).getByRole("textbox", { name: /Resolution note/ }), "Settled in the meeting");
    await user.click(within(await resolveDialog()).getByRole("button", { name: "Cancel" }));
    await user.click(within(await confirmDialog()).getByRole("button", { name: "Keep editing" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Discard unsaved changes?" })).not.toBeInTheDocument());
    expect(onClose).not.toHaveBeenCalled();
    expect(within(await resolveDialog()).getByRole("textbox", { name: /Resolution note/ })).toHaveValue("Settled in the meeting");
    await user.click(within(await resolveDialog()).getByRole("button", { name: "Resolve" }));
    expect(onResolve).toHaveBeenCalledExactlyOnceWith("Settled in the meeting");
  });

  test("Escape on the resolve dialog asks the same question; Escape on the question keeps the note", async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Host onClose={onClose} />);
    const note = within(await resolveDialog()).getByRole("textbox", { name: /Resolution note/ });
    await user.type(note, "Settled in the meeting");
    await user.keyboard("{Escape}");
    await confirmDialog();
    expect(onClose).not.toHaveBeenCalled();
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Discard unsaved changes?" })).not.toBeInTheDocument());
    expect(onClose).not.toHaveBeenCalled();
    expect(within(await resolveDialog()).getByRole("textbox", { name: /Resolution note/ })).toHaveValue("Settled in the meeting");
  });
});
