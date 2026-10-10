import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { nprofileEncode, npubEncode } from "nostr-tools/nip19";
import { act } from "react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { DmMessageRow } from "../../db/schema";
import { installDialogPolyfill } from "../../test/dialog";
import { OTHER_PUBKEY, PUBKEY } from "../../test/fakeNostr";
import { clearViewport, mockViewport } from "../../test/viewport";
import { useDmSeen } from "./dmSeen";
import { resumeDecrypting, startDecrypting } from "./dmService";
import { useDm } from "./dmStore";
import { MessagesScreen } from "./MessagesScreen";
import { parsePeerInput } from "./NewConversationDialog";

// 購読・復号はしない（状態はストアへ直接入れる）
vi.mock("./dmService", () => ({ startDecrypting: vi.fn(), resumeDecrypting: vi.fn() }));

const ME = PUBKEY;
const ALICE = OTHER_PUBKEY;
const BOB = "a".repeat(64);

beforeEach(() => {
  vi.mocked(startDecrypting).mockClear();
  vi.mocked(resumeDecrypting).mockClear();
  useDm.getState().reset(ME);
  useDm.setState({ loaded: true });
  // 既定は全部既読（未読のテストで first を戻す）
  useDmSeen.setState({ me: ME, first: 2_000_000_000, peers: {} });
});

afterEach(() => {
  clearViewport();
  useDm.getState().reset(null);
  useDmSeen.setState({ me: null, first: 0, peers: {} });
  localStorage.clear();
});

function dm(id: string, peer: string, sender: string, content: string, createdAt: number): DmMessageRow {
  return { owner: ME, id, peer, sender, content, tags: [], createdAt, proto: "nip17" };
}

function seed() {
  useDm
    .getState()
    .upsertMessages([
      dm("a1", ALICE, ALICE, "こんにちは", 1_700_000_000),
      dm("a2", ALICE, ME, "やあ", 1_700_000_100),
      dm("a3", ALICE, ALICE, "元気？", 1_700_000_200),
      dm("b1", BOB, BOB, "bob です", 1_700_000_050),
    ]);
}

function renderAt(path: string, width: number) {
  mockViewport(width);
  const router = createMemoryRouter(
    [
      { path: "/messages/:peer?", element: <MessagesScreen /> },
      { path: "/p/:ref", element: <p>profile</p> },
    ],
    { initialEntries: [path] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

function rowButtons() {
  return within(screen.getByRole("list")).getAllByRole("button");
}

describe("一覧", () => {
  it("相手と最後のメッセージを新しい順に出し、表示したら復号を始める", () => {
    seed();
    renderAt("/messages", 400);
    expect(screen.getByRole("heading", { level: 1, name: "DM" })).toBeInTheDocument();
    // [#797] 見出しは通常のカラムヘッダ（DM / NIP-17）。「DM | チャット」の切り替えは無い
    expect(screen.getByText("NIP-17")).toBeInTheDocument();
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
    const rows = rowButtons();
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent(ALICE.slice(0, 10));
    expect(rows[0]).toHaveTextContent("元気？");
    expect(rows[1]).toHaveTextContent("bob です");
    expect(vi.mocked(startDecrypting)).toHaveBeenCalledTimes(1);
  });

  it("0 件: 読み込み前は進捗、読み込み後は「まだ会話がありません」", () => {
    useDm.setState({ loaded: false });
    renderAt("/messages", 400);
    expect(screen.getByText("読み込み中…")).toBeInTheDocument();
    act(() => useDm.setState({ loaded: true }));
    expect(screen.getByText("まだ会話がありません")).toBeInTheDocument();
  });

  it("案内: NIP-44 が無い・復号中・一時停止（「再開」で resumeDecrypting）", async () => {
    useDm.setState({ nip17: "no-nip44", pending: 3 });
    renderAt("/messages", 400);
    const statuses = () => screen.getAllByRole("status").map((s) => s.textContent);
    expect(statuses()).toEqual([
      "この拡張機能は NIP-44 に対応していないため、NIP-17 の DM を読めません（NIP-04 の DM だけ表示しています）",
      "復号中（残り 3 件）",
    ]);

    act(() => useDm.setState({ nip17: "ok", paused: true }));
    expect(statuses()).toEqual(["復号を一時停止しました（署名の要求が拒否されたか、応答がありません）再開"]);
    await userEvent.click(screen.getByRole("button", { name: "再開" }));
    expect(vi.mocked(resumeDecrypting)).toHaveBeenCalledTimes(1);
  });

  it("アバターを押すと相手のプロフィール（/p/npub1…）", async () => {
    seed();
    const router = renderAt("/messages", 400);
    await userEvent.click(screen.getAllByRole("link", { name: /のプロフィール$/ })[0]);
    expect(router.state.location.pathname).toBe(`/p/${npubEncode(ALICE)}`);
  });
});

describe("Compact", () => {
  it("行を押すと /messages/npub1… で会話を開き、「戻る」で一覧へ", async () => {
    seed();
    const router = renderAt("/messages", 400);
    await userEvent.click(rowButtons()[0]);
    expect(router.state.location.pathname).toBe(`/messages/${npubEncode(ALICE)}`);

    const conversation = screen.getByRole("region");
    expect(within(conversation).getByRole("heading", { name: ALICE.slice(0, 10) })).toBeInTheDocument();
    expect(within(conversation).getByText("こんにちは")).toBeInTheDocument();
    expect(within(conversation).getByText("やあ")).toBeInTheDocument();
    expect(within(conversation).queryByText("bob です")).not.toBeInTheDocument();
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
    // [#600] Compact は「←」だけ。「✕」（選択解除）は Expanded だけ
    expect(screen.queryByRole("button", { name: "選択を解除" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "戻る" }));
    expect(router.state.location.pathname).toBe("/messages");
    expect(rowButtons()).toHaveLength(2);
  });

  it("直接開いた会話の「戻る」は一覧へ置き換える", async () => {
    seed();
    const router = renderAt(`/messages/${npubEncode(ALICE)}`, 400);
    await userEvent.click(screen.getByRole("button", { name: "戻る" }));
    expect(router.state.location.pathname).toBe("/messages");
    expect(router.state.historyAction).toBe("REPLACE");
  });

  it("最新が下（DOM は新しい順）。自分の吹き出しと相手の吹き出しを分ける", () => {
    seed();
    renderAt(`/messages/${ALICE}`, 400);
    const texts = screen.getAllByText(/^(こんにちは|やあ|元気？)$/).map((e) => e.textContent);
    expect(texts).toEqual(["元気？", "やあ", "こんにちは"]);
  });

  it("相手を読めなければ「相手を読み取れません」", () => {
    renderAt("/messages/npub1broken", 400);
    expect(screen.getByText("相手を読み取れません")).toBeInTheDocument();
  });

  it("200 件より古いものは「さらに表示」で出す", async () => {
    useDm
      .getState()
      .upsertMessages(
        Array.from({ length: 201 }, (_, n) =>
          dm(`m${String(n).padStart(3, "0")}`, ALICE, ALICE, `msg ${n}`, n),
        ),
      );
    renderAt(`/messages/${npubEncode(ALICE)}`, 400);
    expect(screen.queryByText("msg 0")).not.toBeInTheDocument();
    expect(screen.getByText("msg 1")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "さらに表示" }));
    expect(screen.getByText("msg 0")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "さらに表示" })).not.toBeInTheDocument();
  });
});

describe("Expanded", () => {
  it("未選択は「会話を選択」。行を押すと右に会話（履歴は置き換え）、選択中の行は aria-current", async () => {
    seed();
    const router = renderAt("/messages", 1400);
    expect(screen.getByText("会話を選択")).toBeInTheDocument();

    await userEvent.click(rowButtons()[1]);
    expect(router.state.location.pathname).toBe(`/messages/${npubEncode(BOB)}`);
    expect(router.state.historyAction).toBe("REPLACE");
    expect(rowButtons()[1]).toHaveAttribute("aria-current", "true");
    expect(rowButtons()[0]).not.toHaveAttribute("aria-current");
    expect(within(screen.getByRole("region")).getByText("bob です")).toBeInTheDocument();
    expect(screen.queryByText("会話を選択")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "戻る" })).not.toBeInTheDocument();
  });

  it("[#600] ヘッダ右端に「✕」（選択解除）。押すと一覧はそのままプレースホルダへ戻る", async () => {
    seed();
    const router = renderAt(`/messages/${npubEncode(ALICE)}`, 1400);
    expect(within(screen.getByRole("region")).getByText("こんにちは")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "選択を解除" }));
    expect(router.state.location.pathname).toBe("/messages");
    expect(router.state.historyAction).toBe("REPLACE");
    expect(screen.getByText("会話を選択")).toBeInTheDocument();
    expect(rowButtons()).toHaveLength(2);
  });
});

describe("新しい会話", () => {
  beforeEach(() => {
    installDialogPolyfill();
  });

  function openDialog() {
    return userEvent.click(screen.getByRole("button", { name: "新しいメッセージを送る" }));
  }

  function field() {
    return within(screen.getByRole("dialog", { name: "新しいメッセージ" })).getByPlaceholderText(
      "npub または hex",
    );
  }

  function openButton() {
    return screen.getByRole("button", { name: "開く" });
  }

  it("不正な入力（空・壊れた npub・nprofile・63 桁の hex）では「開く」を押せない", async () => {
    renderAt("/messages", 400);
    await openDialog();
    expect(openButton()).toBeDisabled();
    for (const value of ["npub1broken", nprofileEncode({ pubkey: ALICE }), "a".repeat(63), "not a key"]) {
      await userEvent.clear(field());
      await userEvent.type(field(), value);
      expect(openButton()).toBeDisabled();
    }
  });

  it("npub で /messages/npub1… を開く（まだ会話の無い相手は空の会話 + 入力欄）", async () => {
    const router = renderAt("/messages", 400);
    await openDialog();
    await userEvent.type(field(), npubEncode(ALICE));
    await userEvent.click(openButton());

    expect(router.state.location.pathname).toBe(`/messages/${npubEncode(ALICE)}`);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "メッセージ" })).toBeInTheDocument();
  });

  it("hex（大文字も可）でも開ける。「キャンセル」で閉じる", async () => {
    const router = renderAt("/messages", 1400);
    await openDialog();
    await userEvent.click(screen.getByRole("button", { name: "キャンセル" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await openDialog();
    await userEvent.type(field(), ` ${BOB.toUpperCase()} `);
    await userEvent.click(openButton());
    expect(router.state.location.pathname).toBe(`/messages/${npubEncode(BOB)}`);
  });

  it("parsePeerInput: npub / 64 桁の hex だけ", () => {
    expect(parsePeerInput(npubEncode(ALICE))).toBe(ALICE);
    expect(parsePeerInput(ALICE.toUpperCase())).toBe(ALICE);
    expect(parsePeerInput(nprofileEncode({ pubkey: ALICE }))).toBeNull();
    expect(parsePeerInput("")).toBeNull();
  });
});

describe("未読", () => {
  it("一覧の行の右端に未読数（aria-label に「未読 N 件」）。0 なら出さず、100 以上は 99+", () => {
    useDmSeen.setState({ first: 0, peers: { [BOB]: 1_700_000_050 } });
    seed();
    renderAt("/messages", 400);
    const [alice, bob] = rowButtons();
    // 自分の発言（やあ）は数えない
    expect(alice).toHaveAccessibleName(`${ALICE.slice(0, 10)}（未読 2 件） 元気？`);
    expect(alice).toHaveTextContent(/2$/);
    expect(bob).not.toHaveAttribute("aria-label");
    expect(bob).not.toHaveTextContent(/\d$/);

    act(() =>
      useDm
        .getState()
        .upsertMessages(
          Array.from({ length: 150 }, (_, n) => dm(`b${n + 2}`, BOB, BOB, `bob ${n}`, 1_700_001_000 + n)),
        ),
    );
    expect(rowButtons()[0]).toHaveAccessibleName(`${BOB.slice(0, 10)}（未読 150 件） bob 149`);
    expect(rowButtons()[0]).toHaveTextContent(/99\+$/);
  });

  it("会話を開くとその相手の未読だけが 0 になり、他の相手は残る。開いている間に届いた発言も未読にしない", async () => {
    useDmSeen.setState({ first: 0 });
    seed();
    renderAt("/messages", 1400);
    await userEvent.click(rowButtons()[0]);

    const [alice, bob] = rowButtons();
    expect(alice).not.toHaveAttribute("aria-label");
    expect(bob).toHaveAccessibleName(/（未読 1 件）/);
    expect(localStorage.getItem(`nostrism.dm.seen.${ME}`)).toContain(ALICE);

    // 相手の時計が進んでいても既読になる
    const future = Math.floor(Date.now() / 1000) + 3_600;
    act(() => useDm.getState().upsertMessages([dm("a4", ALICE, ALICE, "未来から", future)]));
    expect(within(screen.getByRole("region")).getByText("未来から")).toBeInTheDocument();
    expect(rowButtons()[0]).not.toHaveAttribute("aria-label");
    expect(useDmSeen.getState().peers[ALICE]).toBe(future);
    expect(rowButtons()[1]).toHaveAccessibleName(/（未読 1 件）/);
  });
});
