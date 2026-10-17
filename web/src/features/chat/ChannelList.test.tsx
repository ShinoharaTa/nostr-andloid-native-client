import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { EMPTY } from "rxjs";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { requestOnce } from "../../nostr/pool";
import { publishEvent } from "../../nostr/publish";
import { addVerified } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { installDialogPolyfill } from "../../test/dialog";
import { ChannelList } from "./ChannelList";
import { resetMyChannelsForTest } from "./channelEdit";
import { resetChannelsForTest, useChannels } from "./channels";
import { useJoinedRooms } from "./joinedRooms";

// リレーへは張らない（自分の kind:40 の購読・#478 の取り直しの呼ばれ方だけ見る）
vi.mock("../../nostr/pool", async (importOriginal) => {
  const { NEVER } = await import("rxjs");
  return {
    ...(await importOriginal<typeof import("../../nostr/pool")>()),
    subscribe: vi.fn(() => NEVER),
    requestOnce: vi.fn(() => EMPTY),
  };
});

// 署名・送信はしない
vi.mock("../../nostr/publish", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/publish")>()),
  publishEvent: vi.fn(async (draft: { kind: number; content: string; tags: string[][] }) =>
    finalizeEvent({ ...draft, created_at: 9_000 }, generateSecretKey()),
  ),
}));

const meKey = generateSecretKey();
const ME = getPublicKey(meKey);

/** 自分が作成した kind:40（一覧の行の id はこのイベントの id と一致させる） */
function myChannel(): NostrEvent {
  return finalizeEvent(
    { kind: 40, created_at: 1, tags: [], content: JSON.stringify({ name: "自分のスレッド" }) },
    meKey,
  );
}

const OWN = myChannel();
const CHANNELS = [
  { id: OWN.id, name: "自分のスレッド", about: "", picture: null, relays: [], createdAt: 1, lastAt: 10 },
  {
    id: "b".repeat(64),
    name: "他人のスレッド",
    about: "",
    picture: null,
    relays: [],
    createdAt: 1,
    lastAt: 5,
  },
];

// ChannelList はマウントで /api/nchan/channels を取り直す（channels.ts）ので、手元で入れた CHANNELS と
// 同じ内容を返しておく（そうしないと非同期の応答で後から上書きされ、await を挟むテストが不安定になる）
const FETCHED = {
  data: [
    { id: OWN.id, content: JSON.stringify({ name: "自分のスレッド" }), latest_update: 10 },
    { id: "b".repeat(64), content: JSON.stringify({ name: "他人のスレッド" }), latest_update: 5 },
  ],
};

beforeAll(() => {
  installDialogPolyfill();
});

beforeEach(() => {
  vi.mocked(publishEvent).mockClear();
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify(FETCHED))),
  );
});

afterEach(() => {
  useJoinedRooms.setState({ joined: null });
  resetChannelsForTest();
  resetMyChannelsForTest();
  vi.unstubAllGlobals();
  useSession.setState({ status: "loading", method: null, pubkey: null });
});

it("未ログインでは「新しいスレッドを作成」も ✏️ も出ない", () => {
  useChannels.setState({ channels: CHANNELS });
  render(<ChannelList selectedId={null} pinnedIds={new Set()} onSelect={vi.fn()} onPin={vi.fn()} />);
  expect(screen.queryByRole("button", { name: "新しいスレッドを作成" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "スレッドを編集" })).not.toBeInTheDocument();
});

describe("ログイン中", () => {
  beforeEach(() => {
    useSession.setState({ status: "in", method: "nip07", pubkey: ME });
  });

  it("自分が作った kind:40 の行にだけ ✏️。他人のスレッドには出ない", () => {
    useChannels.setState({ channels: CHANNELS });
    addVerified(OWN);
    render(<ChannelList selectedId={null} pinnedIds={new Set()} onSelect={vi.fn()} onPin={vi.fn()} />);

    expect(screen.getAllByRole("button", { name: "スレッドを編集" })).toHaveLength(1);
    const ownRow = screen.getByText("自分のスレッド").closest("li");
    if (!ownRow) throw new Error("no row");
    expect(within(ownRow).getByRole("button", { name: "スレッドを編集" })).toBeInTheDocument();
  });

  it("「新しいスレッドを作成」で作成し、一覧の先頭へ足して onSelect（API の一覧を待たない）", async () => {
    const user = userEvent.setup();
    useChannels.setState({ channels: CHANNELS });
    const onSelect = vi.fn();
    render(<ChannelList selectedId={null} pinnedIds={new Set()} onSelect={onSelect} onPin={vi.fn()} />);

    await user.click(screen.getByRole("button", { name: "新しいスレッドを作成" }));
    expect(screen.getByRole("dialog", { name: "スレッドを作成" })).toBeInTheDocument();
    await user.type(screen.getByLabelText("スレッド名"), "新規スレッド");
    await user.click(screen.getByRole("button", { name: "作成" }));

    await waitFor(() => expect(onSelect).toHaveBeenCalledTimes(1));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ name: "新規スレッド" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    const created = onSelect.mock.calls[0][0];
    expect(useChannels.getState().channels?.[0]).toEqual(created);
  });

  it("✏️ を押すと編集ダイアログが開く", async () => {
    const user = userEvent.setup();
    useChannels.setState({ channels: CHANNELS });
    addVerified(OWN);
    render(<ChannelList selectedId={null} pinnedIds={new Set()} onSelect={vi.fn()} onPin={vi.fn()} />);

    await user.click(screen.getByRole("button", { name: "スレッドを編集" }));
    expect(screen.getByRole("dialog", { name: "スレッドを編集" })).toBeInTheDocument();
    expect(vi.mocked(requestOnce)).toHaveBeenCalled();
  });
});

/** [#856] 参加中のルーム（kind:10005） */
describe("参加中のルーム", () => {
  /** 区画の見出しと、その後ろのリストの行の名前 */
  function section(name: string): string[] {
    const heading = screen.getByRole("heading", { name });
    const list = heading.nextElementSibling;
    if (!(list instanceof HTMLElement) || list.tagName !== "UL") throw new Error(`no list after ${name}`);
    return within(list)
      .getAllByRole("listitem")
      .map((li) => li.querySelector("[class*=name]")?.textContent ?? "");
  }

  it("参加中を「参加中」として上に分け、残りを「すべてのルーム」に並べる。参加中は下に重ねて出さない", () => {
    const third = { ...CHANNELS[1], id: "c".repeat(64), name: "三つ目のスレッド", lastAt: 1 };
    useChannels.setState({ channels: [...CHANNELS, third] });
    // kind:10005 の順は third → 他人（一覧と同じ最終活動の順で出す）
    useJoinedRooms.setState({ joined: { ids: [third.id, "b".repeat(64)], hints: {} } });
    render(<ChannelList selectedId={null} pinnedIds={new Set()} onSelect={vi.fn()} onPin={vi.fn()} />);

    const headings = screen.getAllByRole("heading").map((h) => h.textContent);
    expect(headings).toEqual(["参加中", "すべてのルーム"]);
    expect(section("参加中")).toEqual(["他人のスレッド", "三つ目のスレッド"]);
    expect(section("すべてのルーム")).toEqual(["自分のスレッド"]);
    expect(screen.getAllByText("他人のスレッド")).toHaveLength(1);
  });

  it("参加中が 0 件なら見出しを出さず今までどおり", () => {
    useChannels.setState({ channels: CHANNELS });
    useJoinedRooms.setState({ joined: { ids: [], hints: {} } });
    render(<ChannelList selectedId={null} pinnedIds={new Set()} onSelect={vi.fn()} onPin={vi.fn()} />);

    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
  });

  it("一覧に無い参加中のルームは kind:40 が届くまで「パブリックチャット」で出し、届けば名前に変わる。押すと開ける", async () => {
    const user = userEvent.setup();
    const unlisted = finalizeEvent(
      { kind: 40, created_at: 2, tags: [], content: JSON.stringify({ name: "一覧に無いスレッド" }) },
      generateSecretKey(),
    );
    useChannels.setState({ channels: CHANNELS });
    useJoinedRooms.setState({ joined: { ids: [unlisted.id], hints: {} } });
    const onSelect = vi.fn();
    render(<ChannelList selectedId={null} pinnedIds={new Set()} onSelect={onSelect} onPin={vi.fn()} />);

    expect(section("参加中")).toEqual(["パブリックチャット"]);
    expect(section("すべてのルーム")).toEqual(["自分のスレッド", "他人のスレッド"]);

    await user.click(screen.getByRole("button", { name: "パブリックチャット" }));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: unlisted.id }));

    addVerified(unlisted);
    await waitFor(() => expect(section("参加中")).toEqual(["一覧に無いスレッド"]));
  });
});
