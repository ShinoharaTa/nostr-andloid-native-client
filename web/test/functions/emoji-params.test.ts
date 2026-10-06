import { describe, expect, it } from "vitest";
import { canonicalQuery, normalizeColor, normalizeText, parseEmojiParams } from "../../server/emoji/params";

const parse = (query: string) => parseEmojiParams(new URLSearchParams(query));

describe("normalizeText（§4.3）", () => {
  it("\\n（2 文字）・¥n・実改行・CRLF・CR を改行にする", () => {
    expect(normalizeText("a\\nb")).toEqual({ ok: true, lines: ["a", "b"] });
    expect(normalizeText("a¥nb")).toEqual({ ok: true, lines: ["a", "b"] });
    expect(normalizeText("a\nb")).toEqual({ ok: true, lines: ["a", "b"] });
    expect(normalizeText("a\r\nb\rc")).toEqual({ ok: true, lines: ["a", "b", "c"] });
  });

  it("行末の空白（半角・全角・タブ）を落とし、行頭は残す。タブは半角空白にする", () => {
    expect(normalizeText(" a \u3000\t\\n\tb")).toEqual({ ok: true, lines: [" a", " b"] });
  });

  it("先頭・末尾の空行を落とし、途中の空行は残す", () => {
    expect(normalizeText("\n \na\n\nb\n\u3000\n")).toEqual({ ok: true, lines: ["a", "", "b"] });
  });

  it("NFC に正規化する", () => {
    // が = か + 濁点（U+3099）
    expect(normalizeText("\u304b\u3099")).toEqual({ ok: true, lines: ["\u304c"] });
  });

  it("異体字セレクタ（U+FE0E / U+FE0F）を数えずに落とす", () => {
    expect(normalizeText("\u2764\uFE0F\u2764\uFE0E")).toEqual({ ok: true, lines: ["\u2764\u2764"] });
    expect(normalizeText("0123456789\uFE0F")).toEqual({ ok: true, lines: ["0123456789"] });
  });

  it("行数・1 行の文字数はコードポイントで数える", () => {
    expect(normalizeText("1\\n2\\n3\\n4")).toEqual({ ok: true, lines: ["1", "2", "3", "4"] });
    expect(normalizeText("1\\n2\\n3\\n4\\n5")).toEqual({ ok: false, error: "too_many_lines" });
    expect(normalizeText("𠮷".repeat(10))).toEqual({ ok: true, lines: ["𠮷".repeat(10)] });
    expect(normalizeText("あ".repeat(11))).toEqual({ ok: false, error: "line_too_long" });
  });

  it("空・制御文字", () => {
    expect(normalizeText("")).toEqual({ ok: false, error: "empty_text" });
    expect(normalizeText(" \\n\u3000")).toEqual({ ok: false, error: "empty_text" });
    expect(normalizeText("a\u0001")).toEqual({ ok: false, error: "invalid_text" });
    expect(normalizeText("a\u0085")).toEqual({ ok: false, error: "invalid_text" });
  });
});

describe("normalizeColor（§4.2）", () => {
  it("3/4/6/8 桁を小文字に展開し、アルファ ff は落とす", () => {
    expect(normalizeColor("#F0A")).toBe("ff00aa");
    expect(normalizeColor("f0a")).toBe("ff00aa");
    expect(normalizeColor("f0a8")).toBe("ff00aa88");
    expect(normalizeColor("f0af")).toBe("ff00aa");
    expect(normalizeColor("FF00AA")).toBe("ff00aa");
    expect(normalizeColor("ff00aaff")).toBe("ff00aa");
    expect(normalizeColor("ff00aa80")).toBe("ff00aa80");
  });

  it("hex でなければ null", () => {
    for (const value of ["", "#", "ff", "fffff", "gggggg", "##fff", "red", " fff"]) {
      expect(normalizeColor(value)).toBeNull();
    }
  });
});

describe("parseEmojiParams と canonicalQuery（§4.2・§4.4）", () => {
  it("既定値（color=000000・font=notosans・縁取り無し）", () => {
    expect(parse("text=a")).toEqual({
      ok: true,
      params: { lines: ["a"], color: "000000", stroke: null, font: "notosans" },
    });
  });

  it("正規 URL は text・color・stroke・font の順で、既定値を省く。未知のパラメータは無視する", () => {
    const result = parse("font=DelaGothic&z=1&stroke=%23FFF&color=0F0&text=%E3%81%9D%E3%82%8C%5Cn%E3%81%AA");
    if (!result.ok) throw new Error(result.error);
    expect(canonicalQuery(result.params)).toBe(
      "text=%E3%81%9D%E3%82%8C%0A%E3%81%AA&color=00ff00&stroke=ffffff&font=delagothic",
    );
    const defaults = parse("text=a+b&color=000&font=notosans&stroke=");
    if (!defaults.ok) throw new Error(defaults.error);
    expect(canonicalQuery(defaults.params)).toBe("text=a+b");
  });

  it("各 400 code", () => {
    expect(parse("color=fff")).toEqual({ ok: false, error: "missing_text" });
    expect(parse(`text=${"a".repeat(201)}`)).toEqual({ ok: false, error: "text_too_long" });
    expect(parse(`text=${"\\n".repeat(100)}`)).toEqual({ ok: false, error: "empty_text" });
    expect(parse("text=")).toEqual({ ok: false, error: "empty_text" });
    expect(parse("text=a&color=red")).toEqual({ ok: false, error: "invalid_color" });
    expect(parse("text=a&color=")).toEqual({ ok: false, error: "invalid_color" });
    expect(parse("text=a&stroke=12")).toEqual({ ok: false, error: "invalid_stroke" });
    expect(parse("text=a&font=comic")).toEqual({ ok: false, error: "invalid_font" });
  });
});
