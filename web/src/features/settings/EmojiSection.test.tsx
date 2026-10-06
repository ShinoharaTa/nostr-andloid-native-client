import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { addVerified } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { useToast } from "../../ui/toast";
import { EmojiListError, publishEmojiList } from "../compose/customEmojis";
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
