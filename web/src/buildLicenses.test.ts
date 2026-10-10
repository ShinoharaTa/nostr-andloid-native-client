import { describe, expect, it } from "vitest";
import {
  authorName,
  BUNDLED_DEV_ROOTS,
  extractCopyright,
  normalizeLicense,
  productionKeys,
  repositoryUrl,
} from "../scripts/build-licenses.mjs";

/** scripts/build-licenses.mjs（/licenses の生成。#688）の純関数 */
describe("normalizeLicense", () => {
  it("SPDX の ID はそのまま、よくある別表記は SPDX にそろえる", () => {
    expect(normalizeLicense("MIT")).toBe("MIT");
    expect(normalizeLicense("Apache-2.0")).toBe("Apache-2.0");
    expect(normalizeLicense("mit")).toBe("MIT");
    expect(normalizeLicense("MIT License")).toBe("MIT");
    expect(normalizeLicense("Apache 2.0")).toBe("Apache-2.0");
    expect(normalizeLicense("Apache License, Version 2.0")).toBe("Apache-2.0");
    expect(normalizeLicense("unlicense")).toBe("Unlicense");
    expect(normalizeLicense(" 0bsd ")).toBe("0BSD");
  });

  it("外側の括弧を外し、OR / AND の各項を正規化する", () => {
    expect(normalizeLicense("(MIT OR Apache-2.0)")).toBe("MIT OR Apache-2.0");
    expect(normalizeLicense("(mit or apache 2.0)")).toBe("MIT OR Apache-2.0");
    expect(normalizeLicense("(MIT OR Apache-2.0) AND (ISC)")).toBe("(MIT OR Apache-2.0) AND (ISC)");
  });

  it("{ type } と旧形式の licenses 配列。無いものは UNKNOWN", () => {
    expect(normalizeLicense({ type: "MIT", url: "https://example.com" })).toBe("MIT");
    expect(normalizeLicense([{ type: "MIT" }, { type: "Apache 2.0" }])).toBe("MIT OR Apache-2.0");
    expect(normalizeLicense(undefined)).toBe("UNKNOWN");
    expect(normalizeLicense("")).toBe("UNKNOWN");
  });
});

describe("extractCopyright", () => {
  it("MIT の著作権表示の行を返す（空白は詰める）", () => {
    const text = "MIT License\n\n  Copyright (c) 2024   hzrd149\n\nPermission is hereby granted...\n";
    expect(extractCopyright(text)).toBe("Copyright (c) 2024 hzrd149");
  });

  it("© や (c) で始まる行は年が続くときだけ拾う", () => {
    expect(extractCopyright("The MIT License\n\n© 2014 Nicolas Bevacqua\n")).toBe("© 2014 Nicolas Bevacqua");
    expect(extractCopyright("(c) 2019 Someone\n")).toBe("(c) 2019 Someone");
  });

  it("本文中の copyright と Apache-2.0 の 4.(c)・付録の雛形は拾わない", () => {
    const apache = [
      "Apache License",
      "   2. Grant of Copyright License. Subject to the terms",
      "      (c) You must retain, in the Source form of any Derivative Works",
      "   The above copyright notice and this permission notice shall be included",
      "   Copyright [yyyy] [name of copyright owner]",
      "   Copyright (c) 2015-2018 Google, Inc., Netflix, Inc., Microsoft Corp. and contributors",
    ].join("\n");
    expect(extractCopyright(apache)).toBe(
      "Copyright (c) 2015-2018 Google, Inc., Netflix, Inc., Microsoft Corp. and contributors",
    );
  });

  it("著作権表示が無ければ null", () => {
    expect(extractCopyright("This is free and unencumbered software released into the public domain.")).toBe(
      null,
    );
    expect(extractCopyright(undefined)).toBe(null);
  });
});

describe("authorName / repositoryUrl", () => {
  it("author からメールと URL を外した名前", () => {
    expect(authorName("Titus Wormer <tituswormer@gmail.com> (https://wooorm.com)")).toBe("Titus Wormer");
    expect(authorName({ name: "Miles Johnson", email: "a@example.com" })).toBe("Miles Johnson");
    expect(authorName(undefined)).toBe(null);
  });

  it("repository を https の URL にする", () => {
    expect(repositoryUrl({ type: "git", url: "git+https://github.com/unjs/uqr.git" })).toBe(
      "https://github.com/unjs/uqr",
    );
    expect(repositoryUrl("git@github.com:milesj/emojibase.git")).toBe("https://github.com/milesj/emojibase");
    expect(repositoryUrl("pmndrs/zustand")).toBe("https://github.com/pmndrs/zustand");
    expect(repositoryUrl("gitlab:a/b")).toBe("https://gitlab.com/a/b");
    expect(repositoryUrl(undefined)).toBe(null);
  });
});

describe("productionKeys", () => {
  it("dependencies と optional でない peer を推移的にたどる。devDependencies・optional・@types はたどらない", () => {
    const keys = productionKeys({
      packages: {
        "": { dependencies: { a: "1", "@types/x": "1" }, devDependencies: { dev: "1", react: "1" } },
        "node_modules/a": {
          dependencies: { b: "1" },
          peerDependencies: { react: "*", maybe: "*" },
          peerDependenciesMeta: { maybe: { optional: true } },
          optionalDependencies: { opt: "1" },
        },
        // a の下の b を先に解決し、b の依存 c はルートの node_modules まで上がって解決する
        "node_modules/a/node_modules/b": { dependencies: { c: "1" } },
        "node_modules/b": {},
        "node_modules/c": {},
        "node_modules/react": {},
        "node_modules/maybe": {},
        "node_modules/opt": {},
        "node_modules/dev": {},
        "node_modules/@types/x": {},
      },
    });
    expect(keys.sort()).toEqual([
      "node_modules/a",
      "node_modules/a/node_modules/b",
      "node_modules/c",
      "node_modules/react",
    ]);
  });

  it("extraRoots（本番に出る devDependencies）も起点にしてたどる。lockfile に無いものは飛ばす", () => {
    const lock = {
      packages: {
        "": { dependencies: {}, devDependencies: { "workbox-precaching": "1" } },
        "node_modules/workbox-precaching": { dependencies: { "workbox-core": "1" } },
        "node_modules/workbox-core": {},
      },
    };
    expect(productionKeys(lock, ["workbox-precaching", "missing"]).sort()).toEqual([
      "node_modules/workbox-core",
      "node_modules/workbox-precaching",
    ]);
    expect(BUNDLED_DEV_ROOTS).toContain("workbox-core");
  });
});
