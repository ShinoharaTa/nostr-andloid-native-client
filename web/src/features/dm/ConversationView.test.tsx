import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { npubEncode } from "nostr-tools/nip19";
import { finalizeEvent, generateSecretKey, getPublicKey } from "nostr-tools/pure";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { DmMessageRow } from "../../db/schema";
import { retryUnsentNow, unsent$ } from "../../nostr/publish";
import { eventStore } from "../../nostr/store";
import { currentSigner } from "../../signer/session";
import { OTHER_PUBKEY, PUBKEY } from "../../test/fakeNostr";
import { createTestSigner } from "../../test/fakeSigner";
import { renderWithRouter } from "../../test/renderWithRouter";
import { useToast } from "../../ui/toast";
import { ConversationView } from "./ConversationView";
import { useDm } from "./dmStore";
import { type DmSendResult, sendDm } from "./send";

// 送信はしない（結果はテストごとに返す）
vi.mock("./send", () => ({ sendDm: vi.fn() }));

vi.mock("../../nostr/publish", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/publish")>()),
  retryUnsentNow: vi.fn(),
}));

// NIP-98 の署名者（添付のアップロードで使う。拡張機能は使わない）
vi.mock("../../signer/session", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../signer/session")>();
  return { ...actual, currentSigner: vi.fn() };
});

const ME = PUBKEY;
const PEER = OTHER_PUBKEY;

beforeEach(() => {
  vi.mocked(sendDm).mockReset();
  vi.mocked(retryUnsentNow).mockClear();
  useDm.getState().reset(ME);
  useDm.setState({ loaded: true });
  useToast.setState({ queue: [] });
});

afterEach(() => {
  useDm.getState().reset(null);
  unsent$.next(new Set());
});

function dm(id: string, sender: string, content: string, peer = PEER): DmMessageRow {
  return { owner: ME, id, peer, sender, content, tags: [], createdAt: 1_700_000_000, proto: "nip17" };
}

/** 送ったら自分の吹き出しを出して result を返す（sendDm の楽観表示の代わり） */
function sendResolves(result: DmSendResult) {
  vi.mocked(sendDm).mockImplementation(async (peer, text) => {
    if (result === "sent" || result === "sent-no-peer-relays") {
      useDm.getState().upsertMessages([dm(`sent-${text}`, ME, text, peer)]);
    }
    return result;
  });
}

function input(): HTMLTextAreaElement {
  return screen.getByRole("textbox", { name: "メッセージ" });
}

function sendButton(): HTMLButtonElement {
  return screen.getByRole("button", { name: "送信" });
}

function toasts(): string[] {
  return useToast.getState().queue;
}

describe("入力欄", () => {
  it("送信で入力が空になり吹き出しが出る。送信中は入力と送信を止める。空白だけでは押せない", async () => {
    let finish: (result: DmSendResult) => void = () => {};
    vi.mocked(sendDm).mockImplementation(
      (peer, text) =>
        new Promise((resolve) => {
          finish = (result) => {
            useDm.getState().upsertMessages([dm("sent-1", ME, text, peer)]);
            resolve(result);
          };
        }),
    );
    renderWithRouter(<ConversationView peer={PEER} />);
    expect(sendButton()).toBeDisabled();
    await userEvent.type(input(), "   ");
    expect(sendButton()).toBeDisabled();

    await userEvent.clear(input());
    await userEvent.type(input(), "やあ");
    await userEvent.click(sendButton());

    expect(vi.mocked(sendDm)).toHaveBeenCalledWith(PEER, "やあ", null);
    expect(input()).toBeDisabled();
    expect(sendButton()).toBeDisabled();

    await act(async () => finish("sent"));
    expect(input()).toHaveValue("");
    expect(input()).not.toBeDisabled();
    expect(screen.getByText("やあ")).toBeInTheDocument();
    expect(toasts()).toEqual([]);
  });

  it("Enter は改行、Ctrl / Cmd + Enter で送信。IME 変換中の Ctrl + Enter では送らない", async () => {
    sendResolves("sent");
    renderWithRouter(<ConversationView peer={PEER} />);
    await userEvent.type(input(), "1 行目{Enter}2 行目");
    expect(input()).toHaveValue("1 行目\n2 行目");
    expect(vi.mocked(sendDm)).not.toHaveBeenCalled();

    fireEvent.keyDown(input(), { key: "Enter", ctrlKey: true, isComposing: true });
    expect(vi.mocked(sendDm)).not.toHaveBeenCalled();

    await act(async () => {
      fireEvent.keyDown(input(), { key: "Enter", ctrlKey: true });
    });
    expect(vi.mocked(sendDm)).toHaveBeenCalledWith(PEER, "1 行目\n2 行目", null);
    expect(input()).toHaveValue("");

    await userEvent.type(input(), "cmd");
    await act(async () => {
      fireEvent.keyDown(input(), { key: "Enter", metaKey: true });
    });
    expect(vi.mocked(sendDm)).toHaveBeenLastCalledWith(PEER, "cmd", null);
  });

  it("「届かない可能性があります」は同じ相手ではセッション中 1 回だけ", async () => {
    const peer = "d".repeat(64);
    sendResolves("sent-no-peer-relays");
    renderWithRouter(<ConversationView peer={peer} />);

    await userEvent.type(input(), "1");
    await userEvent.click(sendButton());
    await userEvent.type(input(), "2");
    await userEvent.click(sendButton());

    expect(toasts()).toEqual(["相手がDMリレーを公開していないため、届かない可能性があります"]);
    expect(input()).toHaveValue("");
  });

  it.each([
    ["failed", "メッセージを送れませんでした"],
    ["no-nip44", "この拡張機能は NIP-44 に対応していないため、このメッセージを送れません"],
    ["no-nip04", "この拡張機能は NIP-04 に対応していないため、このメッセージを送れません"],
    ["no-relays", "送り先のリレーがありません"],
  ] as const)("%s → トースト「%s」。入力は残す", async (result, message) => {
    sendResolves(result);
    renderWithRouter(<ConversationView peer={PEER} />);
    await userEvent.type(input(), "残る");
    await userEvent.click(sendButton());
    expect(toasts()).toEqual([message]);
    expect(input()).toHaveValue("残る");
    expect(sendButton()).not.toBeDisabled();
  });

  it("署名者に NIP-44 も NIP-04 も無ければ入力欄の代わりに案内", () => {
    useDm.setState({ nip17: "no-nip44", nip04: "no-nip04" });
    renderWithRouter(<ConversationView peer={PEER} />);
    expect(screen.getByText("このログイン方法では DM を送れません")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("片方だけ使えれば入力欄を出す", () => {
    useDm.setState({ nip17: "no-nip44", nip04: "ok" });
    renderWithRouter(<ConversationView peer={PEER} />);
    expect(input()).toBeInTheDocument();
  });
});

describe("未送信", () => {
  it("自分の未送信の吹き出しに「未送信・タップで再送」、押すと retryUnsentNow。相手の吹き出しには出さない", async () => {
    useDm.getState().upsertMessages([dm("mine", ME, "送った"), dm("theirs", PEER, "受けた")]);
    renderWithRouter(<ConversationView peer={PEER} />);
    expect(screen.queryByRole("button", { name: "未送信・タップで再送" })).not.toBeInTheDocument();

    act(() => unsent$.next(new Set(["mine", "theirs"])));
    const retry = screen.getAllByRole("button", { name: "未送信・タップで再送" });
    expect(retry).toHaveLength(1);

    await userEvent.click(retry[0]);
    expect(vi.mocked(retryUnsentNow)).toHaveBeenCalledWith("mine");
  });
});

describe("返信（#589）", () => {
  it("バブルの返信ボタンでバナーが出て、送信すると返信元を sendDm へ渡す。送れたらバナーは消え、✕ でも取り消せる", async () => {
    sendResolves("sent");
    useDm.getState().upsertMessages([dm("parent", PEER, "元の発言")]);
    renderWithRouter(<ConversationView peer={PEER} />);
    expect(screen.queryByText(/ に返信: /)).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "リプライ" }));
    expect(screen.getByText(/ に返信: 元の発言$/)).toBeInTheDocument();

    await userEvent.type(input(), "了解です");
    await userEvent.click(sendButton());
    expect(vi.mocked(sendDm)).toHaveBeenCalledWith(
      PEER,
      "了解です",
      expect.objectContaining({ id: "parent", pubkey: PEER, content: "元の発言" }),
    );
    await waitFor(() => expect(screen.queryByText(/ に返信: /)).not.toBeInTheDocument());
  });

  it("✕（返信をやめる）でバナーを消し、次の送信では返信元を渡さない", async () => {
    sendResolves("sent");
    useDm.getState().upsertMessages([dm("parent", PEER, "元の発言")]);
    renderWithRouter(<ConversationView peer={PEER} />);

    await userEvent.click(screen.getByRole("button", { name: "リプライ" }));
    await userEvent.click(screen.getByRole("button", { name: "返信をやめる" }));
    expect(screen.queryByText(/ に返信: /)).not.toBeInTheDocument();

    await userEvent.type(input(), "普通の発言");
    await userEvent.click(sendButton());
    expect(vi.mocked(sendDm)).toHaveBeenCalledWith(PEER, "普通の発言", null);
  });

  it("受信側: #e（reply マーカー）から返信元を引いて 1 行引用を出す。手元に無ければ出さない（落ちない）", () => {
    useDm.getState().upsertMessages([
      dm("parent", PEER, "元の発言"),
      {
        ...dm("child", ME, "了解です"),
        tags: [
          ["p", PEER],
          ["e", "parent", "", "reply"],
        ],
      },
      {
        ...dm("orphan", ME, "宙に浮いた返信"),
        tags: [
          ["p", PEER],
          ["e", "missing", "", "reply"],
        ],
      },
    ]);
    renderWithRouter(<ConversationView peer={PEER} />);

    expect(screen.getByText(/: 元の発言$/)).toBeInTheDocument();
    expect(screen.getByText("了解です")).toBeInTheDocument();
    expect(screen.getByText("宙に浮いた返信")).toBeInTheDocument();
  });
});

describe("入力補完（#535）", () => {
  it(": でカスタム絵文字の候補、選ぶと :shortcode: に置き換える", async () => {
    const meKey = generateSecretKey();
    const me = getPublicKey(meKey);
    useDm.getState().reset(me);
    useDm.setState({ loaded: true });
    eventStore.add(
      finalizeEvent(
        {
          kind: 10030,
          created_at: 1_000,
          tags: [["emoji", "wave", "https://e.example/wave.png"]],
          content: "",
        },
        meKey,
      ),
    );
    sendResolves("sent");
    renderWithRouter(<ConversationView peer={PEER} />);
    await userEvent.type(input(), "hi :wa");

    await userEvent.click(await screen.findByRole("button", { name: ":wave:" }));
    expect(input()).toHaveValue("hi :wave: ");
  });

  it("@ で 120ms 後にメンション候補、選ぶと nostr:npub1… に置き換える", async () => {
    sendResolves("sent");
    const aliceKey = generateSecretKey();
    const alice = getPublicKey(aliceKey);
    eventStore.add(
      finalizeEvent(
        { kind: 0, created_at: 1_000, tags: [], content: JSON.stringify({ name: "alice" }) },
        aliceKey,
      ),
    );
    renderWithRouter(<ConversationView peer={PEER} />);
    await userEvent.type(input(), "hi @al");

    await userEvent.click(await screen.findByRole("button", { name: /alice/ }));
    expect(input()).toHaveValue(`hi nostr:${npubEncode(alice)} `);
  });
});

describe("添付（画像・動画。#535）", () => {
  const fetchMock = vi.fn<typeof fetch>();
  const UPLOADED_URL = "https://media.example/abc.webp";
  const originalCreate = URL.createObjectURL;
  const originalRevoke = URL.revokeObjectURL;

  beforeEach(() => {
    let n = 0;
    // jsdom には blob: URL が無い
    URL.createObjectURL = () => {
      n += 1;
      return `blob:test/${n}`;
    };
    URL.revokeObjectURL = vi.fn();
    vi.mocked(currentSigner).mockReturnValue(createTestSigner().signer);
    fetchMock.mockReset();
    fetchMock.mockImplementation(async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      // [#895] 最終 URL の確認（HEAD）は配られている扱い
      if (init?.method === "HEAD") return new Response(null, { headers: { "Content-Type": "image/webp" } });
      if (url.endsWith("/.well-known/nostr/nip96.json")) {
        return new Response(JSON.stringify({ api_url: "https://api.example/upload" }));
      }
      return new Response(
        JSON.stringify({ status: "success", nip94_event: { tags: [["url", UPLOADED_URL]] } }),
      );
    });
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    URL.createObjectURL = originalCreate;
    URL.revokeObjectURL = originalRevoke;
    vi.unstubAllGlobals();
  });

  function png(name = "a.png"): File {
    return new File(["x".repeat(2048)], name, { type: "image/png" });
  }

  function fileInput(accept: string): HTMLInputElement {
    const found = Array.from(document.querySelectorAll<HTMLInputElement>('input[type="file"]')).find(
      (i) => i.accept === accept,
    );
    if (!found) throw new Error(`file input not found for ${accept}`);
    return found;
  }

  it("「画像・動画を添付」でプレビューが出て、✗ で外せる（DM4: 添付ボタンは 1 つに統一）", async () => {
    renderWithRouter(<ConversationView peer={PEER} />);
    expect(screen.getByRole("button", { name: "画像・動画を添付" })).toBeInTheDocument();

    await userEvent.upload(fileInput("image/*,video/*"), png());
    expect(screen.getByRole("img", { name: "添付画像" })).toHaveAttribute("src", "blob:test/1");
    // 本文が空でも添付があれば送れる
    expect(sendButton()).toBeEnabled();

    await userEvent.click(screen.getByRole("button", { name: "添付を外す" }));
    expect(screen.queryByRole("img", { name: "添付画像" })).toBeNull();
    expect(sendButton()).toBeDisabled();
  });

  it("送信でアップロードし、本文の末尾に URL を改行でつないで送る", async () => {
    sendResolves("sent");
    renderWithRouter(<ConversationView peer={PEER} />);
    await userEvent.type(input(), "写真");
    await userEvent.upload(fileInput("image/*,video/*"), png());
    await userEvent.click(sendButton());

    await waitFor(() => expect(vi.mocked(sendDm)).toHaveBeenCalledWith(PEER, `写真\n${UPLOADED_URL}`, null));
  });

  it("アップロードに失敗したら送らず chat_upload_failed", async () => {
    fetchMock.mockRejectedValue(new Error("network"));
    renderWithRouter(<ConversationView peer={PEER} />);
    await userEvent.upload(fileInput("image/*,video/*"), png());
    await userEvent.click(sendButton());

    await waitFor(() =>
      expect(toasts()).toEqual([
        "添付をアップロードできませんでした。設定 → メディアサーバーを確認してください。",
      ]),
    );
    expect(vi.mocked(sendDm)).not.toHaveBeenCalled();
    // 失敗しても添付は残す
    expect(screen.getByRole("img", { name: "添付画像" })).toBeInTheDocument();
  });
});
