import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { npubEncode } from "nostr-tools/nip19";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { unixNow } from "../../lib/time";
import { type EventDraft, PublishError, publishEvent } from "../../nostr/publish";
import { eventStore } from "../../nostr/store";
import { currentSigner, useSession } from "../../signer/session";
import { installDialogPolyfill } from "../../test/dialog";
import { createTestSigner } from "../../test/fakeSigner";
import { renderWithRouter } from "../../test/renderWithRouter";
import { useToast } from "../../ui/toast";
import { openHashtagManager } from "../hashtags/hashtagManagerStore";
import { togglePinnedHashtag } from "../hashtags/pinnedHashtags";
import { sha256Hex } from "./blossom";
import { ComposeDialog } from "./ComposeDialog";
import { type ComposeRequest, openCompose, useCompose } from "./composeStore";
import { setMediaServer } from "./mediaServer";
import { DRAFT_KEY, THREAD_DRAFT_KEY, USED_HASHTAGS_KEY } from "./storage";

// 署名・送信はしない（publishEvent だけ差し替える）
vi.mock("../../nostr/publish", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/publish")>();
  return { ...actual, publishEvent: vi.fn() };
});

// NIP-98 の署名者（拡張機能を使わない）
vi.mock("../../signer/session", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../signer/session")>();
  return { ...actual, currentSigner: vi.fn() };
});

// 取り直し・発行は pinnedHashtags.test.ts。ここは呼び出しと画面だけ
vi.mock("../hashtags/pinnedHashtags", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../hashtags/pinnedHashtags")>()),
  togglePinnedHashtag: vi.fn(async () => "done" as const),
}));

vi.mock("../hashtags/hashtagManagerStore", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../hashtags/hashtagManagerStore")>()),
  openHashtagManager: vi.fn(),
}));

let meKey: Uint8Array;
let me: string;

beforeAll(() => {
  installDialogPolyfill();
});

beforeEach(() => {
  meKey = generateSecretKey();
  me = getPublicKey(meKey);
  useSession.setState({ status: "in", method: "nip07", pubkey: me });
  vi.mocked(publishEvent).mockReset();
  vi.mocked(publishEvent).mockImplementation(async (draft: EventDraft) =>
    finalizeEvent(
      { kind: draft.kind, content: draft.content, tags: draft.tags, created_at: unixNow() },
      meKey,
    ),
  );
  vi.mocked(togglePinnedHashtag).mockReset();
  vi.mocked(togglePinnedHashtag).mockResolvedValue("done");
  vi.mocked(openHashtagManager).mockClear();
});

afterEach(() => {
  // 閉じる（= ComposeDialog を unmount。連投の下書きはここで保存される）→ その後で消す。
  // 逆順だと直前のテストの連投下書きが unmount 時の保存で localStorage に戻ってしまう。
  act(() => useCompose.setState({ request: null }));
  localStorage.clear();
  useSession.setState({ status: "loading", method: null, pubkey: null });
  useToast.setState({ queue: [] });
});

/** 開いている要求の投稿シート（閉じたら消える = ComposeHost と同じ） */
function Harness() {
  const request = useCompose((s) => s.request);
  if (!request) return null;
  return (
    <ComposeDialog key={request.mode + (request.mode === "new" ? "" : request.target.id)} request={request} />
  );
}

function open(request: ComposeRequest) {
  act(() => openCompose(request));
}

function body(): HTMLTextAreaElement {
  return screen.getByRole("textbox", { name: "本文" }) as HTMLTextAreaElement;
}

function lastDraft(): EventDraft {
  const calls = vi.mocked(publishEvent).mock.calls;
  return calls[calls.length - 1][0];
}

function stored(
  content: string,
  { kind = 1, key = generateSecretKey(), tags = [] as string[][] } = {},
): NostrEvent {
  const event = finalizeEvent({ kind, created_at: unixNow(), tags, content }, key);
  eventStore.add(event);
  return event;
}

function withName(name: string): Uint8Array {
  const key = generateSecretKey();
  stored(JSON.stringify({ name }), { kind: 0, key });
  return key;
}

describe("新規", () => {
  it("「投稿」を開いて本文に focus。入力で「送信」が有効になり、下書きに入る。開き直すと戻る", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });

    expect(screen.getByRole("dialog", { name: "投稿" })).toHaveAttribute("open");
    expect(body()).toHaveFocus();
    expect(screen.getByRole("button", { name: "送信" })).toBeDisabled();

    await user.type(body(), "hello");
    expect(screen.getByRole("button", { name: "送信" })).toBeEnabled();
    expect(localStorage.getItem(DRAFT_KEY)).toBe("hello");

    act(() => useCompose.setState({ request: null }));
    expect(screen.queryByRole("dialog")).toBeNull();
    open({ mode: "new" });
    expect(body()).toHaveValue("hello");
  });

  it("「送信」で kind 1 を発行して閉じ、下書きを消す", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), "hello");
    await user.click(screen.getByRole("button", { name: "送信" }));

    expect(publishEvent).toHaveBeenCalledTimes(1);
    expect(lastDraft()).toMatchObject({ kind: 1, content: "hello" });
    expect(useCompose.getState().request).toBeNull();
    expect(localStorage.getItem(DRAFT_KEY)).toBeNull();
  });

  it("Ctrl+Enter でも送る。IME 変換中は送らない", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), "x");

    fireEvent.keyDown(body(), { key: "Enter", ctrlKey: true, isComposing: true });
    expect(publishEvent).not.toHaveBeenCalled();

    await user.keyboard("{Control>}{Enter}{/Control}");
    expect(publishEvent).toHaveBeenCalledTimes(1);
  });
});

describe("返信・引用", () => {
  it("返信: 「返信」ボタンと返信先の 1 行。tags の先頭が root の e。送ると新規の下書きも消える", async () => {
    const user = userEvent.setup();
    const target = stored("元の\n投稿", { key: withName("carol") });
    localStorage.setItem(DRAFT_KEY, "x");
    renderWithRouter(<Harness />);
    open({ mode: "reply", target });

    expect(screen.getByRole("dialog", { name: "返信" })).toBeInTheDocument();
    expect(await screen.findByText("carol: 元の 投稿")).toBeInTheDocument();
    expect(body()).toHaveValue("");

    await user.type(body(), "返信です");
    await user.click(screen.getByRole("button", { name: "返信" }));

    expect(lastDraft().tags[0]).toEqual(["e", target.id, "", "root", target.pubkey]);
    expect(localStorage.getItem(DRAFT_KEY)).toBeNull();
  });

  it("引用: 本文が空でも「引用」を押せる。「引用元」カード、content は nostr:nevent1…", async () => {
    const user = userEvent.setup();
    const target = stored("引用される投稿");
    renderWithRouter(<Harness />);
    open({ mode: "quote", target });

    expect(screen.getByText("引用元")).toBeInTheDocument();
    expect(screen.getByText("引用される投稿")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "引用" }));

    expect(lastDraft().content).toMatch(/^nostr:nevent1/);
    expect(lastDraft().tags[0]).toEqual(["q", target.id, "", target.pubkey]);
  });
});

it("センシティブ指定: ON で理由欄が出て、送ると content-warning に理由が入る", async () => {
  const user = userEvent.setup();
  renderWithRouter(<Harness />);
  open({ mode: "new" });

  await user.click(screen.getByRole("button", { name: "センシティブ指定" }));
  const toggle = screen.getByRole("button", { name: "センシティブ: ON" });
  expect(toggle).toHaveAttribute("aria-pressed", "true");
  await user.type(screen.getByRole("textbox", { name: "センシティブの理由" }), "spoiler");
  await user.type(body(), "a");
  await user.click(screen.getByRole("button", { name: "送信" }));

  expect(lastDraft().tags).toContainEqual(["content-warning", "spoiler"]);
});

describe("閉じる", () => {
  it("書きかけの ✗ は確認。「キャンセル」で開いたまま、「破棄する」で閉じて下書きも消す", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), "abc");

    await user.click(screen.getByRole("button", { name: "閉じる" }));
    const confirm = screen.getByRole("dialog", { name: "入力内容を破棄しますか？" });
    await user.click(within(confirm).getByRole("button", { name: "キャンセル" }));
    expect(screen.queryByRole("dialog", { name: "入力内容を破棄しますか？" })).toBeNull();
    expect(screen.getByRole("dialog", { name: "投稿" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "閉じる" }));
    await user.click(screen.getByRole("button", { name: "破棄する" }));
    expect(useCompose.getState().request).toBeNull();
    expect(localStorage.getItem(DRAFT_KEY)).toBeNull();
  });

  it("返信の破棄では新規投稿の下書きを残す", async () => {
    const user = userEvent.setup();
    localStorage.setItem(DRAFT_KEY, "keep");
    renderWithRouter(<Harness />);
    open({ mode: "reply", target: stored("元") });
    await user.type(body(), "abc");
    await user.click(screen.getByRole("button", { name: "閉じる" }));
    await user.click(screen.getByRole("button", { name: "破棄する" }));

    expect(useCompose.getState().request).toBeNull();
    expect(localStorage.getItem(DRAFT_KEY)).toBe("keep");
  });

  it("本文が空の ✗ はすぐ閉じる。cancel（Esc / 戻る）は ✗ と同じ", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.click(screen.getByRole("button", { name: "閉じる" }));
    expect(useCompose.getState().request).toBeNull();

    open({ mode: "new" });
    await user.type(body(), "abc");
    const cancel = new Event("cancel", { cancelable: true });
    fireEvent(screen.getByRole("dialog", { name: "投稿" }), cancel);
    expect(cancel.defaultPrevented).toBe(true);
    expect(screen.getByRole("dialog", { name: "入力内容を破棄しますか？" })).toBeInTheDocument();
  });
});

describe("送信の失敗・キャンセル", () => {
  it("署名に失敗したら文言を出し、本文を残して送信ボタンを戻す", async () => {
    const user = userEvent.setup();
    vi.mocked(publishEvent).mockRejectedValueOnce(new PublishError("sign-failed"));
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), "abc");
    await user.click(screen.getByRole("button", { name: "送信" }));

    expect(screen.getByRole("alert")).toHaveTextContent(
      "投稿に失敗しました。添付はそのままなので、もう一度お試しください。",
    );
    expect(screen.getByRole("dialog", { name: "投稿" })).toBeInTheDocument();
    expect(body()).toHaveValue("abc");
    expect(screen.getByRole("button", { name: "送信" })).toBeEnabled();
  });

  it("署名待ちの間は「投稿中…」と「キャンセル」。キャンセルで編集に戻り、signal が中止される", async () => {
    const user = userEvent.setup();
    let signal: AbortSignal | undefined;
    vi.mocked(publishEvent).mockImplementationOnce((_draft, opts) => {
      signal = opts?.signal;
      return new Promise(() => {});
    });
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), "abc");
    await user.click(screen.getByRole("button", { name: "送信" }));

    expect(screen.getByText("投稿中…")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "閉じる" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "キャンセル" }));

    expect(signal?.aborted).toBe(true);
    expect(screen.queryByText("投稿中…")).toBeNull();
    expect(screen.getByRole("button", { name: "送信" })).toBeEnabled();
    expect(body()).toHaveValue("abc");
  });
});

describe("入力補完", () => {
  it("@al で 120ms 後にメンション候補、選ぶと nostr:npub1… に置き換える", async () => {
    const user = userEvent.setup();
    const alice = getPublicKey(withName("alice"));
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), "hi @al");

    expect(await screen.findByText("メンション候補")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /alice/ }));
    expect(body()).toHaveValue(`hi nostr:${npubEncode(alice)} `);
  });

  it("最近のタグ・📌 ピン留め・入力中の候補", async () => {
    const user = userEvent.setup();
    localStorage.setItem(
      USED_HASHTAGS_KEY,
      JSON.stringify([
        { tag: "nostr", lastUsed: 2 },
        { tag: "bitcoin", lastUsed: 1 },
      ]),
    );
    stored("", {
      kind: 30015,
      key: meKey,
      tags: [
        ["d", "pinned"],
        ["t", "zap"],
      ],
    });
    renderWithRouter(<Harness />);
    open({ mode: "new" });

    const recent = screen.getByText("最近のタグ").nextElementSibling as HTMLElement;
    expect(
      within(recent)
        .getAllByRole("button")
        .map((b) => b.textContent),
    ).toEqual(["#nostr", "#bitcoin"]);
    const pinned = screen.getByText("📌 ピン留め").nextElementSibling as HTMLElement;
    expect(within(pinned).getByRole("button", { name: "#zap" })).toBeInTheDocument();

    await user.type(body(), "#bi");
    const suggest = screen.getByText("候補").nextElementSibling as HTMLElement;
    await user.click(within(suggest).getByRole("button", { name: "#bitcoin" }));
    expect(body()).toHaveValue("#bitcoin ");
  });

  it("タグチップの長押し / 右クリックで tag_pin / tag_unpin（#536）", async () => {
    const user = userEvent.setup();
    localStorage.setItem(USED_HASHTAGS_KEY, JSON.stringify([{ tag: "bitcoin", lastUsed: 1 }]));
    stored("", {
      kind: 30015,
      key: meKey,
      tags: [
        ["d", "pinned"],
        ["t", "zap"],
      ],
    });
    renderWithRouter(<Harness />);
    open({ mode: "new" });

    const pinned = screen.getByText("📌 ピン留め").nextElementSibling as HTMLElement;
    const zapChip = within(pinned).getByRole("button", { name: "#zap" });
    fireEvent.contextMenu(zapChip);
    await user.click(screen.getByRole("menuitem", { name: "ピン留めを解除" }));
    expect(vi.mocked(togglePinnedHashtag)).toHaveBeenCalledWith(me, "zap", false);
    // 長押し / 右クリックに続くクリックはタグの挿入として扱わない
    expect(body()).toHaveValue("");

    const recent = screen.getByText("最近のタグ").nextElementSibling as HTMLElement;
    const bitcoinChip = within(recent).getByRole("button", { name: "#bitcoin" });
    fireEvent.contextMenu(bitcoinChip);
    await user.click(screen.getByRole("menuitem", { name: "ピン留め" }));
    expect(vi.mocked(togglePinnedHashtag)).toHaveBeenCalledWith(me, "bitcoin", true);
  });

  it("ピン留めが15件なら発行せず案内する", async () => {
    const user = userEvent.setup();
    localStorage.setItem(USED_HASHTAGS_KEY, JSON.stringify([{ tag: "extra", lastUsed: 1 }]));
    stored("", {
      kind: 30015,
      key: meKey,
      tags: [["d", "pinned"], ...Array.from({ length: 15 }, (_, i) => ["t", `t${i}`])],
    });
    renderWithRouter(<Harness />);
    open({ mode: "new" });

    const recent = screen.getByText("最近のタグ").nextElementSibling as HTMLElement;
    fireEvent.contextMenu(within(recent).getByRole("button", { name: "#extra" }));
    await user.click(screen.getByRole("menuitem", { name: "ピン留め" }));

    expect(vi.mocked(togglePinnedHashtag)).not.toHaveBeenCalled();
    expect(useToast.getState().queue).toEqual(["ピン留めは15件までです。整理画面で整理してください。"]);
  });

  it("「整理…」で整理画面を開く", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });

    await user.click(screen.getByRole("button", { name: "整理…" }));
    expect(vi.mocked(openHashtagManager)).toHaveBeenCalledTimes(1);
  });

  it(":ca で自分のカスタム絵文字の候補、選ぶと :cat: に置き換える", async () => {
    const user = userEvent.setup();
    stored("", { kind: 10030, key: meKey, tags: [["emoji", "cat", "https://e/cat.png"]] });
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), ":ca");

    expect(screen.getByText("絵文字候補")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: ":cat:" }));
    expect(body()).toHaveValue(":cat: ");
  });
});

describe("絵文字を挿入（#459）", () => {
  it("ピッカーで選んだ絵文字をカーソル位置に入れる", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), "ab");
    body().setSelectionRange(1, 1);
    fireEvent.select(body());

    await user.click(screen.getByRole("button", { name: "絵文字を挿入" }));
    const picker = screen.getByRole("dialog", { name: "リアクション" });
    await user.click(within(picker).getByRole("button", { name: "😄" }));

    expect(screen.queryByRole("dialog", { name: "リアクション" })).toBeNull();
    expect(body()).toHaveValue("a😄b");
    expect(publishEvent).not.toHaveBeenCalled();
  });

  it("カスタム絵文字は後ろに空白を付けて :cat: を入れる", async () => {
    const user = userEvent.setup();
    stored("", { kind: 10030, key: meKey, tags: [["emoji", "cat", "https://e/cat.png"]] });
    renderWithRouter(<Harness />);
    open({ mode: "new" });

    await user.click(screen.getByRole("button", { name: "絵文字を挿入" }));
    await user.click(
      within(screen.getByRole("dialog", { name: "リアクション" })).getByRole("button", { name: ":cat:" }),
    );
    expect(body()).toHaveValue(":cat: ");
  });

  it("ピッカーの cancel / close では投稿シートを閉じない", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), "x");

    await user.click(screen.getByRole("button", { name: "絵文字を挿入" }));
    fireEvent(
      screen.getByRole("dialog", { name: "リアクション" }),
      new Event("cancel", { cancelable: true }),
    );
    expect(screen.queryByRole("dialog", { name: "リアクション" })).toBeNull();

    await user.click(screen.getByRole("button", { name: "絵文字を挿入" }));
    fireEvent(screen.getByRole("dialog", { name: "リアクション" }), new Event("close"));
    expect(screen.queryByRole("dialog", { name: "リアクション" })).toBeNull();

    expect(screen.queryByRole("dialog", { name: "入力内容を破棄しますか？" })).toBeNull();
    expect(useCompose.getState().request).toEqual({ mode: "new" });
    expect(body()).toHaveValue("x");
  });
});

describe("連投（#533）", () => {
  it("返信・引用では「連投に追加」ボタンが出ない", () => {
    renderWithRouter(<Harness />);
    open({ mode: "reply", target: stored("元") });
    expect(screen.queryByRole("button", { name: "連投に追加" })).toBeNull();

    act(() => useCompose.setState({ request: null }));
    open({ mode: "quote", target: stored("元") });
    expect(screen.queryByRole("button", { name: "連投に追加" })).toBeNull();
  });

  it("本文が空なら無効。追加で入力欄が空になり一覧に出る。件数表示も更新される", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    expect(screen.getByRole("button", { name: "連投に追加" })).toBeDisabled();

    await user.type(body(), "1つ目");
    await user.click(screen.getByRole("button", { name: "連投に追加" }));
    expect(body()).toHaveValue("");
    const list = screen.getByRole("list", { name: "連投" });
    expect(within(list).getByText("1つ目")).toBeInTheDocument();
    expect(screen.getByText("連投 2")).toBeInTheDocument();
  });

  it("段落を押すと入力欄に戻り、書いていた本文はその段落が居た位置へ積む", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), "1つ目");
    await user.click(screen.getByRole("button", { name: "連投に追加" }));
    await user.type(body(), "2つ目");

    const list = screen.getByRole("list", { name: "連投" });
    await user.click(within(list).getByRole("button", { name: "1つ目" }));
    expect(body()).toHaveValue("1つ目");
    expect(within(list).getByText("2つ目")).toBeInTheDocument();
  });

  it("✗ でその段落を取り消す", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), "1つ目");
    await user.click(screen.getByRole("button", { name: "連投に追加" }));
    await user.type(body(), "2つ目");
    await user.click(screen.getByRole("button", { name: "連投に追加" }));
    await user.type(body(), "3つ目");

    const list = screen.getByRole("list", { name: "連投" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(3);
    await user.click(within(list).getAllByRole("button", { name: "この段落を取り消す" })[0]);
    expect(within(list).getAllByRole("listitem")).toHaveLength(2);
    expect(within(list).queryByText("1つ目")).toBeNull();
  });

  it("閉じて開くと連投の下書きが戻る（本文の位置も含めて）", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), "1つ目");
    await user.click(screen.getByRole("button", { name: "連投に追加" }));
    await user.type(body(), "2つ目");

    act(() => useCompose.setState({ request: null }));
    expect(screen.queryByRole("dialog")).toBeNull();

    open({ mode: "new" });
    expect(body()).toHaveValue("2つ目");
    const list = screen.getByRole("list", { name: "連投" });
    expect(within(list).getByText("1つ目")).toBeInTheDocument();
    expect(screen.getByText("連投 2")).toBeInTheDocument();
  });

  it("送信ボタンは「連投」。先頭から順に kind 1 を発行し、2 件目に root の e。送信成功で下書きが消える", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), "1つ目");
    await user.click(screen.getByRole("button", { name: "連投に追加" }));
    await user.type(body(), "2つ目");

    await user.click(screen.getByRole("button", { name: "連投" }));

    expect(publishEvent).toHaveBeenCalledTimes(2);
    const calls = vi.mocked(publishEvent).mock.calls;
    expect(calls[0][0]).toMatchObject({ kind: 1, content: "1つ目" });
    const first = await vi.mocked(publishEvent).mock.results[0].value;
    expect(calls[1][0]).toMatchObject({ kind: 1, content: "2つ目" });
    expect(calls[1][0].tags[0]).toEqual(["e", first.id, "", "root", me]);
    expect(useCompose.getState().request).toBeNull();
    expect(localStorage.getItem(DRAFT_KEY)).toBeNull();
    expect(localStorage.getItem(THREAD_DRAFT_KEY)).toBeNull();
  });

  it("途中で失敗したら残りは送らない。送れた段落は外し、残りを新しい連投の下書きにする", async () => {
    const user = userEvent.setup();
    vi.mocked(publishEvent).mockImplementationOnce(async (draft: EventDraft) =>
      finalizeEvent(
        { kind: draft.kind, content: draft.content, tags: draft.tags, created_at: unixNow() },
        meKey,
      ),
    );
    vi.mocked(publishEvent).mockRejectedValueOnce(new PublishError("sign-failed"));
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), "1つ目");
    await user.click(screen.getByRole("button", { name: "連投に追加" }));
    await user.type(body(), "2つ目");
    await user.click(screen.getByRole("button", { name: "連投に追加" }));
    await user.type(body(), "3つ目");
    await user.click(screen.getByRole("button", { name: "連投" }));

    await waitFor(() => expect(publishEvent).toHaveBeenCalledTimes(2));
    expect(useCompose.getState().request).not.toBeNull();
    // 「1つ目」は送れたので外れ、失敗した「2つ目」が本文へ戻り（編集中の行として一覧にも出る）、
    // 「3つ目」だけが段落として一覧に残る
    expect(body()).toHaveValue("2つ目");
    const list = screen.getByRole("list", { name: "連投" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(2);
    expect(within(list).getByText("3つ目")).toBeInTheDocument();
    expect(within(list).queryByText("1つ目")).toBeNull();
    expect(useToast.getState().queue).toContain(
      "1件目までは送信済み。残りは新しい連投として下書きに残しました",
    );
  });
});

describe("添付（画像・動画）", () => {
  const fetchMock = vi.fn<typeof fetch>();
  const UPLOADED_URL = "https://media.example/abc.webp";
  const originalCreate = URL.createObjectURL;
  const originalRevoke = URL.revokeObjectURL;
  let revoked: string[];

  beforeEach(() => {
    revoked = [];
    let n = 0;
    // jsdom には blob: URL が無い
    URL.createObjectURL = () => {
      n += 1;
      return `blob:test/${n}`;
    };
    URL.revokeObjectURL = (url: string) => {
      revoked.push(url);
    };
    vi.mocked(currentSigner).mockReturnValue(createTestSigner().signer);
    fetchMock.mockReset();
    // 実サーバーへは送らない（ディスカバリ → アップロードの 2 回）
    fetchMock.mockImplementation(async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      // [#895] 最終 URL の確認（HEAD）は配られている扱い
      if (init?.method === "HEAD") return new Response(null, { headers: { "Content-Type": "image/webp" } });
      if (url.endsWith("/.well-known/nostr/nip96.json")) {
        return new Response(JSON.stringify({ api_url: "https://api.example/upload" }));
      }
      return new Response(
        JSON.stringify({
          status: "success",
          nip94_event: {
            tags: [
              ["url", UPLOADED_URL],
              ["m", "image/webp"],
              ["x", "ab"],
              ["dim", "800x600"],
            ],
          },
        }),
      );
    });
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    URL.createObjectURL = originalCreate;
    URL.revokeObjectURL = originalRevoke;
    vi.unstubAllGlobals();
    setMediaServer(null);
  });

  function fileInput(): HTMLInputElement {
    const input = document.querySelector<HTMLInputElement>('input[type="file"]');
    if (!input) throw new Error("file input not found");
    return input;
  }

  function png(name = "a.png"): File {
    return new File(["x".repeat(2048)], name, { type: "image/png" });
  }

  it("「画像・動画を添付」の input は image/* と video/* の複数選択。選ぶとプレビュー、✗ で外せる", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    expect(fileInput()).toHaveAttribute("accept", "image/*,video/*");
    expect(fileInput()).toHaveAttribute("multiple");
    expect(screen.getByRole("button", { name: "画像・動画を添付" })).toBeInTheDocument();

    await user.upload(fileInput(), [png(), new File(["v"], "v.mp4", { type: "video/mp4" })]);

    const list = screen.getByRole("list", { name: "添付" });
    expect(within(list).getByRole("img", { name: "添付画像" })).toHaveAttribute("src", "blob:test/1");
    expect(within(list).getByLabelText("添付動画")).toHaveAttribute("src", "blob:test/2");
    // 本文が空でも添付があれば送れる
    expect(screen.getByRole("button", { name: "送信" })).toBeEnabled();
    // 圧縮できない環境（jsdom）では元の容量
    expect(await within(list).findByText("2KB")).toBeInTheDocument();

    await user.click(within(list).getAllByRole("button", { name: "削除" })[0]);
    expect(within(list).queryByRole("img", { name: "添付画像" })).toBeNull();
    expect(revoked).toEqual(["blob:test/1"]);
    await user.click(within(list).getByRole("button", { name: "削除" }));
    expect(screen.queryByRole("list", { name: "添付" })).toBeNull();
    expect(screen.getByRole("button", { name: "送信" })).toBeDisabled();
  });

  describe("向きの編集", () => {
    const bitmap = { width: 600, height: 800, close: vi.fn() };

    beforeEach(() => {
      // jsdom には createImageBitmap と canvas の描画が無い
      vi.stubGlobal(
        "createImageBitmap",
        vi.fn(async () => bitmap),
      );
      vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockImplementation(() => {
        return {
          setTransform: vi.fn(),
          drawImage: vi.fn(),
          getImageData: (_x: number, _y: number, w: number, h: number) => ({
            data: new Uint8ClampedArray(w * h * 4).fill(128),
          }),
        } as unknown as CanvasRenderingContext2D;
      });
      vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation((callback, type) => {
        callback(new Blob(["encoded"], { type: type ?? "image/png" }));
      });
    });

    afterEach(() => {
      vi.restoreAllMocks();
    });

    it("サムネイルをクリックするとライトボックスで開く。複数枚なら前後に移れる", async () => {
      const user = userEvent.setup();
      renderWithRouter(<Harness />);
      open({ mode: "new" });
      await user.upload(fileInput(), [png("a.png"), png("b.png")]);
      const list = screen.getByRole("list", { name: "添付" });
      expect(screen.queryByRole("dialog", { name: "画像" })).toBeNull();

      await user.click(within(list).getAllByRole("button", { name: "添付画像を開く" })[1]);

      const lightbox = screen.getByRole("dialog", { name: "画像" });
      expect(lightbox.querySelector("img")).toHaveAttribute("src", "blob:test/2");
      expect(within(lightbox).getByText("2 / 2")).toBeInTheDocument();
      await user.click(within(lightbox).getByRole("button", { name: "前の画像" }));
      expect(lightbox.querySelector("img")).toHaveAttribute("src", "blob:test/1");
      // 編集メニューは添付のとき（読めて、GIF ではない）に出る
      expect(await within(lightbox).findByRole("toolbar", { name: "画像の向きを編集" })).toBeInTheDocument();
    });

    it("編集するとその添付だけ加工し直し、サムネイルとライトボックスが編集後の画像に替わる。元に戻すで戻る", async () => {
      const user = userEvent.setup();
      renderWithRouter(<Harness />);
      open({ mode: "new" });
      await user.upload(fileInput(), [png("a.png"), png("b.png")]);
      const list = screen.getByRole("list", { name: "添付" });
      const thumbs = () => within(list).getAllByRole("img", { name: "添付画像" });
      expect(thumbs().map((i) => i.getAttribute("src"))).toEqual(["blob:test/1", "blob:test/2"]);

      await user.click(within(list).getAllByRole("button", { name: "添付画像を開く" })[0]);
      const lightbox = screen.getByRole("dialog", { name: "画像" });
      await user.click(await within(lightbox).findByRole("button", { name: "右に回転" }));

      // 作り直した画像（3 つ目の blob: URL）に替わる。もう片方は変わらない
      await waitFor(() =>
        expect(thumbs().map((i) => i.getAttribute("src"))).toEqual(["blob:test/3", "blob:test/2"]),
      );
      expect(lightbox.querySelector("img")).toHaveAttribute("src", "blob:test/3");
      // 古いプレビュー（元のまま）は外すときに解放する。編集後のものを差し替えるときに解放するのは編集後だけ
      expect(revoked).toEqual([]);

      await user.click(within(lightbox).getByRole("button", { name: "左右反転" }));
      await waitFor(() => expect(thumbs()[0]).toHaveAttribute("src", "blob:test/4"));
      expect(revoked).toEqual(["blob:test/3"]);

      await user.click(within(lightbox).getByRole("button", { name: "元に戻す" }));
      await waitFor(() => expect(thumbs()[0]).toHaveAttribute("src", "blob:test/1"));
      expect(revoked).toEqual(["blob:test/3", "blob:test/4"]);
    });

    it("編集した向きで画像を上げる（dim は編集後の寸法）", async () => {
      const user = userEvent.setup();
      renderWithRouter(<Harness />);
      open({ mode: "new" });
      await user.upload(fileInput(), png());
      const list = screen.getByRole("list", { name: "添付" });
      await user.click(within(list).getByRole("button", { name: "添付画像を開く" }));
      const lightbox = screen.getByRole("dialog", { name: "画像" });
      await user.click(await within(lightbox).findByRole("button", { name: "右に回転" }));
      await waitFor(() => expect(within(list).getByRole("img")).toHaveAttribute("src", "blob:test/2"));
      await user.click(within(lightbox).getByRole("button", { name: "閉じる" }));
      expect(screen.queryByRole("dialog", { name: "画像" })).toBeNull();

      await user.click(screen.getByRole("button", { name: "送信" }));
      await waitFor(() => expect(publishEvent).toHaveBeenCalledTimes(1));
      // サーバーが dim を返さない場合の手元の値を見るのは toPostMedia の責務。ここでは作り直した画像を上げたこと
      const upload = fetchMock.mock.calls[1];
      const form = upload[1]?.body as FormData;
      expect((form.get("file") as File).size).toBe("encoded".length);
    });

    it("GIF は編集メニューを出さない（ライトボックスで見ることはできる）", async () => {
      const user = userEvent.setup();
      renderWithRouter(<Harness />);
      open({ mode: "new" });
      await user.upload(fileInput(), new File(["GIF89a"], "a.gif", { type: "image/gif" }));
      const list = screen.getByRole("list", { name: "添付" });
      // 圧縮の結果が揃うまで待つ（編集できるかはそこで決まる）
      await waitFor(() => expect(within(list).getByText(/B$/)).toBeInTheDocument());
      await user.click(within(list).getByRole("button", { name: "添付画像を開く" }));
      const lightbox = screen.getByRole("dialog", { name: "画像" });
      expect(lightbox.querySelector("img")).toHaveAttribute("src", "blob:test/1");
      expect(within(lightbox).queryByRole("toolbar")).toBeNull();
    });
  });

  it("送信で NIP-96 へアップロードし、本文の後ろに URL・tags の末尾に imeta を入れて発行する", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.type(body(), "写真");
    await user.upload(fileInput(), png());
    await user.click(screen.getByRole("button", { name: "送信" }));

    await waitFor(() => expect(publishEvent).toHaveBeenCalledTimes(1));
    expect(lastDraft().content).toBe(`写真\n${UPLOADED_URL}`);
    expect(lastDraft().tags.at(-1)).toEqual([
      "imeta",
      `url ${UPLOADED_URL}`,
      "m image/webp",
      "dim 800x600",
      "x ab",
    ]);
    // 既定の一覧の先頭（nostr.build）から試す。Authorization は NIP-98
    const calls = fetchMock.mock.calls;
    expect(calls[0][0]).toBe("https://nostr.build/.well-known/nostr/nip96.json");
    expect(new Headers(calls[1][1]?.headers).get("Authorization")).toMatch(/^Nostr /);
    expect(useCompose.getState().request).toBeNull();
  });

  it("設定で選んだサーバーへ送る", async () => {
    const user = userEvent.setup();
    setMediaServer({ url: "https://nostr.build", protocol: "nip96" });
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.upload(fileInput(), png());
    await user.click(screen.getByRole("button", { name: "送信" }));

    await waitFor(() => expect(publishEvent).toHaveBeenCalledTimes(1));
    expect(fetchMock.mock.calls[0][0]).toBe("https://nostr.build/.well-known/nostr/nip96.json");
    expect(lastDraft().content).toBe(UPLOADED_URL);
  });

  describe("[#873] 送信先（この投稿だけ）", () => {
    function targetSelect(): HTMLSelectElement {
      return screen.getByRole("combobox", { name: "送信先" }) as HTMLSelectElement;
    }

    it("画像・動画を添付したときだけ出る。既定は設定の送信先で、ヒントと方式のバッジが付く", async () => {
      const user = userEvent.setup();
      setMediaServer({ url: "https://nostpic.com", protocol: "nip96" });
      renderWithRouter(<Harness />);
      open({ mode: "new" });
      expect(screen.queryByRole("combobox", { name: "送信先" })).toBeNull();

      await user.upload(fileInput(), png());
      expect(targetSelect()).toHaveValue("nip96 https://nostpic.com");
      expect(targetSelect()).toHaveAccessibleDescription(
        "この投稿だけ送信先を変えます（設定は変わりません）",
      );
      // 方式の見出しで分ける
      const groups = within(targetSelect()).getAllByRole("group");
      expect(groups.map((g) => g.getAttribute("label"))).toEqual(["NIP-96", "Blossom"]);
      expect(within(groups[1]).getByRole("option", { name: "blossom.band" })).toBeInTheDocument();
      expect(targetSelect().parentElement).toHaveTextContent("NIP-96");

      await user.click(
        within(screen.getByRole("list", { name: "添付" })).getByRole("button", { name: "削除" }),
      );
      expect(screen.queryByRole("combobox", { name: "送信先" })).toBeNull();
    });

    it("Blossom を選ぶと <server>/upload へ PUT して投稿する（設定は変わらない）", async () => {
      const user = userEvent.setup();
      fetchMock.mockImplementation(async (input, init) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
        if (url === "https://blossom.band/upload" && init?.method === "PUT") {
          const body = init.body as Blob;
          return new Response(
            JSON.stringify({ url: UPLOADED_URL, sha256: await sha256Hex(body), size: body.size }),
          );
        }
        // [#895] 成功後の確認（HEAD）は配られている扱い
        if (url === UPLOADED_URL && init?.method === "HEAD") {
          return new Response(null, { headers: { "Content-Type": "image/webp" } });
        }
        throw new TypeError("unexpected request");
      });
      renderWithRouter(<Harness />);
      open({ mode: "new" });
      await user.upload(fileInput(), png());
      await user.selectOptions(targetSelect(), "blossom https://blossom.band");
      expect(targetSelect().parentElement).toHaveTextContent("Blossom");
      await user.click(screen.getByRole("button", { name: "送信" }));

      await waitFor(() => expect(publishEvent).toHaveBeenCalledTimes(1));
      expect(fetchMock.mock.calls.map(([input, init]) => [input, init?.method])).toEqual([
        ["https://blossom.band/upload", "PUT"],
        [UPLOADED_URL, "HEAD"],
      ]);
      const auth = new Headers(fetchMock.mock.calls[0][1]?.headers).get("Authorization") ?? "";
      expect(auth).toMatch(/^Nostr [A-Za-z0-9_-]+$/);
      expect(lastDraft().content).toBe(UPLOADED_URL);
      expect(localStorage.getItem("nostrism.media.server")).toBeNull();
    });

    it("閉じて開き直すと設定の送信先に戻る", async () => {
      const user = userEvent.setup();
      setMediaServer({ url: "https://nostpic.com", protocol: "nip96" });
      renderWithRouter(<Harness />);
      open({ mode: "new" });
      await user.upload(fileInput(), png());
      await user.selectOptions(targetSelect(), "blossom https://blossom.band");
      expect(targetSelect()).toHaveValue("blossom https://blossom.band");

      act(() => useCompose.setState({ request: null }));
      open({ mode: "new" });
      await user.upload(fileInput(), png());
      expect(targetSelect()).toHaveValue("nip96 https://nostpic.com");
    });
  });

  it("アップロード中は「画像 n/m アップロード中…」。失敗したら投稿せず文言を出し、添付を残す", async () => {
    const user = userEvent.setup();
    let fail: (e: Error) => void = () => {};
    fetchMock.mockImplementation(
      () =>
        new Promise<Response>((_resolve, reject) => {
          fail = reject;
        }),
    );
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.upload(fileInput(), png());
    await user.click(screen.getByRole("button", { name: "送信" }));

    expect(await screen.findByText("画像 0/1 アップロード中…")).toBeInTheDocument();
    // どのサーバーも失敗
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));
    act(() => fail(new TypeError("Failed to fetch")));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "投稿に失敗しました。添付はそのままなので、もう一度お試しください。",
    );
    expect(publishEvent).not.toHaveBeenCalled();
    expect(screen.getByRole("img", { name: "添付画像" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "送信" })).toBeEnabled();
  });

  it("アップロード中の「キャンセル」で編集に戻る（添付は残る）", async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation(
      (_input, init) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => reject(init.signal?.reason));
        }),
    );
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.upload(fileInput(), png());
    await user.click(screen.getByRole("button", { name: "送信" }));
    expect(await screen.findByText("画像 0/1 アップロード中…")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "キャンセル" }));
    expect(screen.queryByText(/アップロード中/)).toBeNull();
    expect(screen.getByRole("img", { name: "添付画像" })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("alert")).toBeNull());
    expect(publishEvent).not.toHaveBeenCalled();
  });

  it("貼り付け・ドロップでも添付する（画像・動画以外は無視）", () => {
    renderWithRouter(<Harness />);
    open({ mode: "new" });

    fireEvent.paste(body(), { clipboardData: { files: [png()], types: ["Files"] } });
    expect(screen.getByRole("img", { name: "添付画像" })).toBeInTheDocument();

    fireEvent.drop(screen.getByRole("dialog", { name: "投稿" }), {
      dataTransfer: {
        files: [new File(["v"], "v.mp4", { type: "video/mp4" }), new File(["%PDF"], "a.pdf")],
        types: ["Files"],
      },
    });
    expect(screen.getByLabelText("添付動画")).toBeInTheDocument();
    expect(within(screen.getByRole("list", { name: "添付" })).getAllByRole("listitem")).toHaveLength(2);
  });

  it("添付だけでも ✗ は破棄の確認。破棄で blob: URL を解放する", async () => {
    const user = userEvent.setup();
    renderWithRouter(<Harness />);
    open({ mode: "new" });
    await user.upload(fileInput(), png());

    await user.click(screen.getByRole("button", { name: "閉じる" }));
    await user.click(screen.getByRole("button", { name: "破棄する" }));
    expect(useCompose.getState().request).toBeNull();
    expect(revoked).toEqual(["blob:test/1"]);
  });
});
