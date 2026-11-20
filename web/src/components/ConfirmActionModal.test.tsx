import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import ConfirmActionModal from "./ConfirmActionModal";
import { renderWithProviders, screen } from "../test/render";

function renderModal(props: { loading?: boolean; onClose?: () => void; onConfirm?: () => void }) {
  return renderWithProviders(
    <ConfirmActionModal
      opened
      onClose={props.onClose ?? (() => {})}
      title="Apply?"
      message="Apply to all matching."
      cancelLabel="Not now"
      confirmLabel="Apply"
      onConfirm={props.onConfirm ?? (() => {})}
      loading={props.loading}
    />,
  );
}

describe("ConfirmActionModal", () => {
  test("confirm and cancel reach their handlers", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const onConfirm = vi.fn();
    renderModal({ onClose, onConfirm });
    await user.click(screen.getByRole("button", { name: "Apply" }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test("the close X does nothing while the action runs", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderModal({ onClose, loading: true });
    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(onClose).not.toHaveBeenCalled();
  });
});
