import { describe, expect, it } from "vitest";
import { TimelineCoverage } from "./timelineCoverage";

// [#955] リレーごとの取得範囲の差で、遡ったタイムラインに歯抜けが出ないこと（ネイティブ TimelineCoverageTest と同じ）。
// A は投稿が多く 1 時間分、B は少なく 5 時間分を最初に返す、という状況を基本にする。

const NOW = 1_800_000_000;
const HOUR = 3_600;
const LIMIT = 100;

/** relay から、from 〜 to（秒前）の間に count 件、新しい順に届いて EOSE。seq は遡りの要求の番号（購読そのものなら null） */
function receive(
  c: TimelineCoverage,
  relay: string,
  from: number,
  to: number,
  count: number,
  seq: number | null = null,
) {
  for (let i = 0; i < count; i++) {
    c.onEvent(relay, NOW - from - Math.floor(((to - from) * i) / Math.max(count - 1, 1)), seq !== null);
  }
  c.onEose(relay, seq);
}

function untils(c: TimelineCoverage): Record<string, number> {
  return Object.fromEntries([...c.olderRequests(NOW)].map(([relay, r]) => [relay, r.until]));
}

function twoRelays(): TimelineCoverage {
  const c = new TimelineCoverage(LIMIT);
  receive(c, "A", 0, 1 * HOUR, LIMIT);
  receive(c, "B", 0, 5 * HOUR, LIMIT);
  return c;
}

function requestOf(c: TimelineCoverage, relay: string) {
  const request = c.olderRequests(NOW).get(relay);
  if (!request) throw new Error(`${relay} に遡りの要求が出ていない`);
  return request;
}

/** [#962] A を 3 時間前まで遡ったところ（下限は 3 時間前。B は 5 時間前） */
function pagedToThreeHours(): TimelineCoverage {
  const c = twoRelays();
  receive(c, "A", 1 * HOUR, 2 * HOUR, LIMIT, requestOf(c, "A").seq);
  receive(c, "A", 2 * HOUR, 3 * HOUR, LIMIT, requestOf(c, "A").seq);
  return c;
}

describe("TimelineCoverage", () => {
  it("下限は、いちばん浅いところで止まっているリレーの境界", () => {
    // B の 1〜5 時間前は手元にあっても、A の分が抜けているかもしれないので出さない
    expect(twoRelays().floor()).toBe(NOW - 1 * HOUR);
  });

  it("下限の近くのリレーにだけ、それぞれの境界から聞く", () => {
    const c = twoRelays();
    // 下限は 1 時間前。2 時間前より深くまで取れている B には、まだ聞かない
    expect(untils(c)).toEqual({ A: NOW - 1 * HOUR });
    expect(c.loadingOlder()).toBe(true);
    // 返事待ちの A には重ねて聞かない
    expect(untils(c)).toEqual({});
  });

  it("浅いリレーを遡ると下限が下がり、深いリレーにも追いついたら聞く", () => {
    const c = twoRelays();
    receive(c, "A", 1 * HOUR, 2 * HOUR, LIMIT, requestOf(c, "A").seq);
    expect(c.floor()).toBe(NOW - 2 * HOUR);
    // 下限 2 時間前 → 4 時間前までに境界があるリレーに聞く。B（5 時間前）はまだ
    const second = c.olderRequests(NOW);
    expect([...second.keys()]).toEqual(["A"]);
    receive(c, "A", 2 * HOUR, 3 * HOUR, LIMIT, second.get("A")?.seq ?? null);
    // 下限 3 時間前 → 6 時間前まで。B も聞く（それぞれ自分の境界から）
    expect(untils(c)).toEqual({ A: NOW - 3 * HOUR, B: NOW - 5 * HOUR });
  });

  it("何も無いリレーは数えない", () => {
    const c = twoRelays();
    receive(c, "C", 0, 0, 0);
    expect(c.floor()).toBe(NOW - 1 * HOUR);
    expect(untils(c)).not.toHaveProperty("C");
  });

  it("聞いた件数より少なく返したリレーは、もう先が無いので下限を持ち上げない", () => {
    const c = twoRelays();
    // 投稿の少ないリレー: 直近 5 分の 3 件しか無い。ここを下限にすると、開くたびに 5 分ぶんしか出なくなる
    receive(c, "C", 0, 5 * 60, 3);
    expect(c.floor()).toBe(NOW - 1 * HOUR);
    expect(untils(c)).not.toHaveProperty("C");
  });

  it("遡っても古いものが無かったリレーは外し、下限は先へ進む", () => {
    const c = twoRelays();
    // A に遡って聞いたら、境界より古いものは無かった（境界ちょうどの重複だけ）
    receive(c, "A", 1 * HOUR, 1 * HOUR, 1, requestOf(c, "A").seq);
    expect(c.floor()).toBe(NOW - 5 * HOUR);
    // B も遡れなくなったら下限は無くなる（手元にある分をすべて出す）
    receive(c, "B", 5 * HOUR, 5 * HOUR, 0, requestOf(c, "B").seq);
    expect(c.floor()).toBeNull();
    expect(untils(c)).toEqual({});
  });

  it("応えないリレーは外し、あとで応えても下限を新しい方へ戻さない", () => {
    const c = twoRelays();
    c.onOlderTimeout("A", requestOf(c, "A").seq);
    expect(c.loadingOlder()).toBe(false);
    // A を待たずに先へ進める
    expect(c.floor()).toBe(NOW - 5 * HOUR);
    // [#962] 遅れて A から届いても、下限は新しい方へ戻さない（1.5〜5 時間前の行を隠さない）。A にはまた聞く
    c.onEvent("A", NOW - 90 * 60, true);
    expect(c.floor()).toBe(NOW - 5 * HOUR);
    expect(untils(c)).toEqual({ A: NOW - 90 * 60, B: NOW - 5 * HOUR });
  });

  it("下限を決めるリレーが無くなっても、応えなかったリレーにはもう一度聞く", () => {
    const c = twoRelays();
    c.onOlderTimeout("A", requestOf(c, "A").seq);
    // 下限は B の境界へ。次に遡るときは B と、応えなかった A にもまた聞く
    const second = c.olderRequests(NOW);
    expect([...second.keys()].sort()).toEqual(["A", "B"]);
    // B は遡っても何も無かった。A はまた応えない → 下限を決めるリレーが無くなる
    receive(c, "B", 5 * HOUR, 5 * HOUR, 0, second.get("B")?.seq ?? null);
    c.onOlderTimeout("A", second.get("A")?.seq ?? 0);
    expect(c.floor()).toBeNull();
    // それでも A にはもう一度聞く（ここで止まると、二度と遡れなくなる）
    const third = requestOf(c, "A");
    expect(third.until).toBe(NOW - 1 * HOUR);
    // [#962] 応えても、下限なし（手元の分をすべて出している）から新しい方へは戻さない
    receive(c, "A", 1 * HOUR, 2 * HOUR, LIMIT, third.seq);
    expect(c.floor()).toBeNull();
  });

  it("出している要求を取り下げても、そのリレーを「応えない」扱いにしない", () => {
    const c = twoRelays();
    const a = requestOf(c, "A");
    // カラムを閉じた（張り直した）。返事はもう数えない
    c.cancelOlder();
    expect(c.loadingOlder()).toBe(false);
    c.onOlderTimeout("A", a.seq);
    c.onEose("A", a.seq);
    // A は下限に残り、次に遡るときにまた聞く
    expect(c.floor()).toBe(NOW - 1 * HOUR);
    expect(untils(c)).toEqual({ A: NOW - 1 * HOUR });
  });

  it("前の要求への遅れた返事を、今の要求のものと取り違えない", () => {
    const c = twoRelays();
    const first = requestOf(c, "A");
    c.onOlderTimeout("A", first.seq); // 1 回目は時間切れ（A は外れる）
    c.onEvent("A", NOW - 2 * HOUR, true); // 遅れて届いた → 戻る
    // [#962] 下限は B の境界（5 時間前）のままなので、B にも聞く
    const second = c.olderRequests(NOW);
    expect([...second.keys()].sort()).toEqual(["A", "B"]);
    // 1 回目の遅れた EOSE・時間切れは、2 回目の要求を閉じない
    c.onEose("A", first.seq);
    c.onOlderTimeout("A", first.seq);
    expect(c.loadingOlder()).toBe(true);
    receive(c, "A", 2 * HOUR, 3 * HOUR, 50, second.get("A")?.seq ?? null);
    receive(c, "B", 5 * HOUR, 6 * HOUR, 50, second.get("B")?.seq ?? null);
    expect(c.loadingOlder()).toBe(false);
    // [#962] A が外れている間に B の境界（5 時間前）まで下がった下限は、A が戻っても新しい方へは戻さない
    expect(c.floor()).toBe(NOW - 5 * HOUR);
  });

  it("あとから届く新着は下限を動かさない", () => {
    const c = twoRelays();
    c.onEvent("A", NOW + 60, false);
    c.onEvent("B", NOW + 120, false);
    expect(c.floor()).toBe(NOW - 1 * HOUR);
  });

  it("最初の取得が終わるまでは下限を置かず、遡りもしない", () => {
    const c = new TimelineCoverage(LIMIT);
    c.onEvent("A", NOW - 10 * 60, false);
    c.onEvent("A", NOW - 20 * 60, false);
    // 届いた分で境界を置くと、手元の投稿がいったん全部隠れて、届くたびに戻ってくる（ちらつく）
    expect(c.floor()).toBeNull();
    expect(untils(c)).toEqual({});
    receive(c, "A", 20 * 60, 1 * HOUR, LIMIT);
    expect(c.floor()).toBe(NOW - 1 * HOUR);
  });

  it("3 時間遡ったあとに浅い境界のリレーが加わっても、下限は戻らない", () => {
    const c = pagedToThreeHours();
    expect(c.floor()).toBe(NOW - 3 * HOUR);
    // [#962] 遅れて加わったリレー C（接続が遅れた・アウトボックス）は 30 分前までしか返さなかった
    receive(c, "C", 0, 30 * 60, LIMIT);
    // 下限を 30 分前へ戻すと、読んでいる途中の 30 分〜3 時間前の行が消える。C の分の歯抜けは受け入れる
    expect(c.floor()).toBe(NOW - 3 * HOUR);
    // C にも自分の境界から聞く
    expect(untils(c).C).toBe(NOW - 30 * 60);
  });

  it("戻さない下限も、古い方へは動く", () => {
    const c = pagedToThreeHours();
    receive(c, "C", 0, 30 * 60, LIMIT);
    const requests = c.olderRequests(NOW);
    receive(c, "A", 3 * HOUR, 4 * HOUR + 30 * 60, LIMIT, requests.get("A")?.seq ?? null);
    receive(c, "C", 30 * 60, 4 * HOUR, LIMIT, requests.get("C")?.seq ?? null);
    // [#962] いちばん浅いのは C（4 時間前）。今の下限（3 時間前）より古いので、そこまで下がる
    expect(c.floor()).toBe(NOW - 4 * HOUR);
  });

  it("数え直したら、新しい値で下限が決まる", () => {
    const held = pagedToThreeHours();
    receive(held, "C", 0, 30 * 60, LIMIT);
    expect(held.floor()).toBe(NOW - 3 * HOUR);
    // [#962] REQ が変わったら数え手を作り直す（useColumnFeed）。前の下限は持ち越さない
    const fresh = new TimelineCoverage(LIMIT);
    receive(fresh, "C", 0, 30 * 60, LIMIT);
    expect(fresh.floor()).toBe(NOW - 30 * 60);
  });
});
