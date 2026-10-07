import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { addVerified } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { useToast } from "../../ui/toast";
import { EmojiListError, publishEmojiList } from "../compose/customEmojis";
import { PREVIEW_DEBOUNCE_MS } from "../emoji/EmojiMakerForm";
import { LAST_MAKER_KEY } from "../emoji/lastMakerInput";
import { EmojiSection } from "./EmojiSection";

// 取り直し・発行は customEmojis.test.ts。ここは呼び出しと画面だけ
vi.mock("../compose/customEmojis", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../compose/customEmojis")>()),
  publishEmojiList: vi.fn(async () => undefined),
}));

let meKey: Uint8Array;
let me: string;

function emojiList(tags: string[][]): NostrEvent {
  return finalizeEvent({ kind: 10030, created_at: 1_000, tags, content: "" }, meKey);
}

beforeEach(() => {
  meKey = generateSecretKey();
  me = getPublicKey(meKey);
  useSession.setState({ status: "in", method: "local", pubkey: me });
  vi.mocked(publishEmojiList).mockReset();
  vi.mocked(publishEmojiList).mockResolvedValue(undefined);
  localStorage.removeItem(LAST_MAKER_KEY);
});

afterEach(() => {
  useSession.setState({ status: "loading", method: null, pubkey: null });
  useToast.setState({ queue: [] });
});

function rows() {
  return within(screen.getByRole("list", { name: "カスタム絵文字の一覧" }))
    .getAllByRole("listitem")
    .map((li) => li.textContent);
}

async function addEmoji(code: string, url: string) {
  const codeInput = screen.getByLabelText("ショートコード");
  const urlInput = screen.getByLabelText("画像URL");
  await userEvent.clear(codeInput);
  await userEvent.type(codeInput, code);
  await userEvent.clear(urlInput);
  await userEvent.type(urlInput, url);
  await userEvent.click(screen.getByRole("button", { name: "追加" }));
}

it("空なら案内を出し、保存は無効", () => {
  render(<EmojiSection />);
  expect(screen.getByText("カスタム絵文字はまだありません。下から追加できます。")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "保存して公開" })).toBeDisabled();
});

it("追加・削除・重複と不正な入力の検証、保存で下書きの差分（削除・追加）を渡して発行する", async () => {
  addVerified(
    emojiList([
      ["emoji", "old", "https://a/old.png"],
      ["a", "30030:pk:set"],
    ]),
  );
  render(<EmojiSection />);
  expect(rows()).toEqual([":old:削除"]);

  await addEmoji("cat", "http://a/cat.png");
  expect(screen.getByRole("alert")).toHaveTextContent(
    "ショートコードは英数字と _ - 、画像URLは https:// だけ使えます",
  );

  await addEmoji("old", "https://a/old2.png");
  expect(screen.getByRole("alert")).toHaveTextContent("そのショートコードは追加済みです");

  await addEmoji("cat", "https://a/cat.png");
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  expect(rows()).toEqual([":old:削除", ":cat:削除"]);

  await userEvent.click(screen.getByRole("button", { name: ":old: を削除" }));
  expect(rows()).toEqual([":cat:削除"]);

  await userEvent.click(screen.getByRole("button", { name: "保存して公開" }));

  expect(vi.mocked(publishEmojiList)).toHaveBeenCalledTimes(1);
  expect(vi.mocked(publishEmojiList).mock.calls[0]).toEqual([
    me,
    { removed: ["old"], added: [{ shortcode: "cat", url: "https://a/cat.png" }] },
    expect.any(String),
  ]);
  expect(useToast.getState().queue).toEqual(["絵文字リストを公開しました。"]);
});

it("公開に失敗したら（stale）下書きを捨てて最新の内容を出し直す", async () => {
  addVerified(emojiList([["emoji", "cat", "https://a/cat.png"]]));
  vi.mocked(publishEmojiList).mockRejectedValueOnce(new EmojiListError("stale"));
  render(<EmojiSection />);

  await addEmoji("dog", "https://a/dog.png");
  await userEvent.click(screen.getByRole("button", { name: "保存して公開" }));

  await vi.waitFor(() =>
    expect(useToast.getState().queue).toEqual([
      "絵文字リストが更新されていたため、公開しませんでした。最新の内容を表示したので、確認してもう一度編集してください",
    ]),
  );
  expect(rows()).toEqual([":cat:削除"]);
});

describe("文字から作る（#763）", () => {
  const fetchMock = vi.fn<typeof fetch>();

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
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  const urlMode = () => screen.getByRole("button", { name: "URL で追加" });
  const makerMode = () => screen.getByRole("button", { name: "文字から作る" });
  const textArea = () => screen.getByRole("textbox", { name: "テキスト" });
  const shortcode = () => screen.getByRole("textbox", { name: "ショートコード" });
  const addButton = () => screen.getByRole("button", { name: "追加" });

  it("「URL で追加」と「文字から作る」を切り替える。初期値は黒文字 + 白縁取り", async () => {
    render(<EmojiSection />);
    expect(urlMode()).toHaveAttribute("aria-pressed", "true");
    expect(makerMode()).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByLabelText("画像URL")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "テキスト" })).toBeNull();

    await userEvent.click(makerMode());
    expect(makerMode()).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByLabelText("画像URL")).toBeNull();
    expect(textArea()).toHaveValue("");
    expect(screen.getByRole("textbox", { name: "文字色（16進）" })).toHaveValue("000000");
    expect(screen.getByRole("checkbox", { name: "縁取り" })).toBeChecked();
    expect(screen.getByRole("textbox", { name: "縁取りの色（16進）" })).toHaveValue("ffffff");

    await userEvent.click(urlMode());
    expect(screen.getByLabelText("画像URL")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "テキスト" })).toBeNull();
  });

  it("作って追加すると下書きの末尾に入り、まだ発行しない。保存で追加分として渡す", async () => {
    addVerified(emojiList([["emoji", "old", "https://a/old.png"]]));
    render(<EmojiSection />);
    await userEvent.click(makerMode());

    // テキストが空のうちは押せない
    fireEvent.change(shortcode(), { target: { value: "kusa" } });
    expect(addButton()).toBeDisabled();

    fireEvent.change(textArea(), { target: { value: "草" } });
    fireEvent.click(addButton());

    expect(rows()).toEqual([":old:削除", ":kusa:削除"]);
    expect(shortcode()).toHaveValue("");
    expect(vi.mocked(publishEmojiList)).not.toHaveBeenCalled();

    // 重複は「URL で追加」と同じ検証
    fireEvent.change(shortcode(), { target: { value: "old" } });
    fireEvent.click(addButton());
    expect(screen.getByRole("alert")).toHaveTextContent("そのショートコードは追加済みです");
    fireEvent.change(shortcode(), { target: { value: "く さ" } });
    fireEvent.click(addButton());
    expect(screen.getByRole("alert")).toHaveTextContent(
      "ショートコードは英数字と _ - 、画像URLは https:// だけ使えます",
    );
    expect(rows()).toEqual([":old:削除", ":kusa:削除"]);

    await userEvent.click(screen.getByRole("button", { name: "保存して公開" }));
    expect(vi.mocked(publishEmojiList).mock.calls[0]).toEqual([
      me,
      {
        removed: [],
        added: [
          { shortcode: "kusa", url: `${window.location.origin}/api/emoji.png?text=%E8%8D%89&stroke=ffffff` },
        ],
      },
      expect.any(String),
    ]);
  });

  it("下書きに足したら前回の設定として覚え、次に開くとその色・フォントで始まる（#783）", async () => {
    render(<EmojiSection />);
    await userEvent.click(makerMode());
    // プレビューはフォームの先頭
    const preview = screen.getByRole("region", { name: "プレビュー" });
    expect(preview.compareDocumentPosition(textArea()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    fireEvent.change(textArea(), { target: { value: "草" } });
    fireEvent.click(
      within(screen.getByRole("group", { name: "縁取りの色のパレット" })).getByRole("button", {
        name: "#fb8c00",
      }),
    );
    fireEvent.click(screen.getByRole("radio", { name: "Dela Gothic One" }));
    expect(localStorage.getItem(LAST_MAKER_KEY)).toBeNull();
    // 不正なショートコードで足せなければ覚えない
    fireEvent.change(shortcode(), { target: { value: "く さ" } });
    fireEvent.click(addButton());
    expect(localStorage.getItem(LAST_MAKER_KEY)).toBeNull();

    fireEvent.change(shortcode(), { target: { value: "kusa" } });
    fireEvent.click(addButton());
    expect(rows()).toEqual([":kusa:削除"]);
    expect(JSON.parse(localStorage.getItem(LAST_MAKER_KEY) ?? "null")).toEqual({
      v: 1,
      color: "000000",
      stroke: "fb8c00",
      font: "delagothic",
    });

    cleanup();
    render(<EmojiSection />);
    await userEvent.click(makerMode());
    expect(textArea()).toHaveValue("");
    expect(screen.getByRole("textbox", { name: "縁取りの色（16進）" })).toHaveValue("fb8c00");
    expect(screen.getByRole("radio", { name: "Dela Gothic One" })).toBeChecked();
  });

  it("プレビューがエラーの間は押せない", async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve(Response.json({ error: "unsupported_char", char: "𠮷" }, { status: 400 })),
    );
    vi.useFakeTimers();
    render(<EmojiSection />);
    fireEvent.click(makerMode());

    fireEvent.change(textArea(), { target: { value: "𠮷" } });
    fireEvent.change(shortcode(), { target: { value: "yoshi" } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(PREVIEW_DEBOUNCE_MS);
    });

    expect(screen.getByRole("alert")).toHaveTextContent("「𠮷」はこのフォントで描けません");
    expect(addButton()).toBeDisabled();
    expect(screen.getByText("カスタム絵文字はまだありません。下から追加できます。")).toBeInTheDocument();
  });
});
