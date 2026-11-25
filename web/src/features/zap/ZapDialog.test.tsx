import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { finalizeEvent, generateSecretKey } from "nostr-tools/pure";
import { useState } from "react";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { t } from "../../i18n";
import { requestZapInvoice } from "../../lib/lnurl";
import { eventStore } from "../../nostr/store";
import { installDialogPolyfill } from "../../test/dialog";
import { useToast } from "../../ui/toast";
import { OPEN_APP_TIMEOUT_MS } from "./useOpenAppFallback";
import { ZapDialog } from "./ZapDialog";

// LNURL の取得・署名はしない（lnurl.test.ts で見る）。invoice の取得の呼ばれ方だけ見る
vi.mock("../../lib/lnurl", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/lnurl")>();
  return { ...actual, requestZapInvoice: vi.fn() };
});

const PR = "lnbc210n1ptestinvoice";
const RECIPIENT = "c".repeat(64);
const NOTE_ID = "d".repeat(64);

beforeAll(() => {
  installDialogPolyfill();
});

beforeEach(() => {
  vi.mocked(requestZapInvoice).mockReset();
  vi.mocked(requestZapInvoice).mockResolvedValue(PR);
});

afterEach(() => {
  Reflect.deleteProperty(window, "webln");
  Reflect.deleteProperty(navigator, "clipboard");
  useToast.setState({ queue: [] });
});

function renderDialog(
  props: { eventId?: string; targetKind?: number } = { eventId: NOTE_ID, targetKind: 1 },
) {
  const onClose = vi.fn();
  render(
    <ZapDialog
      recipient={RECIPIENT}
      recipientName="アリス"
      lud16="alice@example.com"
      {...props}
      onClose={onClose}
    />,
  );
  return { onClose, dialog: screen.getByRole("dialog", { name: "⚡ Zap" }) };
}

function installWebln(sendPayment = vi.fn(async () => ({ preimage: "00" }))) {
  const webln = { enable: vi.fn(async () => {}), sendPayment };
  Object.defineProperty(window, "webln", { value: webln, configurable: true, writable: true });
  return webln;
}

it("見出し・説明・送信先。既定は 100 sats", () => {
  const { dialog } = renderDialog();
  expect(
    within(dialog).getByText("アリス へ投げ銭します。金額を選び、ウォレットで支払ってください。"),
  ).toBeInTheDocument();
  expect(within(dialog).getByText("送信先: alice@example.com")).toBeInTheDocument();
  expect(within(dialog).getByRole("button", { name: "100" })).toHaveAttribute("aria-pressed", "true");
  expect(within(dialog).getByRole("button", { name: "⚡ 100" })).toBeInTheDocument();
  for (const sats of ["21", "500", "1000", "5000", "10000"]) {
    expect(within(dialog).getByRole("button", { name: sats })).toHaveAttribute("aria-pressed", "false");
  }
});

it("カスタム額（数字だけ）がプリセットより優先。投稿への Zap は e / k を渡す", async () => {
  const user = userEvent.setup();
  installWebln();
  const { dialog } = renderDialog();

  await user.click(within(dialog).getByRole("button", { name: "500" }));
  expect(within(dialog).getByRole("button", { name: "⚡ 500" })).toBeInTheDocument();
  await user.type(within(dialog).getByRole("textbox", { name: "カスタム額 (sats)" }), "2a1b0");
  expect(within(dialog).getByRole("textbox", { name: "カスタム額 (sats)" })).toHaveValue("210");
  expect(within(dialog).getByRole("button", { name: "500" })).toHaveAttribute("aria-pressed", "false");
  await user.type(within(dialog).getByRole("textbox", { name: "コメント（任意）" }), "ありがとう");
  await user.click(within(dialog).getByRole("button", { name: "⚡ 210" }));

  await waitFor(() => expect(requestZapInvoice).toHaveBeenCalledTimes(1));
  expect(requestZapInvoice).toHaveBeenCalledWith({
    recipient: RECIPIENT,
    lud16: "alice@example.com",
    amountSats: 210,
    comment: "ありがとう",
    eventId: NOTE_ID,
    targetKind: 1,
  });
});

it("window.webln があれば enable → sendPayment(pr)、成功でトーストを出して閉じる", async () => {
  const user = userEvent.setup();
  const webln = installWebln();
  const { dialog, onClose } = renderDialog();

  await user.click(within(dialog).getByRole("button", { name: "⚡ 100" }));

  await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
  expect(webln.enable).toHaveBeenCalledTimes(1);
  expect(webln.sendPayment).toHaveBeenCalledWith(PR);
  expect(useToast.getState().queue).toEqual([t("nwc_paid")]);
});

it("処理中はスピナーで、Esc でも閉じない", async () => {
  const user = userEvent.setup();
  let resolve: (pr: string | null) => void = () => {};
  vi.mocked(requestZapInvoice).mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  const { dialog, onClose } = renderDialog();

  await user.click(within(dialog).getByRole("button", { name: "⚡ 100" }));
  expect(dialog).toHaveAttribute("aria-busy", "true");
  expect(within(dialog).queryByRole("button", { name: "キャンセル" })).toBeNull();
  expect(within(dialog).queryByRole("button", { name: /⚡/ })).toBeNull();
  dialog.dispatchEvent(new Event("cancel", { cancelable: true }));
  expect(onClose).not.toHaveBeenCalled();

  resolve(PR);
  expect(await within(dialog).findByRole("button", { name: "閉じる" })).toBeInTheDocument();
  expect(dialog).toHaveAttribute("aria-busy", "false");
});

it("ウォレットが無ければ QR・「外部ウォレットで開く」・コピーを閉じるまで出す（プロフィール Zap は e / k なし）", async () => {
  const user = userEvent.setup();
  const writeText = vi.fn(async () => {});
  Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
  const { dialog, onClose } = renderDialog({});

  await user.click(within(dialog).getByRole("button", { name: "⚡ 100" }));

  expect(await within(dialog).findByRole("img", { name: "Zap の invoice（100 sats）" })).toBeInTheDocument();
  expect(requestZapInvoice).toHaveBeenCalledWith(
    expect.objectContaining({ amountSats: 100, eventId: undefined, targetKind: undefined }),
  );
  expect(within(dialog).getByRole("link", { name: "外部ウォレットで開く" })).toHaveAttribute(
    "href",
    `lightning:${PR}`,
  );
  await user.click(within(dialog).getByRole("button", { name: "コピー" }));
  expect(writeText).toHaveBeenCalledWith(PR);
  expect(within(dialog).getByRole("status")).toHaveTextContent("コピーしました");
  expect(onClose).not.toHaveBeenCalled();
  expect(useToast.getState().queue).toEqual([]);

  await user.click(within(dialog).getByRole("button", { name: "閉じる" }));
  expect(onClose).toHaveBeenCalledTimes(1);
});

/** (hover: none) and (pointer: coarse) に当たる端末にする */
function touchDevice(matches: boolean) {
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => ({
      matches: matches && query === "(hover: none) and (pointer: coarse)",
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    })),
  );
}

describe("[#994] スマホのブラウザのほかのウォレット", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("指で触る端末なら「ほかのウォレットで開く」に、ウォレットごとの URL で請求書を開くリンクを並べる", async () => {
    touchDevice(true);
    const user = userEvent.setup();
    const { dialog } = renderDialog({});
    await user.click(within(dialog).getByRole("button", { name: "⚡ 100" }));

    await user.click(await within(dialog).findByText("ほかのウォレットで開く"));
    expect(within(dialog).getByRole("link", { name: "Wallet of Satoshi" })).toHaveAttribute(
      "href",
      `walletofsatoshi:lightning:${PR}`,
    );
    expect(within(dialog).getByRole("link", { name: "Cash App" })).toHaveAttribute(
      "href",
      `https://cash.app/launch/lightning/${PR}`,
    );
    // 端末の既定は「外部ウォレットで開く」のほう
    expect(within(dialog).getByRole("link", { name: "外部ウォレットで開く" })).toHaveAttribute(
      "href",
      `lightning:${PR}`,
    );
  });

  it("PC（ホバーできる端末）では出さない", async () => {
    touchDevice(false);
    const user = userEvent.setup();
    const { dialog } = renderDialog({});
    await user.click(within(dialog).getByRole("button", { name: "⚡ 100" }));
    expect(await within(dialog).findByRole("link", { name: "外部ウォレットで開く" })).toBeInTheDocument();
    expect(within(dialog).queryByText("ほかのウォレットで開く")).not.toBeInTheDocument();
  });
});

describe("失敗", () => {
  it("invoice が取れなければ zap_invoice_failed を出し、もう一度送れる", async () => {
    const user = userEvent.setup();
    vi.mocked(requestZapInvoice).mockResolvedValue(null);
    const { dialog, onClose } = renderDialog();

    await user.click(within(dialog).getByRole("button", { name: "⚡ 100" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(t("zap_invoice_failed"));
    expect(within(dialog).getByRole("button", { name: "⚡ 100" })).toBeEnabled();
    expect(within(dialog).queryByRole("img")).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("WebLN が拒否したらエラーを出して QR・リンク・コピーに切り替える", async () => {
    const user = userEvent.setup();
    installWebln(
      vi.fn(async () => {
        throw new Error("User rejected");
      }),
    );
    const { dialog, onClose } = renderDialog();

    await user.click(within(dialog).getByRole("button", { name: "⚡ 100" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("送金に失敗しました: User rejected");
    expect(within(dialog).getByRole("img", { name: "Zap の invoice（100 sats）" })).toBeInTheDocument();
    expect(within(dialog).getByRole("link", { name: "外部ウォレットで開く" })).toHaveAttribute(
      "href",
      `lightning:${PR}`,
    );
    expect(within(dialog).getByRole("button", { name: "コピー" })).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(useToast.getState().queue).toEqual([]);
  });
});

describe("payWithWallet（#537 の口）", () => {
  function renderWithWallet(payWithWallet = vi.fn(async () => {})) {
    const onClose = vi.fn();
    render(
      <ZapDialog
        recipient={RECIPIENT}
        recipientName="アリス"
        lud16="alice@example.com"
        payWithWallet={payWithWallet}
        onClose={onClose}
      />,
    );
    return { onClose, payWithWallet };
  }

  it("WebLN より先に、確認ダイアログを経てから払う", async () => {
    const user = userEvent.setup();
    const webln = installWebln();
    const { onClose, payWithWallet } = renderWithWallet();

    await user.click(screen.getByRole("button", { name: "⚡ 100" }));
    expect(payWithWallet).not.toHaveBeenCalled();
    const confirm = await screen.findByRole("dialog", { name: "ウォレットから送金" });
    expect(confirm).toHaveTextContent("アリス に ⚡ 100 sats を送金します。よろしいですか？");

    await user.click(within(confirm).getByRole("button", { name: "送金する" }));

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(payWithWallet).toHaveBeenCalledWith(PR);
    expect(webln.sendPayment).not.toHaveBeenCalled();
    expect(useToast.getState().queue).toEqual([t("nwc_paid")]);
  });

  it("確認をキャンセルすれば払わない。もう一度送れる", async () => {
    const user = userEvent.setup();
    const { onClose, payWithWallet } = renderWithWallet();

    await user.click(screen.getByRole("button", { name: "⚡ 100" }));
    const confirm = await screen.findByRole("dialog", { name: "ウォレットから送金" });
    await user.click(within(confirm).getByRole("button", { name: "キャンセル" }));

    expect(payWithWallet).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "⚡ 100" })).toBeEnabled();
  });

  it("送金に失敗したら外部ウォレットへ逃がす", async () => {
    const user = userEvent.setup();
    const payWithWallet = vi.fn(async () => {
      throw new Error("wallet error [PAYMENT_FAILED] insufficient balance");
    });
    const onClose = vi.fn();
    render(
      <ZapDialog
        recipient={RECIPIENT}
        recipientName="アリス"
        lud16="alice@example.com"
        payWithWallet={payWithWallet}
        onClose={onClose}
      />,
    );
    const dialog = screen.getByRole("dialog", { name: "⚡ Zap" });

    await user.click(within(dialog).getByRole("button", { name: "⚡ 100" }));
    const confirm = await screen.findByRole("dialog", { name: "ウォレットから送金" });
    await user.click(within(confirm).getByRole("button", { name: "送金する" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "送金に失敗しました: wallet error [PAYMENT_FAILED] insufficient balance",
    );
    expect(within(dialog).getByRole("img", { name: "Zap の invoice（100 sats）" })).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(useToast.getState().queue).toEqual([]);
  });

  it("接続済みの案内（nwc_via）を出す", async () => {
    renderWithWallet();
    expect(screen.getByText("接続済みウォレットからアプリ内で送金します（毎回確認）。")).toBeInTheDocument();
  });
});

it("キャンセル・Esc で閉じる", async () => {
  const user = userEvent.setup();
  const { dialog, onClose } = renderDialog();
  await user.click(within(dialog).getByRole("button", { name: "キャンセル" }));
  expect(onClose).toHaveBeenCalledTimes(1);
  dialog.dispatchEvent(new Event("cancel", { cancelable: true }));
  expect(onClose).toHaveBeenCalledTimes(2);
});

describe("[#1012] アプリが開かなければ QR のモーダル", () => {
  const QR_TITLE = "スマホのウォレットで読み取ってください";

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  /** 外部ウォレットの表示（請求書あり）まで進める。そこからはフェイクタイマー */
  async function toInvoice(dialog: HTMLElement) {
    const user = userEvent.setup();
    await user.click(within(dialog).getByRole("button", { name: "⚡ 100" }));
    await within(dialog).findByRole("link", { name: "外部ウォレットで開く" });
    vi.useFakeTimers();
  }

  it("「外部ウォレットで開く」を押して 1500ms ページが裏に回らなければ、モーダルで QR を大きく出す", async () => {
    const { dialog, onClose } = renderDialog();
    await toInvoice(dialog);
    // userEvent.setup() が clipboard を差し替えるので、その後に置く
    const writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });

    fireEvent.click(within(dialog).getByRole("link", { name: "外部ウォレットで開く" }));
    act(() => vi.advanceTimersByTime(OPEN_APP_TIMEOUT_MS - 1));
    expect(screen.queryByRole("dialog", { name: QR_TITLE })).toBeNull();
    act(() => vi.advanceTimersByTime(1));

    const modal = screen.getByRole("dialog", { name: QR_TITLE });
    expect(within(modal).getByRole("img", { name: "Zap の invoice（100 sats）" })).toHaveAttribute(
      "width",
      "320",
    );
    await act(async () => {
      fireEvent.click(within(modal).getByRole("button", { name: "コピー" }));
    });
    expect(writeText).toHaveBeenCalledWith(PR);
    expect(within(modal).getByRole("status")).toHaveTextContent("コピーしました");

    fireEvent.click(within(modal).getByRole("button", { name: "閉じる" }));
    expect(screen.queryByRole("dialog", { name: QR_TITLE })).toBeNull();
    // Zap のダイアログはそのまま
    expect(screen.getByRole("dialog", { name: "⚡ Zap" })).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  it.each([
    ["blur", () => window.dispatchEvent(new Event("blur"))],
    [
      "visibilitychange（hidden）",
      () => {
        Object.defineProperty(document, "visibilityState", { value: "hidden", configurable: true });
        document.dispatchEvent(new Event("visibilitychange"));
      },
    ],
  ])("%s が来たら（アプリが開いた）モーダルは出さない", async (_, leave) => {
    const { dialog } = renderDialog();
    await toInvoice(dialog);

    fireEvent.click(within(dialog).getByRole("link", { name: "外部ウォレットで開く" }));
    act(() => vi.advanceTimersByTime(300));
    leave();
    act(() => vi.advanceTimersByTime(OPEN_APP_TIMEOUT_MS));
    expect(screen.queryByRole("dialog", { name: QR_TITLE })).toBeNull();
    Reflect.deleteProperty(document, "visibilityState");
  });

  it("スマホの「ほかのウォレットで開く」のリンクでも同じ。「QR を大きく表示」は出さない", async () => {
    touchDevice(true);
    const { dialog } = renderDialog();
    await toInvoice(dialog);
    expect(within(dialog).queryByRole("button", { name: "QR を大きく表示" })).toBeNull();
    expect(within(dialog).getByRole("img", { name: "Zap の invoice（100 sats）" })).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole("link", { name: "Strike" }));
    act(() => vi.advanceTimersByTime(OPEN_APP_TIMEOUT_MS));
    expect(screen.getByRole("dialog", { name: QR_TITLE })).toBeInTheDocument();
  });

  it("PC は「QR を大きく表示」でモーダルを開ける", async () => {
    touchDevice(false);
    const { dialog } = renderDialog();
    await toInvoice(dialog);

    fireEvent.click(within(dialog).getByRole("button", { name: "QR を大きく表示" }));
    expect(screen.getByRole("dialog", { name: QR_TITLE })).toBeInTheDocument();
  });

  it("Zap のダイアログを閉じたらタイマーを残さない", async () => {
    function Harness() {
      const [open, setOpen] = useState(true);
      return open ? (
        <ZapDialog
          recipient={RECIPIENT}
          recipientName="アリス"
          lud16="alice@example.com"
          onClose={() => setOpen(false)}
        />
      ) : null;
    }
    render(<Harness />);
    const dialog = screen.getByRole("dialog", { name: "⚡ Zap" });
    await toInvoice(dialog);

    const before = vi.getTimerCount();
    fireEvent.click(within(dialog).getByRole("link", { name: "外部ウォレットで開く" }));
    // クリックで積まれる 0ms のタスク（jsdom の遷移など）は先に流す
    act(() => vi.advanceTimersByTime(0));
    expect(vi.getTimerCount()).toBe(before + 1);
    fireEvent.click(within(dialog).getByRole("button", { name: "閉じる" }));
    expect(screen.queryByRole("dialog", { name: "⚡ Zap" })).toBeNull();
    expect(vi.getTimerCount()).toBe(before);
    act(() => vi.advanceTimersByTime(OPEN_APP_TIMEOUT_MS));
    expect(screen.queryByRole("dialog", { name: QR_TITLE })).toBeNull();
  });

  it("この請求書の receipt（bolt11 が一致）が届いたらモーダルだけ閉じる", async () => {
    // ストアは共有なので、ほかのテストと被らない請求書と投稿にする
    const pr = "lnbc210n1preceiptclose";
    const noteId = "e".repeat(64);
    vi.mocked(requestZapInvoice).mockResolvedValue(pr);
    const { dialog, onClose } = renderDialog({ eventId: noteId, targetKind: 1 });
    await toInvoice(dialog);
    fireEvent.click(within(dialog).getByRole("button", { name: "QR を大きく表示" }));
    expect(screen.getByRole("dialog", { name: QR_TITLE })).toBeInTheDocument();

    const receipt = (bolt11: string) =>
      finalizeEvent(
        {
          kind: 9735,
          created_at: 1_000,
          tags: [
            ["e", noteId],
            ["bolt11", bolt11],
          ],
          content: "",
        },
        generateSecretKey(),
      );
    act(() => {
      eventStore.add(receipt("lnbc210n1pothers"));
    });
    expect(screen.getByRole("dialog", { name: QR_TITLE })).toBeInTheDocument();

    act(() => {
      eventStore.add(receipt(pr));
    });
    expect(screen.queryByRole("dialog", { name: QR_TITLE })).toBeNull();
    expect(screen.getByRole("dialog", { name: "⚡ Zap" })).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });
});
