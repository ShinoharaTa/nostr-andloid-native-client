import { neventEncode, npubEncode } from "nostr-tools/nip19";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { describe, expect, it } from "vitest";
import { addVerified } from "../../nostr/store";
import {
  articleTitleOf,
  clientNameOf,
  commentRootLabelOf,
  contentWarningOf,
  quotePointerOf,
  replyParentPointerOf,
} from "./tags";

const ID_A = "a".repeat(64);
const ID_B = "b".repeat(64);
const ID_C = "c".repeat(64);
const AUTHOR = getPublicKey(generateSecretKey());

function event(kind: number, tags: string[][] = [], content = ""): NostrEvent {
  return finalizeEvent({ kind, created_at: 1_800_000_000, tags, content }, generateSecretKey());
}

describe("contentWarningOf", () => {
  it("タグ無しは null、理由なしは空文字、理由があればその文字列", () => {
    expect(contentWarningOf(event(1))).toBeNull();
    expect(contentWarningOf(event(1, [["content-warning"]]))).toBe("");
    expect(contentWarningOf(event(1, [["content-warning", "nsfw"]]))).toBe("nsfw");
  });
});

describe("clientNameOf", () => {
  it("client タグの名前（trim）。空なら null、25 文字以上は 24 文字 + …", () => {
    expect(clientNameOf(event(1, [["client", "Nostrism"]]))).toBe("Nostrism");
    expect(clientNameOf(event(1, [["client", "  "]]))).toBeNull();
    expect(clientNameOf(event(1))).toBeNull();
    expect(clientNameOf(event(1, [["client", "x".repeat(25)]]))).toBe(`${"x".repeat(24)}…`);
    expect(clientNameOf(event(1, [["client", "x".repeat(24)]]))).toBe("x".repeat(24));
  });
});

describe("quotePointerOf", () => {
  it("本文の nevent は本文の表記を encoded にし、pointer に relays を入れる", () => {
    const nevent = neventEncode({ id: ID_A, relays: ["wss://r.example"], author: AUTHOR });
    const quote = quotePointerOf(event(1, [], `見て nostr:${nevent}`));
    expect(quote?.encoded).toBe(nevent);
    expect(quote?.pointer).toMatchObject({ id: ID_A, relays: ["wss://r.example"], author: AUTHOR });
  });

  it("本文に参照が無ければ最初の q タグ（encoded は null）", () => {
    // getEventPointerFromQTag はリレーヒントを正規化する（末尾 /）。ドットの無いホストは捨てる
    const quote = quotePointerOf(
      event(1, [
        ["q", ID_B, "wss://relay.example.com", AUTHOR],
        ["q", ID_C],
      ]),
    );
    expect(quote).toEqual({
      pointer: { id: ID_B, relays: ["wss://relay.example.com/"], author: AUTHOR },
      encoded: null,
    });
  });

  it("npub しか無ければ null", () => {
    expect(quotePointerOf(event(1, [], `nostr:${npubEncode(AUTHOR)}`))).toBeNull();
  });
});

describe("replyParentPointerOf", () => {
  it("reply マーカーを優先する", () => {
    const e = event(1, [
      ["e", ID_A, "", "root"],
      ["e", ID_B, "", "reply"],
    ]);
    expect(replyParentPointerOf(e)?.id).toBe(ID_B);
  });

  it("root マーカーだけなら root", () => {
    expect(replyParentPointerOf(event(1, [["e", ID_A, "", "root"]]))?.id).toBe(ID_A);
  });

  it("[#796] kind:42 は reply マーカーの e だけ（root はチャンネルなので返信元にしない。ネイティブ Nip28.replyToOf）", () => {
    const reply = event(42, [
      ["e", ID_A, "", "root"],
      ["e", ID_B, "wss://chat.example", "reply"],
    ]);
    expect(replyParentPointerOf(reply)).toEqual({ id: ID_B, relays: ["wss://chat.example"] });
    expect(replyParentPointerOf(event(42, [["e", ID_A, "", "root"]]))).toBeNull();
    const toChannel = event(42, [
      ["e", ID_A, "", "root"],
      ["e", ID_A, "", "reply"],
    ]);
    expect(replyParentPointerOf(toChannel)).toBeNull();
  });

  it("マーカー無しが 2 本なら末尾が親", () => {
    expect(
      replyParentPointerOf(
        event(1, [
          ["e", ID_A],
          ["e", ID_B],
        ]),
      )?.id,
    ).toBe(ID_B);
  });

  it("mention マーカーだけなら null", () => {
    expect(replyParentPointerOf(event(1, [["e", ID_A, "", "mention"]]))).toBeNull();
  });

  it("kind:1111 は小文字 e、無ければ大文字 E", () => {
    const both = event(1111, [
      ["E", ID_A, "wss://root.example", AUTHOR],
      ["e", ID_B, "wss://parent.example", AUTHOR],
    ]);
    expect(replyParentPointerOf(both)).toEqual({
      id: ID_B,
      relays: ["wss://parent.example"],
      author: AUTHOR,
    });
    const rootOnly = event(1111, [["E", ID_A, "", "not-a-pubkey"]]);
    expect(replyParentPointerOf(rootOnly)).toEqual({ id: ID_A });
  });

  it("kind:6 は null", () => {
    expect(replyParentPointerOf(event(6, [["e", ID_A]]))).toBeNull();
  });

  it("kind:1111 は e が無ければ a（解決できたときだけ）、E が無ければ A（挙動2.1）", () => {
    const article = event(30023, [["d", "post"]], "本文");
    addVerified(article);
    const coord = `30023:${article.pubkey}:post`;

    // e/E が無く a だけ → 解決して親にする
    expect(replyParentPointerOf(event(1111, [["a", coord]]))).toEqual({
      id: article.id,
      author: article.pubkey,
    });

    // e が無く a と A の両方があれば a（親）を優先
    const rootCoord = `30023:${article.pubkey}:other`;
    expect(
      replyParentPointerOf(
        event(1111, [
          ["A", rootCoord],
          ["a", coord],
        ]),
      )?.id,
    ).toBe(article.id);

    // 手元に無いアドレスは解決できないので null
    expect(replyParentPointerOf(event(1111, [["a", `30023:${AUTHOR}:missing`]]))).toBeNull();
  });
});

describe("commentRootLabelOf", () => {
  it("I タグ（URL ならホスト名）→ K タグ → 取得中。kind:1 は null", () => {
    expect(commentRootLabelOf(event(1111, [["I", "https://example.com/x"]]))).toBe(
      "example.com へのコメント",
    );
    expect(commentRootLabelOf(event(1111, [["K", "30023"]]))).toBe("kind 30023 へのコメント");
    expect(commentRootLabelOf(event(1111))).toBe("コメント対象を取得中…");
    expect(commentRootLabelOf(event(1, [["K", "30023"]]))).toBeNull();
  });
});

describe("articleTitleOf", () => {
  it("title → summary → 本文の最初の非空行。kind:1 は null", () => {
    expect(
      articleTitleOf(
        event(
          30023,
          [
            ["title", "題"],
            ["summary", "要約"],
          ],
          "本文",
        ),
      ),
    ).toBe("題");
    expect(articleTitleOf(event(30023, [["summary", "要約"]], "本文"))).toBe("要約");
    expect(articleTitleOf(event(30023, [], "\n\n  最初の行  \n次の行"))).toBe("最初の行");
    expect(articleTitleOf(event(1, [["title", "題"]], "本文"))).toBeNull();
  });
});
