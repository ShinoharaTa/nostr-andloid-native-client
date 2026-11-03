import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { npubEncode } from "nostr-tools/nip19";
import { generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { createMemoryRouter, RouterProvider } from "react-router";
import { VirtuosoMockContext } from "react-virtuoso";
import type { Subject } from "rxjs";
import { afterEach, beforeAll, beforeEach, expect, it, vi } from "vitest";
import { subscribeTo } from "../../nostr/pool";
import { useSession } from "../../signer/session";
import { renderWithRouter } from "../../test/renderWithRouter";
import { markSeen, useDmSeen } from "../dm/dmSeen";
import { useDm } from "../dm/dmStore";
import { NotificationsScreen } from "./NotificationsScreen";

// リレーには繋がず、REQ ごとに Subject を返す（張った / やめたを observed で見る）
vi.mock("../../nostr/pool", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/pool")>();
  const { Subject } = await import("rxjs");
  const RELAYS = ["wss://relay.example"];
  return {
    ...actual,
    readRelays: () => RELAYS,
    writeRelays: () => RELAYS,
    // 毎回同じ配列を返す（新しい配列だと購読の張り直しが止まらない）
    useReadRelays: () => RELAYS,
    subscribe: vi.fn(() => new Subject<"EOSE">()),
    subscribeTo: vi.fn(() => new Subject<"EOSE">()),
    requestOnce: vi.fn(() => new Subject<NostrEvent>()),
  };
});

let me: string;

beforeAll(() => {
  // jsdom に ResizeObserver が無い（Virtuoso が使う。寸法は VirtuosoMockContext が与える）
  if (typeof globalThis.ResizeObserver !== "function") {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
});

beforeEach(() => {
  me = getPublicKey(generateSecretKey());
  useSession.setState({ status: "in", method: "nip07", pubkey: me });
  vi.mocked(subscribeTo).mockClear();
});

afterEach(() => {
  useSession.setState({ status: "loading", method: null, pubkey: null });
  useDm.getState().reset(null);
  useDmSeen.setState({ me: null, first: 0, peers: {} });
  localStorage.clear();
});

/** 相手から未読の DM が 3 通ある状態（既読は 0 から） */
function seedUnreadDm(peer: string) {
  useDm.getState().reset(me);
  // [#970] フォロー中の相手（「その他」は通知の行に出さない）
  useDm.setState({ follows: new Set([peer]) });
  useDmSeen.setState({ me, first: 0, peers: {} });
  useDm.getState().upsertMessages(
    [1, 2, 3].map((n) => ({
      owner: me,
      id: `dm${n}`,
      peer,
      sender: peer,
      content: `秘密の本文${n}`,
      tags: [],
      createdAt: 1_700_000_000 + n,
      proto: "nip17" as const,
    })),
  );
}

/** 通知画面を / に描き、/messages/:peer へ移れるルータ */
function renderScreen() {
  const router = createMemoryRouter([
    {
      path: "/",
      element: (
        <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
          <NotificationsScreen />
        </VirtuosoMockContext.Provider>
      ),
    },
    { path: "/messages/:peer", element: <p>messages</p> },
  ]);
  render(<RouterProvider router={router} />);
  return router;
}

it("未読のある会話を 1 行の DM 通知で出し（本文は出さない）、押すと /messages/npub1…（#522）", async () => {
  const user = userEvent.setup();
  const peer = getPublicKey(generateSecretKey());
  seedUnreadDm(peer);
  const router = renderScreen();

  expect(screen.getByRole("img", { name: "メッセージ" })).toBeInTheDocument();
  expect(screen.getByText("3件のメッセージが届いています")).toBeInTheDocument();
  expect(screen.queryByText(/秘密の本文/)).toBeNull();

  await user.click(screen.getByText("3件のメッセージが届いています"));
  expect(router.state.location.pathname).toBe(`/messages/${npubEncode(peer)}`);
});

it("DM の通知は既読にすると消え、未読 1 通なら「メッセージが届きました」（#522）", () => {
  const peer = getPublicKey(generateSecretKey());
  seedUnreadDm(peer);
  renderScreen();
  expect(screen.getByText("3件のメッセージが届いています")).toBeInTheDocument();

  act(() => useDmSeen.setState({ peers: { [peer]: 1_700_000_002 } }));
  expect(screen.getByText("メッセージが届きました")).toBeInTheDocument();

  act(() => markSeen(me, peer, 1_700_000_003));
  expect(screen.queryByText(/メッセージが届/)).toBeNull();
  expect(screen.queryByRole("img", { name: "メッセージ" })).toBeNull();
});

it("見出しと説明を出し、自分宛ての 6 種を 200 件で購読する。閉じたら購読をやめる", () => {
  const { unmount } = renderWithRouter(<NotificationsScreen />);

  expect(screen.getByRole("heading", { name: "通知" })).toBeInTheDocument();
  expect(screen.getByText("メンション・リアクション・リポスト")).toBeInTheDocument();
  expect(screen.getByText("読み込み中…")).toBeInTheDocument();

  const { calls, results } = vi.mocked(subscribeTo).mock;
  expect(calls).toEqual([
    [["wss://relay.example"], [{ kinds: [1, 6, 16, 7, 9735, 1111], "#p": [me], limit: 200 }]],
  ]);
  const req = results[0].value as Subject<"EOSE">;
  expect(req.observed).toBe(true);

  unmount();
  expect(req.observed).toBe(false);
});
