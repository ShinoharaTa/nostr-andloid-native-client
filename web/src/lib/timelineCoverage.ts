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
  /** 最初の購読（EOSE まで）で届いた件数。EOSE のときに 0 なら、このリレーには何も無い */
  initialReceived: number;
  initialDone: boolean;
  /** 遡りの要求を出して返事を待っている */
  olderInFlight: boolean;
  /** 出した遡りの要求の番号。前の要求の遅れた返事（EOSE・時間切れ）を、今の要求のものと取り違えないため */
  olderSeq: number;
  /** 遡りの要求を出したときの oldest。返事でこれより古くならなければ、もう先が無い */
  oldestAtRequest: number | null;
  /** もう遡れない（何も無い・遡っても古くならなかった）。下限の計算から外す */
  exhausted: boolean;
  /** 遡りの要求に何も返さなかった。止まらないよう下限の計算から外す（届いたら戻す） */
  unresponsive: boolean;
};

export class TimelineCoverage {
  private readonly relays = new Map<string, RelayCoverage>();

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
      if (r.initialReceived === 0) r.exhausted = true;
    }
  }

  /** 遡りの要求（olderSeq）に、待つ時間内に EOSE が来なかった。何も届いていなければ応えないリレーとして外す */
  onOlderTimeout(relay: string, olderSeq: number): void {
    const r = this.relays.get(relay);
    if (!r?.olderInFlight || olderSeq !== r.olderSeq) return;
    r.olderInFlight = false;
    if (r.oldest === r.oldestAtRequest) r.unresponsive = true;
  }

  /**
   * 画面に並べてよい下限（これより古い投稿は、どこかのリレーの分が抜けているかもしれない）。
   * まだ先があるリレーの境界のうち、いちばん新しいもの。そういうリレーが無ければ null（下限なし）
   */
  floor(): number | null {
    let floor: number | null = null;
    for (const r of this.relays.values()) {
      if (r.exhausted || r.unresponsive || r.oldest === null) continue;
      if (floor === null || r.oldest > floor) floor = r.oldest;
    }
    return floor;
  }

  /**
   * 次に遡って聞くリレーと、その要求。聞くと決めたリレーは返事待ちにする。
   * 聞くのは、下限から「いまから下限までの幅」ぶん深いところまでに境界があるリレー（下限の 2 倍の深さまで）。
   * 下限で止まっているリレーは必ず入る。ずっと深くまで取れているリレーには、下限が追いつくまで聞かない（取りすぎない）
   */
  olderRequests(now: number): Map<string, OlderRequest> {
    const requests = new Map<string, OlderRequest>();
    const floor = this.floor();
    if (floor === null) return requests;
    const reach = floor - Math.max(now - floor, 0);
    for (const [url, r] of this.relays) {
      if (r.exhausted || r.olderInFlight || r.oldest === null || r.oldest < reach) continue;
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
