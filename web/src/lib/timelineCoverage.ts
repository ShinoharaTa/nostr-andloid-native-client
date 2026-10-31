/**
 * [#955] 1 本のカラムについて、リレーごとに「どの時刻まで遡って取れたか」を覚え、
 * 画面に並べてよい下限（floor）と、次に遡って聞くリレー（olderRequests）を決める。
 * ネイティブ data/TimelineCoverage.kt の写し。
 *
 * リレーごとに同じ件数（limit）で聞くと、投稿の多いリレーは 1 時間分、少ないリレーは 5 時間分、のように
 * 取れる幅が違う。いちばん古い投稿の時刻から全リレーへ続きを聞くと、多いリレーの 1〜5 時間前が抜ける。
 * そこで、まだ先があるリレーのうちいちばん浅いところで止まっているリレーの境界より古い投稿は出さず、
 * 遡るときは、リレーごとに自分の境界から聞く。
 *
 * 状態を持つだけで通信はしない。
 */

/** 遡りの要求。until はそのリレーの境界、seq は返事（onEose / onOlderTimeout）に添える番号 */
export type OlderRequest = { until: number; seq: number };

type RelayCoverage = {
  /** このリレーから届いた中でいちばん古い created_at */
  oldest: number | null;
  /** 最初の購読（EOSE まで）で届いた件数。EOSE のときに limit 未満なら、このリレーにはもう先が無い */
  initialReceived: number;
  /** 最初の購読の EOSE が届いた。届くまでは境界が定まらないので、下限にも遡りにも使わない */
  initialDone: boolean;
  /** 遡りの要求を出して返事を待っている */
  olderInFlight: boolean;
  /** 出した遡りの要求の番号。前の要求の遅れた返事（EOSE・時間切れ）を、今の要求のものと取り違えないため */
  olderSeq: number;
  /** 遡りの要求を出したときの oldest。返事でこれより古くならなければ、もう先が無い */
  oldestAtRequest: number | null;
  /** もう遡れない（何も無い・遡っても古くならなかった）。下限の計算から外す */
  exhausted: boolean;
  /** 遡りの要求に何も返さなかった。止まらないよう下限の計算から外す（届いたら戻す。次に遡るときはまた聞く） */
  unresponsive: boolean;
};

/** 境界として使える（最初の取得が終わっていて、まだ先がある） */
function settled(r: RelayCoverage): r is RelayCoverage & { oldest: number } {
  return r.initialDone && !r.exhausted && r.oldest !== null;
}

export class TimelineCoverage {
  private readonly relays = new Map<string, RelayCoverage>();

  /** @param limit 1 回の REQ で聞く件数。最初の取得でこれより少なく返したリレーは、持っている分をすべて返したとみなす（0 なら件数では判断しない） */
  constructor(private readonly limit: number) {}

  private relay(url: string): RelayCoverage {
    let r = this.relays.get(url);
    if (!r) {
      r = {
        oldest: null,
        initialReceived: 0,
        initialDone: false,
        olderInFlight: false,
        olderSeq: 0,
        oldestAtRequest: null,
        exhausted: false,
        unresponsive: false,
      };
      this.relays.set(url, r);
    }
    return r;
  }

  /** イベントが届いた。older は遡りの要求への返事か（false = カラムの購読そのもの） */
  onEvent(relay: string, createdAt: number, older: boolean): void {
    const r = this.relay(relay);
    r.oldest = r.oldest === null ? createdAt : Math.min(r.oldest, createdAt);
    if (older) r.unresponsive = false;
    else if (!r.initialDone) r.initialReceived++;
  }

  /** EOSE が届いた。olderSeq は遡りの要求への返事ならその番号（olderRequests が返したもの）、カラムの購読なら null */
  onEose(relay: string, olderSeq: number | null): void {
    const r = this.relay(relay);
    if (olderSeq !== null) {
      if (!r.olderInFlight || olderSeq !== r.olderSeq) return;
      r.olderInFlight = false;
      const before = r.oldestAtRequest;
      const after = r.oldest;
      if (before === null || after === null || after >= before) r.exhausted = true;
    } else if (!r.initialDone) {
      r.initialDone = true;
      // 聞いた件数より少なければ、持っている分をすべて返した（遡っても何も無いので、下限を持ち上げない）
      if (r.initialReceived === 0 || r.initialReceived < this.limit) r.exhausted = true;
    }
  }

  /** 遡りの要求（olderSeq）に、待つ時間内に EOSE が来なかった。何も届いていなければ応えないリレーとして外す */
  onOlderTimeout(relay: string, olderSeq: number): void {
    const r = this.relays.get(relay);
    if (!r?.olderInFlight || olderSeq !== r.olderSeq) return;
    r.olderInFlight = false;
    if (r.oldest === r.oldestAtRequest) r.unresponsive = true;
  }

  /** 出している遡りの要求を取り下げる（返事はもう数えない）。遅れて届く EOSE・時間切れは無視される */
  cancelOlder(): void {
    for (const r of this.relays.values()) r.olderInFlight = false;
  }

  /**
   * 画面に並べてよい下限（これより古い投稿は、どこかのリレーの分が抜けているかもしれない）。
   * まだ先があるリレーの境界のうち、いちばん新しいもの。そういうリレーが無ければ null（下限なし）。
   * 最初の取得が終わっていないリレーは数えない（届いた分だけで境界を置くと、手元にある投稿がいったん全部隠れてしまう）
   */
  floor(): number | null {
    return this.boundary(false);
  }

  private boundary(includeUnresponsive: boolean): number | null {
    let floor: number | null = null;
    for (const r of this.relays.values()) {
      if (!settled(r) || (!includeUnresponsive && r.unresponsive)) continue;
      if (floor === null || r.oldest > floor) floor = r.oldest;
    }
    return floor;
  }

  /**
   * 次に遡って聞くリレーと、その要求。聞くと決めたリレーは返事待ちにする。
   * 聞くのは、下限から「いまから下限までの幅」ぶん深いところまでに境界があるリレー（下限の 2 倍の深さまで）。
   * 下限で止まっているリレーは必ず入る。ずっと深くまで取れているリレーには、下限が追いつくまで聞かない（取りすぎない）。
   * 応えなかったリレーにもまた聞く（下限からは外れているが、応えれば戻る）。応えるリレーが無ければ、応えなかったリレーの境界を下限の代わりにする
   */
  olderRequests(now: number): Map<string, OlderRequest> {
    const requests = new Map<string, OlderRequest>();
    const base = this.floor() ?? this.boundary(true);
    if (base === null) return requests;
    const reach = base - Math.max(now - base, 0);
    for (const [url, r] of this.relays) {
      if (!settled(r) || r.olderInFlight || r.oldest < reach) continue;
      r.olderInFlight = true;
      r.oldestAtRequest = r.oldest;
      r.olderSeq++;
      requests.set(url, { until: r.oldest, seq: r.olderSeq });
    }
    return requests;
  }

  /** 返事を待っている遡りの要求があるか（表示の「読み込み中」用） */
  loadingOlder(): boolean {
    for (const r of this.relays.values()) if (r.olderInFlight) return true;
    return false;
  }
}
