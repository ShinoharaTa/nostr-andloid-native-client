import { act, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { neventEncode, noteEncode } from "nostr-tools/nip19";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { VirtuosoMockContext } from "react-virtuoso";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { resetChannelsForTest, useChannels } from "../../features/chat/channels";
import { ComposeHost } from "../../features/compose/ComposeHost";
import { useCompose } from "../../features/compose/composeStore";
import { unixNow } from "../../lib/time";
import { subscribeTo } from "../../nostr/pool";
import { type EventDraft, publishEvent } from "../../nostr/publish";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { installDialogPolyfill } from "../../test/dialog";
import { renderWithRouter } from "../../test/renderWithRouter";
import { ThreadOverlay } from "./ThreadOverlay";

// リレーには繋がない（REQ は開いたままの Subject）
vi.mock("../../nostr/pool", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/pool")>();
  const { Subject } = await import("rxjs");
  const relays = ["wss://relay.example"];
  return {
    ...actual,
    useReadRelays: () => relays,
    subscribeTo: vi.fn(() => new Subject<"EOSE">()),
    requestOnce: vi.fn(() => new Subject<NostrEvent>()),
  };
});

// 署名・送信はしない
vi.mock("../../nostr/publish", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/publish")>()),
  publishEvent: vi.fn(),
  setPublishAccount: vi.fn(),
}));

let meKey: Uint8Array;

beforeAll(() => {
  installDialogPolyfill();
});

beforeEach(() => {
  meKey = generateSecretKey();
  useSession.setState({ status: "in", method: "nip07", pubkey: getPublicKey(meKey) });
  vi.mocked(publishEvent).mockImplementation(async (draft: EventDraft) =>
    finalizeEvent({ ...draft, created_at: unixNow() }, meKey),
  );
});

afterEach(() => {
  useCompose.setState({ request: null });
  useSession.setState({ status: "loading", method: null, pubkey: null });
});

function stored(tags: string[][], content: string, createdAt: number): NostrEvent {
  const event = finalizeEvent({ kind: 1, created_at: createdAt, tags, content }, generateSecretKey());
  eventStore.add(event);
  return event;
}

it("返信ボックスから起点への返信を送れる（NIP-10 の root → reply）", async () => {
  const user = userEvent.setup();
  const root = stored([], "ルート", 1_000);
  const focus = stored([["e", root.id, "", "root"]], "起点", 1_001);
  renderWithRouter(
    <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
      <ThreadOverlay refParam={neventEncode({ id: focus.id })} onBack={() => {}} />
      <ComposeHost showFab={false} />
    </VirtuosoMockContext.Provider>,
  );

  await user.click(await screen.findByRole("button", { name: "返信を書く" }));
  const dialog = screen.getByRole("dialog", { name: "返信" });
  await user.type(within(dialog).getByRole("textbox", { name: "本文" }), "スレッドから返信");
  await user.click(within(dialog).getByRole("button", { name: "返信" }));

  expect(publishEvent).toHaveBeenCalledTimes(1);
  const draft = vi.mocked(publishEvent).mock.calls[0][0];
  expect(draft).toMatchObject({ kind: 1, content: "スレッドから返信" });
  expect(draft.tags.slice(0, 3)).toEqual([
    ["e", root.id, expect.any(String), "root", root.pubkey],
    ["e", focus.id, expect.any(String), "reply", focus.pubkey],
    ["p", focus.pubkey, expect.any(String)],
  ]);
  expect(useCompose.getState().request).toBeNull();
});

describe("[#798] パブリックチャットへのリンク", () => {
  const created = finalizeEvent(
    {
      kind: 40,
      created_at: 1_000,
      tags: [],
      content: JSON.stringify({ name: "雑談部屋", about: "なんでも" }),
    },
    generateSecretKey(),
  );

  beforeEach(() => {
    useChannels.setState({
      channels: [
        {
          id: created.id,
          name: "雑談部屋",
          about: "なんでも",
          picture: null,
          relays: [],
          createdAt: 1_000,
          lastAt: 1_000,
        },
      ],
      loading: false,
      failed: false,
    });
  });

  afterEach(() => {
    resetChannelsForTest();
  });

  it("kind:40 の nevent はそのチャンネルのルームを開く（取得を待たない）", () => {
    renderWithRouter(
      <ThreadOverlay refParam={neventEncode({ id: created.id, kind: 40 })} onBack={() => {}} />,
    );
    expect(screen.getByRole("heading", { name: "雑談部屋" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "スレッド" })).not.toBeInTheDocument();
    expect(subscribeTo).toHaveBeenCalledWith(expect.any(Array), [
      { kinds: [42], "#e": [created.id], limit: 200 },
    ]);
  });

  it("kind の無い note でも、手元のチャンネル一覧にあればルームを開く", () => {
    renderWithRouter(<ThreadOverlay refParam={noteEncode(created.id)} onBack={() => {}} />);
    expect(screen.getByRole("heading", { name: "雑談部屋" })).toBeInTheDocument();
  });

  it("kind:42 は root のチャンネルのルームを開き、その発言を強調する", () => {
    const msg = finalizeEvent(
      { kind: 42, created_at: 1_100, tags: [["e", created.id, "", "root"]], content: "リンク先の発言" },
      generateSecretKey(),
    );
    const other = finalizeEvent(
      { kind: 42, created_at: 1_200, tags: [["e", created.id, "", "root"]], content: "ほかの発言" },
      generateSecretKey(),
    );
    eventStore.add(msg);
    eventStore.add(other);
    renderWithRouter(<ThreadOverlay refParam={neventEncode({ id: msg.id, kind: 42 })} onBack={() => {}} />);

    const room = screen.getByRole("region", { name: "雑談部屋" });
    expect(within(room).getByText("リンク先の発言").closest("article")).toHaveAttribute("data-highlight");
    expect(within(room).getByText("ほかの発言").closest("article")).not.toHaveAttribute("data-highlight");
  });

  it("一覧に無いチャンネルは「パブリックチャット」で開き、kind:40 が届けば名前を出す", async () => {
    useChannels.setState({ channels: [] });
    const later = finalizeEvent(
      { kind: 40, created_at: 1_000, tags: [], content: JSON.stringify({ name: "あとから届く部屋" }) },
      generateSecretKey(),
    );
    renderWithRouter(<ThreadOverlay refParam={neventEncode({ id: later.id, kind: 40 })} onBack={() => {}} />);
    expect(screen.getByRole("heading", { name: "パブリックチャット" })).toBeInTheDocument();
    act(() => {
      eventStore.add(later);
    });
    expect(await screen.findByRole("heading", { name: "あとから届く部屋" })).toBeInTheDocument();
  });

  it("kind:1 の投稿はこれまでどおりスレッド", () => {
    const note = stored([], "ふつうの投稿", 1_300);
    renderWithRouter(
      <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
        <ThreadOverlay refParam={noteEncode(note.id)} onBack={() => {}} />
      </VirtuosoMockContext.Provider>,
    );
    expect(screen.getByRole("heading", { name: "スレッド" })).toBeInTheDocument();
  });
});
