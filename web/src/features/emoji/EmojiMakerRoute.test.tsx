import { act, fireEvent, render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { routes } from "../../app/routes";
import { useSession } from "../../signer/session";
import { PUBKEY, resetSession } from "../../test/fakeNostr";
import { useToast } from "../../ui/toast";
import { appendToEmojiList, EmojiListError } from "../compose/customEmojis";
import { PREVIEW_DEBOUNCE_MS } from "./EmojiMakerRoute";

// 絵文字リストの発行はしない（取り直し・署名・送信は customEmojis.test.ts で見る）
vi.mock("../compose/customEmojis", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../compose/customEmojis")>()),
  appendToEmojiList: vi.fn(async () => {}),
}));

const fetchMock = vi.fn<typeof fetch>();
const createObjectURL = vi.fn(() => "blob:emoji");
const revokeObjectURL = vi.fn();

beforeEach(() => {
  vi.mocked(appendToEmojiList).mockReset();
  vi.mocked(appendToEmojiList).mockResolvedValue(undefined);
  fetchMock.mockReset();
  fetchMock.mockImplementation(() =>
    Promise.resolve(new Response(new Blob([new Uint8Array([0x89, 0x50])], { type: "image/png" }))),
  );
  vi.stubGlobal("fetch", fetchMock);
  // jsdom に objectURL は無い
  Object.assign(URL, { createObjectURL, revokeObjectURL });
  useSession.setState({ status: "out", method: null, pubkey: null });
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  Reflect.deleteProperty(navigator, "clipboard");
  useToast.setState({ queue: [] });
  resetSession();
});

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { basename: "/", initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

const origin = () => window.location.origin;
const textArea = () => screen.getByRole("textbox", { name: "テキスト" });
const urlField = () => screen.getByRole("textbox", { name: "画像のURL" });

/** デバウンスを進め、fetch の応答まで流す */
async function flushPreview() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(PREVIEW_DEBOUNCE_MS);
  });
}

it("未ログインでも開け（/login に飛ばない）、初期値は黒文字 + 白縁取り", () => {
  const router = renderAt("/emoji");

  expect(router.state.location.pathname).toBe("/emoji");
  expect(screen.getByRole("heading", { name: "絵文字をつくる" })).toBeInTheDocument();
  expect(screen.getByRole("textbox", { name: "文字色（16進）" })).toHaveValue("000000");
  expect(screen.getByRole("checkbox", { name: "縁取り" })).toBeChecked();
  expect(screen.getByRole("textbox", { name: "縁取りの色（16進）" })).toHaveValue("ffffff");
  expect(screen.getByRole("radio", { name: "Noto Sans JP" })).toBeChecked();
  // 空のうちは URL を出さず、取りに行かない
  expect(urlField()).toHaveValue("");
  expect(screen.getByRole("button", { name: "コピー" })).toBeDisabled();
  // 未ログインは追加の代わりにログインの案内
  expect(screen.getByRole("link", { name: "ログイン" })).toHaveAttribute("href", "/login?next=%2Femoji");
  expect(screen.queryByRole("button", { name: "自分の絵文字に追加" })).toBeNull();
  expect(screen.queryByRole("link", { name: "絵文字の設定を開く" })).toBeNull();
  // フォントのライセンス表記
  expect(screen.getByRole("link", { name: "Dela Gothic One" })).toHaveAttribute(
    "href",
    "/fonts/OFL-delagothicone.txt",
  );
});

it("入力が止まって 400 ms 後に、正規化した URL を 1 回だけ取りに行く", async () => {
  vi.useFakeTimers();
  renderAt("/emoji");

  fireEvent.change(textArea(), { target: { value: "そ" } });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(200);
  });
  fireEvent.change(textArea(), { target: { value: "それ\\nな" } });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(PREVIEW_DEBOUNCE_MS - 1);
  });
  expect(fetchMock).not.toHaveBeenCalled();

  await act(async () => {
    await vi.advanceTimersByTimeAsync(1);
  });
  const expected = `${origin()}/api/emoji.png?text=%E3%81%9D%E3%82%8C%0A%E3%81%AA&stroke=ffffff`;
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(fetchMock.mock.calls[0]?.[0]).toBe(expected);
  expect(urlField()).toHaveValue(expected);
  // 明るい地・暗い地の 2 枚に同じ画像
  expect(screen.getByRole("img", { name: "明るい背景" })).toHaveAttribute("src", "blob:emoji");
  expect(screen.getByRole("img", { name: "暗い背景" })).toHaveAttribute("src", "blob:emoji");
});

it("色・縁取り・フォントの変更が URL に入る（既定値は省く）", () => {
  renderAt("/emoji");

  fireEvent.change(textArea(), { target: { value: "草" } });
  fireEvent.change(screen.getByRole("textbox", { name: "文字色（16進）" }), { target: { value: "#F0A" } });
  fireEvent.click(screen.getByRole("checkbox", { name: "縁取り" }));
  fireEvent.click(screen.getByRole("radio", { name: "Dela Gothic One" }));

  expect(urlField()).toHaveValue(`${origin()}/api/emoji.png?text=%E8%8D%89&color=ff00aa&font=delagothic`);
  expect(screen.queryByRole("textbox", { name: "縁取りの色（16進）" })).toBeNull();
});

it("上限を超えたら赤字にし、取りに行かない", async () => {
  vi.useFakeTimers();
  renderAt("/emoji");

  fireEvent.change(textArea(), { target: { value: "あいうえおかきくけこさ" } });
  await flushPreview();

  expect(textArea()).toHaveAttribute("aria-invalid", "true");
  expect(screen.getByText("4 行・1 行 10 文字まで")).toBeInTheDocument();
  expect(urlField()).toHaveValue("");
  expect(fetchMock).not.toHaveBeenCalled();
});

it("400 応答の error を文言にして出す（unsupported_char は文字を埋める）", async () => {
  vi.useFakeTimers();
  fetchMock.mockImplementation(() =>
    Promise.resolve(
      new Response(JSON.stringify({ error: "unsupported_char", char: "😀" }), {
        status: 400,
        headers: { "Content-Type": "application/json" },
      }),
    ),
  );
  renderAt("/emoji");

  fireEvent.change(textArea(), { target: { value: "😀" } });
  await flushPreview();

  expect(screen.getByRole("alert")).toHaveTextContent("「😀」はこのフォントで描けません");
  expect(screen.queryByRole("img", { name: "明るい背景" })).toBeNull();
});

it("通信に失敗したら network の文言", async () => {
  vi.useFakeTimers();
  fetchMock.mockImplementation(() => Promise.reject(new TypeError("Failed to fetch")));
  renderAt("/emoji");

  fireEvent.change(textArea(), { target: { value: "草" } });
  await flushPreview();

  expect(screen.getByRole("alert")).toHaveTextContent("画像を取得できませんでした");
});

it("コピーで URL をクリップボードへ書き、トーストを出す", async () => {
  const writeText = vi.fn(() => Promise.resolve());
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
  renderAt("/emoji");

  fireEvent.change(textArea(), { target: { value: "草" } });
  fireEvent.click(screen.getByRole("button", { name: "コピー" }));

  expect(await screen.findByRole("status")).toHaveTextContent("URLをコピーしました");
  expect(writeText).toHaveBeenCalledWith(`${origin()}/api/emoji.png?text=%E8%8D%89&stroke=ffffff`);
});

it("/emoji?text=…&font=… を初期値にする（stroke が無ければ縁取りなし）", () => {
  renderAt("/emoji?text=%E3%81%9D%E3%82%8C%0A%E3%81%AA&color=00FF00&font=delagothic");

  expect(textArea()).toHaveValue("それ\nな");
  expect(screen.getByRole("radio", { name: "Dela Gothic One" })).toBeChecked();
  expect(screen.getByRole("textbox", { name: "文字色（16進）" })).toHaveValue("00ff00");
  expect(screen.getByRole("checkbox", { name: "縁取り" })).not.toBeChecked();
  expect(urlField()).toHaveValue(
    `${origin()}/api/emoji.png?text=%E3%81%9D%E3%82%8C%0A%E3%81%AA&color=00ff00&font=delagothic`,
  );
});

describe("ログイン中は自分の絵文字リストへ追加できる", () => {
  beforeEach(() => {
    useSession.setState({ status: "in", method: "nip07", pubkey: PUBKEY });
  });

  const shortcode = () => screen.getByRole("textbox", { name: "ショートコード" });
  const addButton = () => screen.getByRole("button", { name: "自分の絵文字に追加" });

  it("shortcode と正規化した URL で appendToEmojiList を呼び、成功をトーストで出す", async () => {
    renderAt("/emoji");
    expect(screen.queryByRole("link", { name: "ログイン" })).toBeNull();
    expect(screen.getByRole("link", { name: "← アプリへ" })).toHaveAttribute("href", "/");
    expect(screen.getByRole("link", { name: "絵文字の設定を開く" })).toHaveAttribute(
      "href",
      "/settings/emoji",
    );

    fireEvent.change(textArea(), { target: { value: "草" } });
    fireEvent.change(shortcode(), { target: { value: ":kusa:" } });
    fireEvent.click(addButton());

    expect(await screen.findByRole("status")).toHaveTextContent("絵文字リストを公開しました。");
    expect(appendToEmojiList).toHaveBeenCalledWith(PUBKEY, {
      shortcode: "kusa",
      url: `${origin()}/api/emoji.png?text=%E8%8D%89&stroke=ffffff`,
    });
    expect(shortcode()).toHaveValue("");
  });

  it("テキストが空なら押せない", () => {
    renderAt("/emoji");
    fireEvent.change(shortcode(), { target: { value: "kusa" } });
    expect(addButton()).toBeDisabled();
  });

  it("shortcode の形が不正なら欄の下に出して呼ばない", () => {
    renderAt("/emoji");
    fireEvent.change(textArea(), { target: { value: "草" } });
    fireEvent.change(shortcode(), { target: { value: "く さ" } });
    fireEvent.click(addButton());

    expect(screen.getByRole("alert")).toHaveTextContent(
      "ショートコードは英数字と _ - 、画像URLは https:// だけ使えます",
    );
    expect(appendToEmojiList).not.toHaveBeenCalled();
  });

  it("重複（duplicate）は欄の下に、取り直しの失敗（no-emoji-list）はトーストで出す", async () => {
    renderAt("/emoji");
    fireEvent.change(textArea(), { target: { value: "草" } });
    fireEvent.change(shortcode(), { target: { value: "kusa" } });

    vi.mocked(appendToEmojiList).mockRejectedValueOnce(new EmojiListError("duplicate"));
    fireEvent.click(addButton());
    expect(await screen.findByRole("alert")).toHaveTextContent("そのショートコードは追加済みです");

    vi.mocked(appendToEmojiList).mockRejectedValueOnce(new EmojiListError("no-emoji-list"));
    fireEvent.change(shortcode(), { target: { value: "kusa2" } });
    fireEvent.click(addButton());
    expect(await screen.findByRole("status")).toHaveTextContent("最新の絵文字リストを取得できなかったため");
  });
});
