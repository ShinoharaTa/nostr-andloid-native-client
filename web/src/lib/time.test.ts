import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import {
  reloadTimeDisplayForTest,
  setTimeDisplay,
  TIME_DISPLAY_KEY,
  useTimeDisplay,
} from "../features/settings/timeDisplay";
import { formatAbsoluteTime, relativeTime, type TimeDisplay, timeLabel } from "./time";

const NOW = 1_800_000_000;

it.each([
  [0, "now"],
  [9, "now"],
  [10, "10s"],
  [59, "59s"],
  [60, "1m"],
  [3599, "59m"],
  [3600, "1h"],
  [86399, "23h"],
  [86400, "1d"],
  [604799, "6d"],
  [604800, "1w"],
  [604800 * 5 + 3, "5w"],
])("%i 秒前は %s", (ago, expected) => {
  expect(relativeTime(NOW - ago, NOW)).toBe(expected);
});

it("未来の時刻（時計のずれ）は now", () => {
  expect(relativeTime(NOW + 120, NOW)).toBe("now");
});

it("formatAbsoluteTime は端末のタイムゾーンで yyyy/MM/dd HH:mm（0 埋め）", () => {
  for (const t of [NOW, 1_704_070_805, 1_000_000_000]) {
    const d = new Date(t * 1000);
    const pad = (n: number) => String(n).padStart(2, "0");
    const expected = `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
    expect(formatAbsoluteTime(t)).toBe(expected);
    expect(formatAbsoluteTime(t)).toMatch(/^\d{4}\/\d{2}\/\d{2} \d{2}:\d{2}$/);
  }
});

it("formatAbsoluteTime は無効な時刻なら空文字", () => {
  expect(formatAbsoluteTime(Number.NaN)).toBe("");
});

it("formatAbsoluteTime は withSeconds で秒まで（yyyy/MM/dd HH:mm:ss）", () => {
  const t = local(2024, 12, 31, 23, 59, 7);
  expect(formatAbsoluteTime(t, true)).toBe("2024/12/31 23:59:07");
  expect(formatAbsoluteTime(t)).toBe("2024/12/31 23:59");
});

/** 端末のタイムゾーンでの年月日・時刻 → UNIX 秒（テストを実行環境のタイムゾーンに依らせない） */
function local(y: number, mo: number, d: number, h: number, mi: number, s = 0): number {
  return new Date(y, mo - 1, d, h, mi, s).getTime() / 1000;
}

// [#940] 時刻の表示（ネイティブ TimeLabelsTest と同じ内容。ネイティブは UTC の書式化を渡すが、Web は端末のタイムゾーン）
describe("timeLabel", () => {
  const now = local(2025, 10, 25, 13, 46, 40);
  const label = (t: number, mode: TimeDisplay) => timeLabel(t, now, mode);

  it("経過時間は従来どおり", () => {
    expect(label(now - 5, "relative")).toBe("now");
    expect(label(now - 30, "relative")).toBe("30s");
    expect(label(now - 300, "relative")).toBe("5m");
    expect(label(now - 3 * 3600, "relative")).toBe("3h");
    expect(label(now - 2 * 86400, "relative")).toBe("2d");
    expect(label(now - 8 * 86400, "relative")).toBe("1w");
    expect(label(now + 60, "relative")).toBe("now"); // 未来の時刻
  });

  it("日時: 今日なら時刻だけ", () => {
    expect(label(now - 300, "absolute")).toBe("13:41");
    expect(label(now - 300, "absolute_seconds")).toBe("13:41:40");
  });

  it("日時: 今年なら月日（ゼロ埋めなし）と時刻", () => {
    expect(label(local(2025, 10, 23, 13, 46, 40), "absolute")).toBe("10/23 13:46");
    expect(label(local(2025, 1, 5, 9, 0, 7), "absolute_seconds")).toBe("1/5 09:00:07");
  });

  it("日時: それより前は年月日と時刻", () => {
    expect(label(local(2024, 12, 31, 23, 59), "absolute")).toBe("2024/12/31 23:59");
  });

  it("日付の境目・年またぎ", () => {
    const midnight = local(2025, 10, 25, 0, 0);
    // 0 時を越えたら前日の投稿は月日つき
    expect(timeLabel(midnight - 60, midnight + 30, "absolute")).toBe("10/24 23:59");
    expect(timeLabel(midnight, midnight + 30, "absolute")).toBe("00:00");
    // 年またぎ: 去年の投稿は年から
    const newYear = local(2026, 1, 1, 0, 0);
    expect(timeLabel(newYear - 600, newYear + 600, "absolute")).toBe("2025/12/31 23:50");
  });

  it("未来でも同じ日なら時刻だけ。それより前は秒まで", () => {
    expect(label(now + 3600, "absolute")).toBe("14:46");
    expect(label(local(2024, 12, 31, 23, 59), "absolute_seconds")).toBe("2024/12/31 23:59:00");
  });

  it("無効な時刻は空文字", () => {
    expect(label(Number.NaN, "absolute")).toBe("");
  });
});

describe("時刻の表示の設定", () => {
  afterEach(() => {
    localStorage.clear();
    reloadTimeDisplayForTest();
  });

  it("既定は経過時間。不正な保存値も経過時間", () => {
    reloadTimeDisplayForTest();
    expect(renderHook(() => useTimeDisplay()).result.current).toBe("relative");
    localStorage.setItem(TIME_DISPLAY_KEY, "unknown");
    reloadTimeDisplayForTest();
    expect(renderHook(() => useTimeDisplay()).result.current).toBe("relative");
  });

  it("選んだ値は保存され、読み直しても残る", () => {
    const { result } = renderHook(() => useTimeDisplay());
    for (const mode of ["absolute", "absolute_seconds", "relative"] as const) {
      act(() => setTimeDisplay(mode));
      expect(result.current).toBe(mode);
      expect(localStorage.getItem(TIME_DISPLAY_KEY)).toBe(mode);
      act(() => reloadTimeDisplayForTest());
      expect(result.current).toBe(mode);
    }
  });
});
