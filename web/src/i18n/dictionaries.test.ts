import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import jaKansai from "./ja-kansai.json";
import nativeEn from "./native.en.json";
import nativeJa from "./native.ja.json";
import webEn from "./web.en.json";
import webJa from "./web.ja.json";

type Dict = Readonly<Record<string, string>>;

const JA: Dict = { ...nativeJa, ...webJa };
const EN: Dict = { ...nativeEn, ...webEn };

/** ネイティブ（Compose）の日本語リソース。ja-kansai はネイティブにしか無いキーも持つ（#727）ので、ここも基準にする */
const NATIVE_XML = readFileSync(
  resolve(process.cwd(), "../composeApp/src/commonMain/composeResources/values-ja/strings.xml"),
  "utf8",
);
const XML_JA: Dict = Object.fromEntries(
  [...NATIVE_XML.matchAll(/<string name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)].map((m) => [
    m[1] ?? "",
    m[2] ?? "",
  ]),
);
/** ja-kansai の基準: Web の ja 辞書 ∪ strings.xml（同じキーは Web の値を優先） */
const BASE: Dict = { ...XML_JA, ...JA };

/** `%1$s` / `%2$d` / `%s` / `%%` の並び（順序は言語で変わるので並べ替えて比べる） */
function placeholders(value: string): string {
  return (value.match(/%(?:\d+\$)?[sd%]/g) ?? []).sort().join(" ");
}

describe("辞書の整合（ja / en / ja-kansai）", () => {
  it("en（native + web）は ja と同じキー集合", () => {
    expect(Object.keys(EN).sort()).toEqual(Object.keys(JA).sort());
    expect(Object.keys(nativeEn).sort()).toEqual(Object.keys(nativeJa).sort());
    expect(Object.keys(webEn).sort()).toEqual(Object.keys(webJa).sort());
  });

  it("en のプレースホルダは ja と個数・種類が同じ", () => {
    const mismatched = Object.keys(JA).filter((k) => placeholders(EN[k] ?? "") !== placeholders(JA[k] ?? ""));
    expect(mismatched).toEqual([]);
  });

  it("ja-kansai に、ja（Web）にも strings.xml（ネイティブ）にも無いキーは無い（孤児なし）", () => {
    expect(Object.keys(XML_JA).length).toBeGreaterThan(500);
    const orphans = Object.keys(jaKansai).filter((k) => !(k in BASE));
    expect(orphans).toEqual([]);
  });

  it("ja-kansai のプレースホルダは ja（ネイティブ由来は strings.xml）と個数・種類が同じ", () => {
    const kansai: Dict = jaKansai;
    const mismatched = Object.keys(kansai).filter(
      (k) => placeholders(kansai[k] ?? "") !== placeholders(BASE[k] ?? ""),
    );
    expect(mismatched).toEqual([]);
  });

  it("どの辞書にも空の値は無い（ネイティブで言語の語順のために空にしているキーを除く）", () => {
    // [#904] thread_reaction_to_suffix は ja「へ」/ en「」（「on 〇〇’s 👍」で終わる）。描く側は空なら出さない
    const intentionallyEmpty = new Set(["thread_reaction_to_suffix"]);
    for (const dict of [nativeJa, nativeEn, webJa, webEn, jaKansai] as Dict[]) {
      const empty = Object.keys(dict).filter(
        (k) => !intentionallyEmpty.has(k) && (dict[k] ?? "").trim() === "",
      );
      expect(empty).toEqual([]);
    }
  });

  it("web 辞書のキーは web_ 始まり", () => {
    expect(Object.keys(webEn).filter((k) => !k.startsWith("web_"))).toEqual([]);
  });

  it("見出し 3 つ（にゃにゃにゃウイルス / 廃人モード / うにゅうと握手）とそのトグルは ja と同一（戻せなくならないように）", () => {
    const kansai: Dict = jaKansai;
    for (const k of ["nyan_mode_title", "dense_mode_title", "web_kansai_title", "web_kansai_toggle"]) {
      expect(JA[k]).toBeTruthy();
      expect(kansai[k] ?? JA[k]).toBe(JA[k]);
    }
  });
});
