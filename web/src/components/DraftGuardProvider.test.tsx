import { useContext, useState, type ReactElement } from "react";
import { afterEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { createMemoryRouter, MemoryRouter, RouterProvider } from "react-router-dom";
import { DraftGroupContext, DraftGuardContext, useDraftGuard } from "../hooks/useDraftGuard";
import { theme } from "../theme";
import DraftGuardProvider from "./DraftGuardProvider";

/** One form: Edit makes it dirty, Save releases it (what a successful save does before navigating), Cancel asks to discard. */
function Form({ name, discarded }: { name: string; discarded: (name: string) => void }) {
  const [dirty, setDirty] = useState(false);
  const { markSaved, requestDiscard } = useDraftGuard(dirty);
  return (
    <>
      <button onClick={() => setDirty(true)}>Edit {name}</button>
      <button onClick={markSaved}>Save {name}</button>
      <button onClick={() => requestDiscard(() => discarded(name))}>Cancel {name}</button>
    </>
  );
}

function unloadIsBlocked(): boolean {
  const event = new Event("beforeunload", { cancelable: true });
  window.dispatchEvent(event);
  return event.defaultPrevented;
}

function guarded(ui: ReactElement) {
  return (
    <MantineProvider env="test" theme={theme}>
      <DraftGuardProvider>{ui}</DraftGuardProvider>
    </MantineProvider>
  );
}

/** Without a data router (a plain `MemoryRouter`): no route blocker, only the local confirmation and the unload guard. */
function renderPlain(ui: ReactElement) {
  return render(<MemoryRouter>{guarded(ui)}</MemoryRouter>);
}

/** With a data router, as the app mounts it: the provider also blocks leaving the page. */
async function renderRouted(ui: ReactElement, route = "/start") {
  const router = createMemoryRouter([{ path: "*", element: guarded(ui) }], { initialEntries: [route] });
  render(<RouterProvider router={router} />);
  // The data router mounts its route a tick after render.
  await screen.findByRole("button", { name: /^Edit / });
  return router;
}

const dialog = () => screen.findByRole("dialog", { name: "Discard unsaved changes?" });

describe("DraftGuardProvider", () => {
  afterEach(() => localStorage.clear());

  test("an explicit cancel of a clean form discards at once, without asking", async () => {
    const discarded = vi.fn();
    const user = userEvent.setup();
    renderPlain(<Form name="a" discarded={discarded} />);
    await user.click(screen.getByRole("button", { name: "Cancel a" }));
    expect(discarded).toHaveBeenCalledExactlyOnceWith("a");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  test("an explicit cancel of a dirty form asks first: Keep editing discards nothing, Discard changes discards once", async () => {
    const discarded = vi.fn();
    const user = userEvent.setup();
    renderPlain(<Form name="a" discarded={discarded} />);
    await user.click(screen.getByRole("button", { name: "Edit a" }));
    await user.click(screen.getByRole("button", { name: "Cancel a" }));
    expect(within(await dialog()).getByText("Your unsaved changes will be lost.")).toBeInTheDocument();
    await user.click(within(await dialog()).getByRole("button", { name: "Keep editing" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(discarded).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Cancel a" }));
    await user.click(within(await dialog()).getByRole("button", { name: "Discard changes" }));
    expect(discarded).toHaveBeenCalledExactlyOnceWith("a");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("the browser's unload prompt is armed while any form is dirty and released when the last one is saved", async () => {
    const user = userEvent.setup();
    renderPlain(
      <>
        <Form name="a" discarded={vi.fn()} />
        <Form name="b" discarded={vi.fn()} />
      </>,
    );
    expect(unloadIsBlocked()).toBe(false);
    await user.click(screen.getByRole("button", { name: "Edit a" }));
    await user.click(screen.getByRole("button", { name: "Edit b" }));
    expect(unloadIsBlocked()).toBe(true);
    await user.click(screen.getByRole("button", { name: "Save a" }));
    expect(unloadIsBlocked()).toBe(true);
    await user.click(screen.getByRole("button", { name: "Save b" }));
    expect(unloadIsBlocked()).toBe(false);
  });

  test("a form that unmounts releases its registration", async () => {
    const user = userEvent.setup();
    function Host() {
      const [shown, setShown] = useState(true);
      return (
        <>
          <button onClick={() => setShown(false)}>Close</button>
          {shown && <Form name="a" discarded={vi.fn()} />}
        </>
      );
    }
    renderPlain(<Host />);
    await user.click(screen.getByRole("button", { name: "Edit a" }));
    expect(unloadIsBlocked()).toBe(true);
    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(unloadIsBlocked()).toBe(false);
  });

  test("hasDrafts answers for everything, or for one group of forms", async () => {
    const user = userEvent.setup();
    const groupA = Symbol("a");
    const groupB = Symbol("b");
    // The answer is read on demand (the registry is not render state), so the probe prints it when asked.
    function Probe() {
      const guard = useContext(DraftGuardContext);
      const [answer, setAnswer] = useState("");
      const ask = () => {
        if (guard) setAnswer([guard.hasDrafts(), guard.hasDrafts(groupA), guard.hasDrafts(groupB)].join(" "));
      };
      return (
        <>
          <button onClick={ask}>Ask</button>
          <output>{answer}</output>
        </>
      );
    }
    renderPlain(
      <>
        <Probe />
        <DraftGroupContext.Provider value={groupA}><Form name="a" discarded={vi.fn()} /></DraftGroupContext.Provider>
        <DraftGroupContext.Provider value={groupB}><Form name="b" discarded={vi.fn()} /></DraftGroupContext.Provider>
      </>,
    );
    const ask = async () => {
      await user.click(screen.getByRole("button", { name: "Ask" }));
      return screen.getByRole("status").textContent;
    };
    // everything / group a / group b
    expect(await ask()).toBe("false false false");
    await user.click(screen.getByRole("button", { name: "Edit a" }));
    expect(await ask()).toBe("true true false");
    await user.click(screen.getByRole("button", { name: "Save a" }));
    expect(await ask()).toBe("false false false");
  });

  describe("under a data router", () => {
    test("leaving to another page is allowed for a clean form", async () => {
      const user = userEvent.setup();
      const router = await renderRouted(<Form name="a" discarded={vi.fn()} />);
      await user.click(screen.getByRole("button", { name: "Edit a" }));
      await user.click(screen.getByRole("button", { name: "Save a" }));
      await act(() => router.navigate("/elsewhere"));
      expect(router.state.location.pathname).toBe("/elsewhere");
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    test("leaving a dirty form asks first: Keep editing stays, Discard changes goes", async () => {
      const user = userEvent.setup();
      const router = await renderRouted(<Form name="a" discarded={vi.fn()} />);
      await user.click(screen.getByRole("button", { name: "Edit a" }));
      await act(() => router.navigate("/elsewhere"));
      await user.click(within(await dialog()).getByRole("button", { name: "Keep editing" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(router.state.location.pathname).toBe("/start");
      await act(() => router.navigate("/elsewhere"));
      await user.click(within(await dialog()).getByRole("button", { name: "Discard changes" }));
      await waitFor(() => expect(router.state.location.pathname).toBe("/elsewhere"));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    });

    test("a change of the query string or hash on the same page is not leaving it", async () => {
      const user = userEvent.setup();
      const router = await renderRouted(<Form name="a" discarded={vi.fn()} />);
      await user.click(screen.getByRole("button", { name: "Edit a" }));
      await act(() => router.navigate("/start?page=2#top"));
      expect(router.state.location.search).toBe("?page=2");
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    test("a sign-out redirect to /login is never blocked: the session is over and the draft with it", async () => {
      const user = userEvent.setup();
      const router = await renderRouted(<Form name="a" discarded={vi.fn()} />);
      await user.click(screen.getByRole("button", { name: "Edit a" }));
      await act(() => router.navigate("/login"));
      expect(router.state.location.pathname).toBe("/login");
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    test("going to /login while still signed in is an ordinary page change and asks first", async () => {
      localStorage.setItem("hammurabi.auth.token", "fake-token");
      const user = userEvent.setup();
      const router = await renderRouted(<Form name="a" discarded={vi.fn()} />);
      await user.click(screen.getByRole("button", { name: "Edit a" }));
      await act(() => router.navigate("/login"));
      await dialog();
      expect(router.state.location.pathname).toBe("/start");
    });

    test("a local cancel asks through the same dialog and leaves the route alone", async () => {
      const discarded = vi.fn();
      const user = userEvent.setup();
      const router = await renderRouted(<Form name="a" discarded={discarded} />);
      await user.click(screen.getByRole("button", { name: "Edit a" }));
      await user.click(screen.getByRole("button", { name: "Cancel a" }));
      await user.click(within(await dialog()).getByRole("button", { name: "Discard changes" }));
      expect(discarded).toHaveBeenCalledExactlyOnceWith("a");
      expect(router.state.location.pathname).toBe("/start");
    });

    test("a route change while a local cancel is being asked takes over the dialog; leaving then drops the local question too", async () => {
      const discarded = vi.fn();
      const user = userEvent.setup();
      const router = await renderRouted(<Form name="a" discarded={discarded} />);
      await user.click(screen.getByRole("button", { name: "Edit a" }));
      await user.click(screen.getByRole("button", { name: "Cancel a" }));
      await dialog();
      await act(() => router.navigate("/elsewhere"));
      await user.click(within(await dialog()).getByRole("button", { name: "Discard changes" }));
      await waitFor(() => expect(router.state.location.pathname).toBe("/elsewhere"));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      // Leaving the page discards the whole draft; the local cancel's own discard never ran on top of it.
      expect(discarded).not.toHaveBeenCalled();
    });
  });
});
