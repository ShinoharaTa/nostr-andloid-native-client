import { finalizeEvent, generateSecretKey, type NostrEvent } from "nostr-tools/pure";
import { describe, expect, it } from "vitest";
import { t } from "../../i18n";
import {
  expiryLabel,
  isStatusVisible,
  STATUS_MAX_AGE_SEC,
  serviceLabelOf,
  sortStatuses,
  statusTypeOf,
} from "./statusModel";

const NOW = 1_791_283_000;
const DAY = 86400;

function status({
  d = "general",
  content = "作業中",
  createdAt = NOW - 60,
  expiration,
  kind = 30315,
}: {
  d?: string;
  content?: string;
  createdAt?: number;
  expiration?: string | number;
  kind?: number;
} = {}): NostrEvent {
  const tags = [["d", d]];
  if (expiration !== undefined) tags.push(["expiration", String(expiration)]);
  return finalizeEvent({ kind, created_at: createdAt, tags, content }, generateSecretKey());
}

/** id と created_at だけを見る並べ替え用の偽イベント */
function stub(id: string, createdAt: number): NostrEvent {
  return { id, created_at: createdAt, kind: 30315, pubkey: "", tags: [], content: "", sig: "" };
}

describe("statusTypeOf", () => {
  it("d の完全一致だけを種類にする", () => {
    expect(statusTypeOf(status({ d: "general" }))).toBe("general");
    expect(statusTypeOf(status({ d: "music" }))).toBe("music");
    expect(statusTypeOf(status({ d: "presence" }))).toBeNull();
    expect(statusTypeOf(status({ d: "general,expiration=1,r=x" }))).toBeNull();
  });
});

describe("isStatusVisible", () => {
  it("general / music は出す。presence・壊れた d・kind 違いは出さない", () => {
    expect(isStatusVisible(status({ d: "general" }), null, NOW)).toBe(true);
    expect(isStatusVisible(status({ d: "music" }), null, NOW)).toBe(true);
    expect(isStatusVisible(status({ d: "presence" }), null, NOW)).toBe(false);
    expect(isStatusVisible(status({ d: "general,expiration=1791283100" }), null, NOW)).toBe(false);
    expect(isStatusVisible(status({ kind: 1 }), null, NOW)).toBe(false);
  });

  it("空・空白だけの content（クリア）は出さない", () => {
    expect(isStatusVisible(status({ content: "" }), null, NOW)).toBe(false);
    expect(isStatusVisible(status({ content: " \n\t　" }), null, NOW)).toBe(false);
  });

  it("期限が過ぎた（expiration <= now）ものは出さない。期限が未来なら出す", () => {
    expect(isStatusVisible(status({ expiration: NOW }), null, NOW)).toBe(false);
    expect(isStatusVisible(status({ expiration: NOW - 1 }), null, NOW)).toBe(false);
    expect(isStatusVisible(status({ expiration: NOW + 1 }), null, NOW)).toBe(true);
  });

  it("期限なしは 30 日まで（31 日前は出さない・29 日前は出す）", () => {
    expect(STATUS_MAX_AGE_SEC).toBe(30 * DAY);
    expect(isStatusVisible(status({ createdAt: NOW - 31 * DAY }), null, NOW)).toBe(false);
    expect(isStatusVisible(status({ createdAt: NOW - 29 * DAY }), null, NOW)).toBe(true);
  });

  it("期限が未来なら 40 日前のものでも出す", () => {
    expect(isStatusVisible(status({ createdAt: NOW - 40 * DAY, expiration: NOW + 60 }), null, NOW)).toBe(
      true,
    );
  });

  it("期限が数値でなければ期限なし扱い", () => {
    expect(isStatusVisible(status({ expiration: "soon" }), null, NOW)).toBe(true);
    expect(isStatusVisible(status({ expiration: "soon", createdAt: NOW - 31 * DAY }), null, NOW)).toBe(false);
  });

  it("filter = music のとき general は出さない（逆も同じ）", () => {
    expect(isStatusVisible(status({ d: "general" }), "music", NOW)).toBe(false);
    expect(isStatusVisible(status({ d: "music" }), "music", NOW)).toBe(true);
    expect(isStatusVisible(status({ d: "music" }), "general", NOW)).toBe(false);
    expect(isStatusVisible(status({ d: "general" }), "general", NOW)).toBe(true);
  });
});

describe("sortStatuses", () => {
  it("新しい順。同時刻は id の昇順。元の配列は変えない", () => {
    const input = [stub("b", 100), stub("c", 200), stub("a", 100), stub("d", 50)];
    expect(sortStatuses(input).map((e) => e.id)).toEqual(["c", "a", "b", "d"]);
    expect(input.map((e) => e.id)).toEqual(["b", "c", "a", "d"]);
  });
});

describe("serviceLabelOf", () => {
  it("既知のホストはサービス名", () => {
    expect(serviceLabelOf("https://open.spotify.com/track/x")).toBe("Spotify");
    expect(serviceLabelOf("https://music.apple.com/jp/album/x")).toBe("Apple Music");
    expect(serviceLabelOf("https://music.youtube.com/watch?v=x")).toBe("YouTube Music");
    expect(serviceLabelOf("https://youtube.com/watch?v=x")).toBe("YouTube");
    expect(serviceLabelOf("https://www.youtube.com/watch?v=x")).toBe("YouTube");
    expect(serviceLabelOf("https://m.youtube.com/watch?v=x")).toBe("YouTube");
    expect(serviceLabelOf("https://youtu.be/x")).toBe("YouTube");
    expect(serviceLabelOf("https://soundcloud.com/a/b")).toBe("SoundCloud");
    expect(serviceLabelOf("https://artist.bandcamp.com/track/x")).toBe("Bandcamp");
  });

  it("それ以外はホスト名（先頭の www. を外す）。http も対象", () => {
    expect(serviceLabelOf("https://www.example.com/a")).toBe("example.com");
    expect(serviceLabelOf("http://chouseisan.com/s?h=x")).toBe("chouseisan.com");
  });

  it("http(s) 以外・壊れた URL は null", () => {
    expect(serviceLabelOf("spotify:search:x")).toBeNull();
    expect(serviceLabelOf("hittps://x")).toBeNull();
    expect(serviceLabelOf("https://")).toBeNull();
  });
});

describe("expiryLabel", () => {
  it("残りの秒数で表記を変える", () => {
    expect(expiryLabel(NOW + 59, NOW, t)).toBe("まもなく終了");
    expect(expiryLabel(NOW + 60, NOW, t)).toBe("残り 1 分");
    expect(expiryLabel(NOW + 61, NOW, t)).toBe("残り 2 分");
    expect(expiryLabel(NOW + 3600, NOW, t)).toBe("残り 1 時間");
    expect(expiryLabel(NOW + 86399, NOW, t)).toBe("残り 23 時間");
  });

  it("24 時間以上は「M/D HH:mm まで」（端末のタイムゾーン）", () => {
    const expiration = NOW + 86400;
    const date = new Date(expiration * 1000);
    const pad = (n: number) => String(n).padStart(2, "0");
    const at = `${date.getMonth() + 1}/${date.getDate()} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
    expect(expiryLabel(expiration, NOW, t)).toBe(`${at} まで`);
    expect(expiryLabel(expiration, NOW, t)).toMatch(/^\d{1,2}\/\d{1,2} \d{2}:\d{2} まで$/);
  });
});
