import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { finalizeEvent, generateSecretKey, getPublicKey } from "nostr-tools/pure";
import { afterEach, beforeAll, expect, it } from "vitest";
import { buildColumn, DEFAULT_COLUMNS } from "../../lib/columns";
import { addVerified } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { useDeck } from "../../store/deck";
import { USED_HASHTAGS_KEY } from "../compose/storage";
import { EditColumnDialog } from "./EditColumnDialog";

// jsdom は <dialog> の showModal / close を持たないので、開閉と close イベントだけを足す
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    if (!this.open) return;
    this.open = false;
    this.dispatchEvent(new Event("close"));
  };
});

afterEach(() => {
  localStorage.clear();
  useSession.setState({ status: "loading", method: null, pubkey: null });
});

it("ハッシュタグのフィルター編集にも 2 段のチップを出し、押すと入力を置き換えて保存できる（Web 独自。#642）", async () => {
  const meKey = generateSecretKey();
  useSession.setState({ status: "in", method: "local", pubkey: getPublicKey(meKey) });
  addVerified(
    finalizeEvent(
      {
        kind: 30015,
        created_at: 1,
        tags: [
          ["d", "pinned"],
          ["t", "zap"],
        ],
        content: "",
      },
      meKey,
    ),
  );
  localStorage.setItem(USED_HASHTAGS_KEY, JSON.stringify([{ tag: "bitcoin", lastUsed: 1 }]));
  const column = buildColumn("HASHTAG", { text: "nostr" }, new Set(), 1);
  if (!column) throw new Error("column not built");
  useDeck.setState({ columns: [...DEFAULT_COLUMNS, column], editingColumnId: column.id });
  const user = userEvent.setup();
  render(<EditColumnDialog />);

  const input = screen.getByRole("textbox", { name: "ハッシュタグ" });
  expect(input).toHaveValue("nostr");
  const pinned = screen.getByText("📌 ピン留め").nextElementSibling as HTMLElement;
  expect(within(pinned).getByRole("button", { name: "#zap" })).toBeInTheDocument();
  const recent = screen.getByText("最近のタグ").nextElementSibling as HTMLElement;
  await user.click(within(recent).getByRole("button", { name: "#bitcoin" }));
  expect(input).toHaveValue("bitcoin");

  await user.click(screen.getByRole("button", { name: "保存" }));
  expect(useDeck.getState().columns.find((c) => c.id === column.id)?.filter.hashtags).toEqual(["bitcoin"]);
});
