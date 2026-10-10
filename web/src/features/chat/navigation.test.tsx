import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { routes } from "../../app/routes";
import type { ColumnSpec } from "../../lib/columns";
import { DEFAULT_COLUMNS } from "../../lib/columns";
import { useSession } from "../../signer/session";
import { useDeck } from "../../store/deck";
import { OTHER_PUBKEY, PUBKEY, resetSession } from "../../test/fakeNostr";
import { clearViewport, mockViewport } from "../../test/viewport";
import { useDmSeen } from "../dm/dmSeen";
import { useDm } from "../dm/dmStore";
import { resetChannelsForTest, useChannels } from "./channels";

// カラムの中身とダイアログは描かない（AppShell.test と同じ）
vi.mock("../deck/DeckColumn", () => ({
  DeckColumn: ({ spec }: { spec: ColumnSpec }) => <div data-testid={`col-${spec.id}`} />,
  ColumnMenu: () => <button type="button">カラムメニュー</button>,
}));
vi.mock("../deck/AddColumnDialog", () => ({ AddColumnDialog: () => <div role="dialog" /> }));
vi.mock("../deck/EditColumnDialog", () => ({ EditColumnDialog: () => <div role="dialog" /> }));
// DM の購読・復号はしない
vi.mock("../dm/dmService", () => ({ startDecrypting: vi.fn(), resumeDecrypting: vi.fn() }));

beforeEach(() => {
  localStorage.clear();
  useDeck.setState({
    columns: structuredClone([...DEFAULT_COLUMNS]),
    widths: {},
    jumpTarget: null,
    visibleColumnId: null,
    editingColumnId: null,
    showAddColumn: false,
  });
  useSession.setState({ status: "in", method: "nip07", pubkey: PUBKEY });
  // 一覧の取得は空を返す（実際には取りに行かない）
  useChannels.setState({ channels: [] });
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify({ data: [] }))),
  );
  useDm.getState().reset(PUBKEY);
  useDmSeen.setState({ me: PUBKEY, first: 0, peers: {} });
});

afterEach(() => {
  vi.unstubAllGlobals();
  clearViewport();
  resetSession();
  resetChannelsForTest();
  useDm.getState().reset(null);
  useDmSeen.setState({ me: null, first: 0, peers: {} });
});

function seedUnreadDm() {
  useDm.getState().upsertMessages([
    {
      owner: PUBKEY,
      id: "a1",
      peer: OTHER_PUBKEY,
      sender: OTHER_PUBKEY,
      content: "未読",
      tags: [],
      createdAt: 1,
      proto: "nip17",
    },
  ]);
}

function renderAt(path: string, width = 400) {
  mockViewport(width);
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

function mainNav() {
  return screen.getByRole("navigation", { name: "メイン" });
}

function publicChatButton() {
  return within(mainNav()).getByRole("button", { name: "パブリックチャット" });
}

/** [#797] ナビの 3 枠目は「パブリックチャット」直行（#422 の「メッセージ」と「DM | チャット」の切り替えはやめた） */
describe("下部ナビ・レールの「パブリックチャット」", () => {
  it("未読の DM があっても、直前が DM でも、常にチャンネル一覧へ置き換える", async () => {
    seedUnreadDm();
    const router = renderAt("/messages");
    await userEvent.click(publicChatButton());
    expect(router.state.location.pathname).toBe("/channels");
    expect(router.state.historyAction).toBe("REPLACE");
    expect(publicChatButton()).toHaveAttribute("aria-current", "page");
  });

  it("レールでも同じ", async () => {
    const router = renderAt("/search", 1200);
    await userEvent.click(publicChatButton());
    expect(router.state.location.pathname).toBe("/channels");
  });

  it("一覧の見出しは通常のカラムヘッダ（パブリックチャット / NIP-28 · channels）。「DM | チャット」の切り替えは無い", () => {
    renderAt("/channels");
    expect(screen.getByRole("heading", { level: 1, name: "パブリックチャット" })).toBeInTheDocument();
    expect(screen.getByText("NIP-28 · channels")).toBeInTheDocument();
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
  });

  it("DM を開いている間は「パブリックチャット」ではなく自分のアイコンを選択表示する", () => {
    renderAt("/messages");
    expect(publicChatButton()).not.toHaveAttribute("aria-current");
    expect(within(mainNav()).getByRole("button", { name: "アカウントのメニュー" })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });

  it("/channels の詳細（プロフィール）の背後もパブリックチャットのまま", async () => {
    const router = renderAt("/channels");
    await act(() => router.navigate(`/p/${OTHER_PUBKEY}`));
    expect(
      screen.getByRole("heading", { level: 1, name: "パブリックチャット", hidden: true }),
    ).toBeInTheDocument();
    expect(publicChatButton()).toHaveAttribute("aria-current", "page");
  });
});
