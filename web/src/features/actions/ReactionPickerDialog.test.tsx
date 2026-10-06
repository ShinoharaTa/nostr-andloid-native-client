import { createHash } from "node:crypto";
import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { unixNow } from "../../lib/time";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { installDialogPolyfill } from "../../test/dialog";
import { renderWithRouter } from "../../test/renderWithRouter";
import { ReactionPickerDialog } from "./ReactionPickerDialog";
import { RECENT_EMOJIS_KEY } from "./reactionPrefs";

// 全絵文字（emojibase-data）の読み込みは #684 の別テスト（emojiCatalog.test.ts）で見る。ここでは厳選リストの
// フォールバックのまま固定して、タブ・検索・選択の挙動だけを検証する（解決しない Promise = 読み込み中のまま）
vi.mock("./emojiCatalog", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./emojiCatalog")>()),
  loadEmojiCatalog: vi.fn(() => new Promise(() => {})),
}));

let meKey: Uint8Array;

beforeAll(() => {
  installDialogPolyfill();
});

beforeEach(() => {
  meKey = generateSecretKey();
  useSession.setState({ status: "in", method: "nip07", pubkey: getPublicKey(meKey) });
  // 自分のカスタム絵文字 1 件（kind:10030 直下）と「最近」1 件
  eventStore.add(
    finalizeEvent(
      { kind: 10030, created_at: unixNow(), tags: [["emoji", "cat", "https://e/cat.png"]], content: "" },
      meKey,
    ),
  );
  localStorage.setItem(
    RECENT_EMOJIS_KEY,
    JSON.stringify([{ content: "🔥", imageUrl: null, lastUsed: unixNow(), uses: 1 }]),
  );
});

afterEach(() => {
  localStorage.clear();
  useSession.setState({ status: "loading", method: null, pubkey: null });
});

function open(target?: NostrEvent) {
  const onPick = vi.fn();
  const onClose = vi.fn();
  renderWithRouter(<ReactionPickerDialog target={target} onPick={onPick} onClose={onClose} />);
  return { onPick, onClose, dialog: screen.getByRole("dialog", { name: "リアクション" }) };
}

function sectionTitles(): string[] {
  return screen.getAllByRole("heading", { level: 3 }).map((h) => h.textContent ?? "");
}

it("検索なしは「最近」→「カスタム絵文字」→ カテゴリタブ（先頭タブのグリッド）", () => {
  open();
  expect(sectionTitles()).toEqual(["最近", "カスタム絵文字", "表情"]);
  expect(
    within(screen.getByRole("region", { name: "最近" })).getByRole("button", { name: "🔥" }),
  ).toBeVisible();
  const tabs = screen.getAllByRole("tab");
  expect(tabs.map((t) => t.textContent)).toEqual([
    "表情",
    "手・ジェスチャー",
    "ハート・感情",
    "動物・自然",
    "食べ物・飲み物",
    "アクティビティ・記号",
  ]);
  expect(tabs[0]).toHaveAttribute("aria-selected", "true");
});

it("タブを切り替えると、そのカテゴリのグリッドに変わる", async () => {
  const user = userEvent.setup();
  open();
  expect(
    within(screen.getByRole("region", { name: "表情" })).getByRole("button", { name: "😄" }),
  ).toBeVisible();

  await user.click(screen.getByRole("tab", { name: "動物・自然" }));
  expect(screen.queryByRole("region", { name: "表情" })).toBeNull();
  expect(
    within(screen.getByRole("region", { name: "動物・自然" })).getByRole("button", { name: "🐶" }),
  ).toBeVisible();
  expect(screen.getByRole("tab", { name: "動物・自然" })).toHaveAttribute("aria-selected", "true");
  expect(screen.getByRole("tab", { name: "表情" })).toHaveAttribute("aria-selected", "false");
});

it("検索すると「カスタム」「絵文字」に絞る。無ければ「一致する絵文字がありません」", async () => {
  const user = userEvent.setup();
  open();
  const search = screen.getByRole("searchbox", { name: "絵文字を検索" });

  await user.type(search, "cat");
  expect(
    within(screen.getByRole("region", { name: "カスタム" })).getByRole("button", { name: ":cat:" }),
  ).toBeVisible();

  await user.clear(search);
  await user.type(search, "わらい");
  expect(screen.queryByRole("region", { name: "カスタム" })).toBeNull();
  expect(
    within(screen.getByRole("region", { name: "絵文字" })).getByRole("button", { name: "😄" }),
  ).toBeVisible();

  await user.clear(search);
  await user.type(search, "zzzz");
  expect(screen.getByText("一致する絵文字がありません")).toBeVisible();
});

it("Unicode を選ぶと onPick(文字, null) して閉じる", async () => {
  const user = userEvent.setup();
  const { onPick, onClose } = open();
  await user.click(screen.getByRole("button", { name: "😄" }));
  expect(onPick).toHaveBeenCalledWith("😄", null);
  expect(onClose).toHaveBeenCalled();
});

it("カスタム絵文字を選ぶと onPick(:code:, 画像 URL)", async () => {
  const user = userEvent.setup();
  const { onPick } = open();
  const custom = within(screen.getByRole("region", { name: "カスタム絵文字" })).getByRole("button", {
    name: ":cat:",
  });
  expect(custom.querySelector("img")).not.toBeNull();
  await user.click(custom);
  expect(onPick).toHaveBeenCalledWith(":cat:", "https://e/cat.png");
});

it("対象があれば名前と本文を出す", async () => {
  const authorKey = generateSecretKey();
  eventStore.add(
    finalizeEvent(
      { kind: 0, created_at: unixNow(), tags: [], content: JSON.stringify({ name: "alice" }) },
      authorKey,
    ),
  );
  const target = finalizeEvent(
    { kind: 1, created_at: unixNow(), tags: [], content: "対象の本文" },
    authorKey,
  );
  open(target);
  expect(await screen.findByText("alice")).toBeVisible();
  expect(screen.getByText("対象の本文")).toBeVisible();
});

it("cancel（Esc / 戻る）・✗・背景の押下で onClose", async () => {
  const user = userEvent.setup();
  const { onClose, dialog } = open();
  act(() => {
    dialog.dispatchEvent(new Event("cancel", { cancelable: true }));
  });
  expect(onClose).toHaveBeenCalledTimes(1);

  await user.click(screen.getByRole("button", { name: "閉じる" }));
  expect(onClose).toHaveBeenCalledTimes(2);

  await user.click(dialog);
  expect(onClose).toHaveBeenCalledTimes(3);
});

describe("絵文字を作る（#768）", () => {
  const fetchMock = vi.fn<typeof fetch>();
  const kusaUrl = () => `${window.location.origin}/api/emoji.png?text=%E8%8D%89&stroke=ffffff`;
  const autoName = (url: string) => `nostrism_${createHash("sha256").update(url).digest("hex").slice(0, 8)}`;

  beforeEach(() => {
    fetchMock.mockReset();
    fetchMock.mockImplementation(() =>
      Promise.resolve(new Response(new Blob([new Uint8Array([0x89, 0x50])], { type: "image/png" }))),
    );
    vi.stubGlobal("fetch", fetchMock);
    // jsdom に objectURL は無い
    Object.assign(URL, { createObjectURL: vi.fn(() => "blob:emoji"), revokeObjectURL: vi.fn() });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function post(): NostrEvent {
    return finalizeEvent({ kind: 1, created_at: unixNow(), tags: [], content: "対象" }, generateSecretKey());
  }

  async function openMaker() {
    const opened = open(post());
    await userEvent.click(screen.getByRole("button", { name: "絵文字を作る" }));
    return opened;
  }

  const textArea = () => screen.getByRole("textbox", { name: "テキスト" });
  const shortcode = () => screen.getByRole("textbox", { name: "ショートコード（任意）" });
  const send = () => screen.getByRole("button", { name: "この絵文字でリアクション" });

  it("対象が無い（投稿画面・既定リアクションの設定）ときは出さない", () => {
    open();
    expect(screen.queryByRole("button", { name: "絵文字を作る" })).toBeNull();
  });

  it("「絵文字を作る」で一覧の代わりにフォーム（黒文字 + 白縁取り）。「戻る」で一覧へ", async () => {
    await openMaker();
    expect(screen.queryByRole("searchbox", { name: "絵文字を検索" })).toBeNull();
    expect(screen.queryByRole("region", { name: "最近" })).toBeNull();
    expect(textArea()).toHaveValue("");
    expect(screen.getByRole("textbox", { name: "文字色（16進）" })).toHaveValue("000000");
    expect(screen.getByRole("checkbox", { name: "縁取り" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "自分の絵文字リストにも保存" })).not.toBeChecked();
    // テキストが空のうちは押せない
    expect(send()).toBeDisabled();

    await userEvent.click(screen.getByRole("button", { name: "戻る" }));
    expect(screen.getByRole("searchbox", { name: "絵文字を検索" })).toBeVisible();
    expect(screen.getByRole("region", { name: "最近" })).toBeVisible();
    expect(screen.queryByRole("textbox", { name: "テキスト" })).toBeNull();
  });

  it("名前が空なら自動の名前（プレースホルダに出す）で onPick(:nostrism_…:, URL, autoName) して閉じる", async () => {
    const { onPick, onClose } = await openMaker();
    fireEvent.change(textArea(), { target: { value: "草" } });
    const name = autoName(kusaUrl());
    await waitFor(() => expect(shortcode()).toHaveAttribute("placeholder", name));
    await waitFor(() => expect(send()).toBeEnabled());
    await userEvent.click(send());
    expect(onPick).toHaveBeenCalledWith(`:${name}:`, kusaUrl(), { made: true, autoName: true, save: false });
    expect(onClose).toHaveBeenCalled();
  });

  it("入力した名前と保存のチェックを渡す", async () => {
    const { onPick } = await openMaker();
    fireEvent.change(textArea(), { target: { value: "草" } });
    fireEvent.change(shortcode(), { target: { value: " :kusa: " } });
    await userEvent.click(screen.getByRole("checkbox", { name: "自分の絵文字リストにも保存" }));
    await waitFor(() => expect(send()).toBeEnabled());
    await userEvent.click(send());
    expect(onPick).toHaveBeenCalledWith(":kusa:", kusaUrl(), { made: true, autoName: false, save: true });
  });

  it("不正な名前の間は押せない", async () => {
    const { onPick } = await openMaker();
    fireEvent.change(textArea(), { target: { value: "草" } });
    await waitFor(() => expect(send()).toBeEnabled());
    fireEvent.change(shortcode(), { target: { value: "く さ" } });
    expect(send()).toBeDisabled();
    expect(shortcode()).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByText("ショートコードは英数字と _ - だけ使えます")).toBeVisible();
    fireEvent.change(shortcode(), { target: { value: "kusa_2" } });
    expect(send()).toBeEnabled();
    expect(onPick).not.toHaveBeenCalled();
  });

  it("プレビューがエラーの間は押せない", async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve(Response.json({ error: "unsupported_char", char: "𠮷" }, { status: 400 })),
    );
    await openMaker();
    fireEvent.change(textArea(), { target: { value: "𠮷" } });
    expect(await screen.findByRole("alert")).toHaveTextContent("「𠮷」はこのフォントで描けません");
    expect(send()).toBeDisabled();
  });

  it("今の入力のプレビューが届くまでは押せない", async () => {
    await openMaker();
    fireEvent.change(textArea(), { target: { value: "草" } });
    expect(send()).toBeDisabled();
    await waitFor(() => expect(send()).toBeEnabled());
    // 打ち直すと、新しいプレビューが届くまでまた押せない
    fireEvent.change(textArea(), { target: { value: "草草" } });
    expect(send()).toBeDisabled();
    await waitFor(() => expect(send()).toBeEnabled());
  });
});
