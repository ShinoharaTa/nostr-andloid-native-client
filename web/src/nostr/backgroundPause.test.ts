import { finalizeEvent, generateSecretKey } from "nostr-tools/pure";
import type { Subscription } from "rxjs";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { pauseNip46, resumeNip46 } from "../signer/nip46";
import { ScriptedWebSocket } from "../test/scriptedWebSocket";
import { BG_PAUSE_DELAY_MS, startBackgroundPause } from "./backgroundPause";
import { SINCE_MARGIN_SEC, subscribeTo } from "./pool";
import { retryUnsent } from "./publish";

/** NIP-46 の一時停止・再開と再送の呼ばれた順 */
const calls = vi.hoisted(() => [] as string[]);

// 送信キューは呼ばれたかだけを見る
vi.mock("./publish", () => ({ retryUnsent: vi.fn(() => calls.push("retryUnsent")) }));
vi.mock("../signer/nip46", () => ({
  pauseNip46: vi.fn(() => calls.push("pauseNip46")),
  resumeNip46: vi.fn(() => calls.push("resumeNip46")),
}));

const NOW = 1_700_000_000;
let visibility: DocumentVisibilityState = "visible";
let subscription: Subscription | undefined;
let stop: (() => void) | undefined;

function setVisibility(next: DocumentVisibilityState) {
  visibility = next;
  document.dispatchEvent(new Event("visibilitychange"));
}

beforeEach(() => {
  vi.stubGlobal("WebSocket", ScriptedWebSocket);
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval", "Date"] });
  vi.setSystemTime(NOW * 1000);
  visibility = "visible";
  vi.spyOn(document, "visibilityState", "get").mockImplementation(() => visibility);
  vi.mocked(retryUnsent).mockClear();
  vi.mocked(pauseNip46).mockClear();
  vi.mocked(resumeNip46).mockClear();
  calls.length = 0;
});

afterEach(() => {
  subscription?.unsubscribe();
  subscription = undefined;
  stop?.();
  stop = undefined;
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/** url へ購読を張って接続を確立し、created_at の 1 件を受ける。戻り値は接続と REQ の id */
function subscribeAndReceive(url: string, createdAt: number) {
  subscription = subscribeTo([url], [{ kinds: [1], limit: 50 }]).subscribe();
  const socket = ScriptedWebSocket.latest(url);
  if (!socket) throw new Error("no socket");
  socket.accept();
  const [req] = socket.reqs();
  socket.push([
    "EVENT",
    req.id,
    finalizeEvent({ kind: 1, created_at: createdAt, tags: [], content: "hi" }, generateSecretKey()),
  ]);
  return { socket, id: req.id };
}

it("非表示が 4 分 59 秒では閉じず、5 分で全リレーを閉じ、表示に戻るとすぐ since 差分で張り直して未送信を再送する", async () => {
  const url = "wss://pause.example/";
  const { socket: first, id } = subscribeAndReceive(url, NOW - 30);
  stop = startBackgroundPause();

  setVisibility("hidden");
  await vi.advanceTimersByTimeAsync(BG_PAUSE_DELAY_MS - 1_000);
  expect(first.readyState).toBe(1);
  expect(first.sent).not.toContainEqual(["CLOSE", id]);

  await vi.advanceTimersByTimeAsync(1_000);
  expect(first.sent).toContainEqual(["CLOSE", id]);
  // 接続は keepAlive（30 秒）を待たずに閉じる
  await vi.advanceTimersByTimeAsync(100);
  expect(first.readyState).toBe(3);
  expect(retryUnsent).not.toHaveBeenCalled();

  setVisibility("visible");
  expect(retryUnsent).toHaveBeenCalledTimes(1);
  const second = ScriptedWebSocket.latest(url);
  if (!second || second === first) throw new Error("not reconnected");
  second.accept();
  expect(second.reqs().map((r) => r.filters)).toEqual([
    [{ kinds: [1], limit: 50, since: NOW - 30 - SINCE_MARGIN_SEC }],
  ]);
});

it("5 分より前に表示に戻れば何もしない（閉じない・再送しない）", async () => {
  const url = "wss://short-hide.example/";
  const { socket, id } = subscribeAndReceive(url, NOW - 30);
  stop = startBackgroundPause();

  setVisibility("hidden");
  await vi.advanceTimersByTimeAsync(BG_PAUSE_DELAY_MS - 1_000);
  setVisibility("visible");
  await vi.advanceTimersByTimeAsync(BG_PAUSE_DELAY_MS);
  expect(socket.readyState).toBe(1);
  expect(socket.sent).not.toContainEqual(["CLOSE", id]);
  expect(socket.reqs()).toHaveLength(1);
  expect(retryUnsent).not.toHaveBeenCalled();
  expect(ScriptedWebSocket.latest(url)).toBe(socket);
});

it("5 分で NIP-46 も一時停止し、表示に戻ると再送の前に NIP-46 を再開する", async () => {
  subscribeAndReceive("wss://nip46-order.example/", NOW - 30);
  stop = startBackgroundPause();

  setVisibility("hidden");
  await vi.advanceTimersByTimeAsync(BG_PAUSE_DELAY_MS - 1_000);
  expect(pauseNip46).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1_000);
  expect(calls).toEqual(["pauseNip46"]);

  setVisibility("visible");
  // 再送の署名が NIP-46 のことがあるので、再開してから再送する
  expect(calls).toEqual(["pauseNip46", "resumeNip46", "retryUnsent"]);
});

it("監視をやめると NIP-46 も再開する", async () => {
  stop = startBackgroundPause();
  setVisibility("hidden");
  await vi.advanceTimersByTimeAsync(BG_PAUSE_DELAY_MS);
  expect(pauseNip46).toHaveBeenCalledTimes(1);

  stop();
  stop = undefined;

  expect(resumeNip46).toHaveBeenCalledTimes(1);
});
