import { act, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { finalizeEvent, generateSecretKey, getPublicKey } from "nostr-tools/pure";
import { afterEach, beforeAll, beforeEach, expect, it, vi } from "vitest";
import { unixNow } from "../../lib/time";
import { requestOnce } from "../../nostr/pool";
import { publishEvent } from "../../nostr/publish";
import { eventStore } from "../../nostr/store";
import { installDialogPolyfill } from "../../test/dialog";
import { useToast } from "../../ui/toast";
import { StatusEditorDialog } from "./StatusEditorDialog";

// 送信と、いまのステータスの取得は差し替える（リレーへはつながない）
vi.mock("../../nostr/publish", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/publish")>();
  return { ...actual, publishEvent: vi.fn() };
});
vi.mock("../../nostr/pool", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/pool")>();
  const { NEVER } = await import("rxjs");
  return { ...actual, requestOnce: vi.fn(() => NEVER) };
});

beforeAll(() => {
  installDialogPolyfill();
});

beforeEach(() => {
  vi.mocked(publishEvent).mockReset();
  vi.mocked(publishEvent).mockImplementation(
    async (draft) => ({ ...draft, id: "x", pubkey: "p", sig: "s" }) as never,
  );
  vi.mocked(requestOnce).mockClear();
});

afterEach(() => {
  useToast.setState({ queue: [] });
});

/** 自分のステータスを手元（EventStore）に入れ、その公開鍵を返す。テストごとに別の鍵（前のテストの分が混ざらない） */
function withStatus(
  content: string | null,
  tags: string[][] = [],
  d = "general",
  createdAt = unixNow() - 120,
): string {
  const key = generateSecretKey();
  if (content !== null) {
    eventStore.add(
      finalizeEvent({ kind: 30315, created_at: createdAt, tags: [["d", d], ...tags], content }, key),
    );
  }
  return getPublicKey(key);
}

function renderDialog(pubkey: string) {
  const onClose = vi.fn();
  render(<StatusEditorDialog pubkey={pubkey} onClose={onClose} />);
  return onClose;
}

const input = () => screen.getByRole("textbox", { name: "ステータスを更新" });
const update = () => screen.getByRole("button", { name: "更新" });

it("ステータスが無ければ空で開く。「更新」は押せず、「消す」は出ない。開いたら自分のステータスを取りに行く", () => {
  const pubkey = withStatus(null);
  renderDialog(pubkey);
  expect(screen.getByRole("dialog", { name: "ステータスを更新" })).toBeInTheDocument();
  expect(input()).toHaveValue("");
  expect(update()).toBeDisabled();
  expect(screen.queryByRole("button", { name: "消す" })).toBeNull();
  expect(vi.mocked(requestOnce).mock.calls[0][1]).toEqual([
    { kinds: [30315], authors: [pubkey], "#d": ["general"], limit: 1 },
  ]);
});

it("書いて「更新」で d=general のステータスを出し、トーストを出して閉じる", async () => {
  const onClose = renderDialog(withStatus(null));
  await userEvent.type(input(), "  作業中  ");
  await userEvent.click(update());
  expect(publishEvent).toHaveBeenCalledTimes(1);
  expect(vi.mocked(publishEvent).mock.calls[0][0]).toMatchObject({
    kind: 30315,
    content: "作業中",
    tags: [["d", "general"]],
  });
  expect(useToast.getState().queue).toEqual(["ステータスを更新しました"]);
  expect(onClose).toHaveBeenCalledTimes(1);
});

it("Enter でも更新できる", async () => {
  const onClose = renderDialog(withStatus(null));
  await userEvent.type(input(), "昼休み{Enter}");
  expect(vi.mocked(publishEvent).mock.calls[0][0]).toMatchObject({ content: "昼休み" });
  expect(onClose).toHaveBeenCalledTimes(1);
});

it("期限を選ぶと expiration（今 + 秒）が付く", async () => {
  renderDialog(withStatus(null));
  await userEvent.type(input(), "会議");
  const oneHour = screen.getByRole("button", { name: "1 時間" });
  expect(screen.getByRole("button", { name: "なし" })).toHaveAttribute("aria-pressed", "true");
  await userEvent.click(oneHour);
  expect(oneHour).toHaveAttribute("aria-pressed", "true");
  const before = unixNow();
  await userEvent.click(update());
  const draft = vi.mocked(publishEvent).mock.calls[0][0];
  const expiration = Number(draft.tags.find((tag) => tag[0] === "expiration")?.[1]);
  expect(expiration).toBeGreaterThanOrEqual(before + 3600);
  expect(expiration).toBeLessThanOrEqual(unixNow() + 3600);
  expect(expiration).toBe((draft.created_at ?? 0) + 3600);
});

it("いま出しているステータスを入れておく。「消す」で空のステータスを出す（期限は付けない）", async () => {
  const onClose = renderDialog(withStatus("作業中"));
  expect(input()).toHaveValue("作業中");
  await userEvent.click(screen.getByRole("button", { name: "1 日" }));
  await userEvent.click(screen.getByRole("button", { name: "消す" }));
  expect(vi.mocked(publishEvent).mock.calls[0][0]).toMatchObject({
    kind: 30315,
    content: "",
    tags: [["d", "general"]],
  });
  expect(useToast.getState().queue).toEqual(["ステータスを消しました"]);
  expect(onClose).toHaveBeenCalledTimes(1);
});

it("消してある・期限切れのステータスと、Now Playing（music）は入れない", () => {
  for (const pubkey of [
    withStatus(""),
    withStatus("会議", [["expiration", String(unixNow() - 10)]]),
    withStatus("曲 - 歌手", [], "music"),
  ]) {
    const { unmount } = render(<StatusEditorDialog pubkey={pubkey} onClose={() => {}} />);
    expect(input()).toHaveValue("");
    expect(screen.queryByRole("button", { name: "消す" })).toBeNull();
    unmount();
  }
});

it("送れなかったら理由を出して、画面を残す（書いた内容もそのまま）", async () => {
  vi.mocked(publishEvent).mockRejectedValue(new Error("sign failed"));
  const onClose = renderDialog(withStatus(null));
  await userEvent.type(input(), "作業中");
  await userEvent.click(update());
  expect(screen.getByRole("alert")).toHaveTextContent(
    "ステータスを更新できませんでした。もう一度お試しください。",
  );
  expect(onClose).not.toHaveBeenCalled();
  expect(input()).toHaveValue("作業中");
  expect(useToast.getState().queue).toEqual([]);
});

it("100 文字を超える入力は受け付けない", async () => {
  renderDialog(withStatus(null));
  await userEvent.click(input());
  await userEvent.paste("あ".repeat(100));
  expect(input()).toHaveValue("あ".repeat(100));
  await userEvent.type(input(), "い");
  expect(input()).toHaveValue("あ".repeat(100));
});

it("ほかのアプリで出した長いステータスも、短くする編集はできる", async () => {
  renderDialog(withStatus("x".repeat(120)));
  expect(input()).toHaveValue("x".repeat(120));
  await userEvent.type(input(), "{Backspace}");
  expect(input()).toHaveValue("x".repeat(119));
});

it("「キャンセル」で閉じる（何も送らない）", async () => {
  const onClose = renderDialog(withStatus("作業中"));
  await userEvent.click(screen.getByRole("button", { name: "キャンセル" }));
  expect(onClose).toHaveBeenCalledTimes(1);
  expect(publishEvent).not.toHaveBeenCalled();
});

it("[レビュー] 期限つきのステータスは「いまのまま」が選ばれていて、文言だけ直しても期限が変わらない", async () => {
  const expiration = unixNow() + 900;
  renderDialog(withStatus("会議", [["expiration", String(expiration)]]));
  const keep = screen.getByRole("button", { name: "いまのまま（残り 15 分）" });
  expect(keep).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("button", { name: "なし" })).toHaveAttribute("aria-pressed", "false");
  await userEvent.type(input(), "中");
  await userEvent.click(update());
  expect(vi.mocked(publishEvent).mock.calls[0][0]).toMatchObject({
    content: "会議中",
    tags: [
      ["d", "general"],
      ["expiration", String(expiration)],
    ],
  });
});

it("[レビュー] 期限つきのステータスで「なし」を選ぶと、期限を外す", async () => {
  renderDialog(withStatus("会議", [["expiration", String(unixNow() + 900)]]));
  await userEvent.click(screen.getByRole("button", { name: "なし" }));
  expect(screen.getByRole("button", { name: /^いまのまま/ })).toHaveAttribute("aria-pressed", "false");
  await userEvent.click(update());
  expect(vi.mocked(publishEvent).mock.calls[0][0].tags).toEqual([["d", "general"]]);
});

it("[レビュー] 期限の無いステータスには「いまのまま」を出さない", () => {
  renderDialog(withStatus("作業中"));
  expect(screen.queryByRole("button", { name: /^いまのまま/ })).toBeNull();
  expect(screen.getByRole("button", { name: "なし" })).toHaveAttribute("aria-pressed", "true");
});

it("[レビュー] created_at は、いま出している版より後にする（時計の進んだ端末で出した版に負けない）", async () => {
  const ahead = unixNow() + 300;
  renderDialog(withStatus("作業中", [], "general", ahead));
  await userEvent.click(screen.getByRole("button", { name: "消す" }));
  expect(vi.mocked(publishEvent).mock.calls[0][0].created_at).toBe(ahead + 1);
});

it("[レビュー] 開いたあとに届いたステータスも入れる。書き始めていたら上書きしない", async () => {
  const key = generateSecretKey();
  const arrive = (content: string) =>
    act(() => {
      eventStore.add(
        finalizeEvent({ kind: 30315, created_at: unixNow() - 60, tags: [["d", "general"]], content }, key),
      );
    });
  const { unmount } = render(<StatusEditorDialog pubkey={getPublicKey(key)} onClose={() => {}} />);
  expect(input()).toHaveValue("");
  arrive("作業中");
  expect(input()).toHaveValue("作業中");
  expect(screen.getByRole("button", { name: "消す" })).toBeInTheDocument();
  unmount();

  const other = generateSecretKey();
  render(<StatusEditorDialog pubkey={getPublicKey(other)} onClose={() => {}} />);
  await userEvent.type(input(), "書きかけ");
  act(() => {
    eventStore.add(
      finalizeEvent(
        { kind: 30315, created_at: unixNow() - 60, tags: [["d", "general"]], content: "古い" },
        other,
      ),
    );
  });
  expect(input()).toHaveValue("書きかけ");
});

it("[レビュー] 送信中は Esc（cancel）で閉じない。ブラウザが閉じてしまったら（close）、開いている印を外す", async () => {
  vi.mocked(publishEvent).mockImplementation(() => new Promise(() => {}));
  const onClose = renderDialog(withStatus(null));
  await userEvent.type(input(), "作業中");
  await userEvent.click(update());
  const dialog = screen.getByRole("dialog", { name: "ステータスを更新" });
  expect(dialog).toHaveAttribute("aria-busy", "true");
  fireEvent(dialog, new Event("cancel", { cancelable: true }));
  expect(onClose).not.toHaveBeenCalled();
  fireEvent(dialog, new Event("close"));
  expect(onClose).toHaveBeenCalledTimes(1);
});
