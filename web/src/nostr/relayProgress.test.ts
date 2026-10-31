import { finalizeEvent, generateSecretKey } from "nostr-tools/pure";
import type { Subscription } from "rxjs";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ScriptedWebSocket } from "../test/scriptedWebSocket";
import { type RelayProgress, requestFrom, subscribeProgress } from "./pool";
import { eventStore } from "./store";

// [#955] カラムの取得範囲を数えるための、リレーごとの受信と EOSE（subscribeProgress）と、1 リレーへの遡り（requestFrom）

let subscription: Subscription | undefined;

beforeEach(() => {
  vi.stubGlobal("WebSocket", ScriptedWebSocket);
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval"] });
});

afterEach(() => {
  subscription?.unsubscribe();
  subscription = undefined;
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

function note(createdAt: number) {
  return finalizeEvent({ kind: 1, created_at: createdAt, tags: [], content: "hi" }, generateSecretKey());
}

function socketOf(url: string): ScriptedWebSocket {
  const socket = ScriptedWebSocket.latest(url);
  if (!socket) throw new Error(`no socket for ${url}`);
  return socket;
}

it("subscribeProgress: 受信と EOSE を、どのリレーからかと一緒に流し、イベントは EventStore へ入れる", () => {
  const a = "wss://progress-a.example/";
  const b = "wss://progress-b.example/";
  const seen: RelayProgress[] = [];
  subscription = subscribeProgress([a, b], [{ kinds: [1], limit: 100 }]).subscribe((p) => seen.push(p));
  for (const url of [a, b]) socketOf(url).accept();

  const fromA = note(1_000);
  const [reqA] = socketOf(a).reqs();
  const [reqB] = socketOf(b).reqs();
  socketOf(a).push(["EVENT", reqA.id, fromA]);
  socketOf(a).push(["EOSE", reqA.id]);
  socketOf(b).push(["EOSE", reqB.id]);

  expect(seen).toEqual([
    { type: "EVENT", from: a, createdAt: 1_000 },
    { type: "EOSE", from: a },
    { type: "EOSE", from: b },
  ]);
  expect(eventStore.getEvent(fromA.id)).toBeDefined();
});

it("requestFrom: そのリレーにだけ REQ を送り、created_at を流して EOSE で終わる（CLOSE を送る）", () => {
  const url = "wss://older-eose.example/";
  const seen: (number | "EOSE" | "TIMEOUT")[] = [];
  let done = false;
  subscription = requestFrom(url, [{ kinds: [1], limit: 100, until: 1_000 }], 6_000).subscribe({
    next: (r) => seen.push(r),
    complete: () => {
      done = true;
    },
  });
  const socket = socketOf(url);
  socket.accept();
  const [req] = socket.reqs();
  expect(req.filters).toEqual([{ kinds: [1], limit: 100, until: 1_000 }]);

  const older = note(900);
  socket.push(["EVENT", req.id, older]);
  socket.push(["EOSE", req.id]);
  expect(seen).toEqual([900, "EOSE"]);
  expect(done).toBe(true);
  expect(socket.sent).toContainEqual(["CLOSE", req.id]);
  expect(eventStore.getEvent(older.id)).toBeDefined();
});

it("requestFrom: 待つ時間内に EOSE が来なければ TIMEOUT を流して終わる", () => {
  const url = "wss://older-timeout.example/";
  const seen: (number | "EOSE" | "TIMEOUT")[] = [];
  let done = false;
  subscription = requestFrom(url, [{ kinds: [1], limit: 100, until: 1_000 }], 6_000).subscribe({
    next: (r) => seen.push(r),
    complete: () => {
      done = true;
    },
  });
  const socket = socketOf(url);
  socket.accept();
  const [req] = socket.reqs();

  vi.advanceTimersByTime(5_999);
  expect(done).toBe(false);
  vi.advanceTimersByTime(1);
  expect(seen).toEqual(["TIMEOUT"]);
  expect(done).toBe(true);
  expect(socket.sent).toContainEqual(["CLOSE", req.id]);
});
