import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { expect, it } from "vitest";

const script = (name: string) => join(process.cwd(), "scripts", name);
const run = (name: string, ...args: string[]) =>
  spawnSync(process.execPath, [script(name), ...args], { encoding: "utf8" });

it("native.*.json は strings.xml から生成した最新の内容と一致する", () => {
  const r = run("import-strings.mjs", "--check");
  expect(r.stderr).toBe("");
  expect(r.status).toBe(0);
});

it("native.ja.json は values-ja/strings.xml の値と同じ", () => {
  const xml = readFileSync(
    join(process.cwd(), "../composeApp/src/commonMain/composeResources/values-ja/strings.xml"),
    "utf8",
  );
  const dict = JSON.parse(readFileSync(join(process.cwd(), "src/i18n/native.ja.json"), "utf8"));
  expect(Object.keys(dict).length).toBeGreaterThan(0);
  for (const [key, value] of Object.entries(dict)) {
    expect(xml).toContain(`<string name="${key}">${value}</string>`);
  }
});

it("check-i18n は許可リストのファイルに日本語リテラルが無ければ 0", () => {
  expect(run("check-i18n.mjs").status).toBe(0);
});

it("check-i18n は日本語リテラルを検出して非 0 で終わり、コメントの日本語は無視する", () => {
  const dir = mkdtempSync(join(tmpdir(), "check-i18n-"));
  try {
    const clean = join(dir, "clean.tsx");
    writeFileSync(clean, '// 日本語のコメント\n/* これも */\nexport const a = t("key");\n');
    const str = join(dir, "str.tsx");
    writeFileSync(str, 'export const a = "保存";\n');
    const jsx = join(dir, "jsx.tsx");
    writeFileSync(jsx, "export const a = <p>キャンセル</p>;\n");
    expect(run("check-i18n.mjs", clean).status).toBe(0);
    const s = run("check-i18n.mjs", str);
    expect(s.status).toBe(1);
    expect(s.stderr).toContain("str.tsx:1");
    expect(run("check-i18n.mjs", jsx).status).toBe(1);
  } finally {
    rmSync(dir, { recursive: true });
  }
});

it("check-i18n は console.*( と new Error( の引数を見ない（括弧の外の日本語は検出する）", () => {
  const dir = mkdtempSync(join(tmpdir(), "check-i18n-"));
  try {
    const log = join(dir, "log.ts");
    writeFileSync(
      log,
      'console.warn("保存に失敗", f("(", x));\nconsole.error(\n  `[db] 開けない`,\n);\nthrow new Error("不正");\n',
    );
    expect(run("check-i18n.mjs", log).status).toBe(0);
    const after = join(dir, "after.ts");
    writeFileSync(
      after,
      'console.warn("失敗"); export const a = "保存";\nthrow new Error(`x`); const b = "取消";\n',
    );
    const r = run("check-i18n.mjs", after);
    expect(r.status).toBe(1);
    expect(r.stderr).toContain("after.ts:1");
    expect(r.stderr).toContain("after.ts:2");
  } finally {
    rmSync(dir, { recursive: true });
  }
});
