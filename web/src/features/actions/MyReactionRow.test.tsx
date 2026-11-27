import { screen } from "@testing-library/react";
import { finalizeEvent, generateSecretKey, type NostrEvent } from "nostr-tools/pure";
import { expect, it } from "vitest";
import { proxiedEmoji } from "../../lib/imageProxy";
import { unixNow } from "../../lib/time";
import { eventStore } from "../../nostr/store";
import { renderWithRouter } from "../../test/renderWithRouter";
import { yoDraft } from "../yo/yoModel";
import { MyReactionRow } from "./MyReactionRow";

/** 名前付きの作者の投稿をストアに入れる */
function storedTarget(name: string, content: string): NostrEvent {
  const key = generateSecretKey();
  eventStore.add(
    finalizeEvent({ kind: 0, created_at: unixNow(), tags: [], content: JSON.stringify({ name }) }, key),
  );
  const target = finalizeEvent({ kind: 1, created_at: unixNow(), tags: [], content }, key);
  eventStore.add(target);
  return target;
}

function reactionTo(
  target: NostrEvent | { id: string; pubkey: string },
  content: string,
  tags: string[][] = [],
) {
  return finalizeEvent(
    {
      kind: 7,
      created_at: unixNow(),
      tags: [["e", target.id], ["p", target.pubkey], ...tags],
      content,
    },
    generateSecretKey(),
  );
}

it("[#878] + のリアクションは ♡ のマーク・「あなたがリアクション」→ ❤️ →「名前: 本文」の縦並びでスレッドへのリンク。操作ボタンは無い", async () => {
  const target = storedTarget("alice", "こんにちは\n世界");
  renderWithRouter(<MyReactionRow reaction={reactionTo(target, "+")} />);

  const link = await screen.findByRole("link");
  expect(link.querySelector("svg")).not.toBeNull();
  expect(link.getAttribute("href")).toMatch(/\/e\/nevent1/);
  const order = [
    screen.getByText("あなたがリアクション"),
    screen.getByText("❤️"),
    await screen.findByText("alice: こんにちは 世界"),
  ];
  expect(order[0].compareDocumentPosition(order[1])).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  expect(order[1].compareDocumentPosition(order[2])).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  expect(screen.queryByRole("button")).toBeNull();
  expect(screen.queryByRole("group")).toBeNull();
});

it("カスタム絵文字は画像（alt は :code:、src は proxiedEmoji）", async () => {
  const target = storedTarget("bob", "本文");
  renderWithRouter(
    <MyReactionRow
      reaction={reactionTo(target, ":cat:", [["emoji", "cat", "https://emoji.test/cat.png"]])}
    />,
  );
  const img = await screen.findByRole("img", { name: ":cat:" });
  expect(img.getAttribute("src")).toBe(proxiedEmoji("https://emoji.test/cat.png", 80));
});

it("対象がストアに無ければ何も書かない", () => {
  const missing = { id: "f".repeat(64), pubkey: "e".repeat(64) };
  const { container } = renderWithRouter(<MyReactionRow reaction={reactionTo(missing, "+")} />);
  expect(container.textContent).toBe("");
  expect(screen.queryByRole("link")).toBeNull();
});

it("[#1025] 自分が送った Yo は「〇〇 さんに Yo」の行。相手のプロフィールへのリンクで、プロフィールの JSON は出さない", async () => {
  const key = generateSecretKey();
  const profile = finalizeEvent(
    {
      kind: 0,
      created_at: unixNow(),
      tags: [],
      content: JSON.stringify({ name: "carol", about: "json-ish" }),
    },
    key,
  );
  eventStore.add(profile);
  const reaction = finalizeEvent(
    { kind: 7, created_at: unixNow(), content: ":yo:", tags: yoDraft(profile.pubkey, profile.id).tags },
    generateSecretKey(),
  );
  renderWithRouter(<MyReactionRow reaction={reaction} />);

  const link = await screen.findByRole("link");
  expect(link.getAttribute("href")).not.toMatch(/\/e\//);
  expect(await screen.findByText("carol さんに Yo")).toBeInTheDocument();
  expect(screen.queryByText(/json-ish/)).toBeNull();
  expect(screen.queryByText("あなたがリアクション")).toBeNull();
  expect(link.querySelector("img[alt=':yo:']")).toBeNull();
});

it("[#1025] Yo でないプロフィールへのリアクションは、行にしない（対象を投稿として出せない）", () => {
  const key = generateSecretKey();
  const profile = finalizeEvent({ kind: 0, created_at: unixNow(), tags: [], content: "{}" }, key);
  eventStore.add(profile);
  renderWithRouter(<MyReactionRow reaction={reactionTo(profile, "🎉", [["k", "0"]])} />);
  expect(screen.queryByRole("link")).toBeNull();
});
