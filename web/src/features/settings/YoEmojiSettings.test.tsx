import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { finalizeEvent, generateSecretKey, getPublicKey } from "nostr-tools/pure";
import { afterEach, beforeAll, beforeEach, expect, it } from "vitest";
import { originOf } from "../../lib/imageProxy";
import { unixNow } from "../../lib/time";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { installDialogPolyfill } from "../../test/dialog";
import { reloadYoEmojiForTest, YO_EMOJI_KEY, yoEmojiUrl } from "../yo/yoEmoji";
import { YO_IMAGE_URL } from "../yo/yoModel";
import { YoEmojiSettings } from "./YoEmojiSettings";

const WAVE = "https://emoji.example/wave.png";

beforeAll(() => installDialogPolyfill());

beforeEach(() => {
  // 自分のカスタム絵文字（kind:10030。ストアは署名を確かめるので、署名した形で入れる）
  const key = generateSecretKey();
  useSession.setState({ status: "in", method: "nip07", pubkey: getPublicKey(key) });
  eventStore.add(
    finalizeEvent(
      {
        kind: 10030,
        created_at: unixNow() - 100,
        tags: [
          ["emoji", "wave", WAVE],
          // https でないものは Yo に使えない
          ["emoji", "plain", "http://emoji.example/not-https.png"],
        ],
        content: "",
      },
      key,
    ),
  );
});

afterEach(() => {
  localStorage.removeItem(YO_EMOJI_KEY);
  reloadYoEmojiForTest();
  useSession.setState({ status: "loading", method: null, pubkey: null });
});

it("既定は標準（nostr-yo の絵）", () => {
  render(<YoEmojiSettings />);
  expect(screen.getByRole("heading", { name: "Yo の絵文字" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "標準" })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("button", { name: "カスタム絵文字" })).toHaveAttribute("aria-pressed", "false");
  expect(yoEmojiUrl()).toBe(YO_IMAGE_URL);
});

it("「カスタム絵文字」で開く画面には、標準の絵が先頭に必ず並び、自分のカスタム絵文字から選べる。選ぶと保存される", async () => {
  const user = userEvent.setup();
  const { unmount } = render(<YoEmojiSettings />);
  await user.click(screen.getByRole("button", { name: "カスタム絵文字" }));

  const dialog = screen.getByRole("dialog", { name: "Yo の絵文字を選ぶ" });
  const cells = within(dialog).getAllByRole("button", { name: /^:.+:$/ });
  // 標準（:yo:）が先頭で、いま選んでいる。https でない絵文字は並べない
  expect(cells.map((c) => c.getAttribute("aria-label"))).toEqual([":yo:", ":wave:"]);
  expect(cells[0]).toHaveAttribute("aria-pressed", "true");
  expect(cells[1]).toHaveAttribute("aria-pressed", "false");

  await user.click(cells[1]);
  expect(screen.queryByRole("dialog", { name: "Yo の絵文字を選ぶ" })).toBeNull();
  expect(localStorage.getItem(YO_EMOJI_KEY)).toBe(WAVE);
  expect(yoEmojiUrl()).toBe(WAVE);
  // 選んだ絵が「カスタム絵文字」のタイルに出る
  const custom = screen.getByRole("button", { name: "カスタム絵文字" });
  expect(custom).toHaveAttribute("aria-pressed", "true");
  expect(originOf(custom.querySelector("img")?.getAttribute("src"))).toBe(WAVE);
  unmount();

  // 次に開いても残る。「標準」を押すと戻る
  reloadYoEmojiForTest();
  render(<YoEmojiSettings />);
  expect(screen.getByRole("button", { name: "カスタム絵文字" })).toHaveAttribute("aria-pressed", "true");
  await user.click(screen.getByRole("button", { name: "標準" }));
  expect(localStorage.getItem(YO_EMOJI_KEY)).toBe(YO_IMAGE_URL);
  expect(yoEmojiUrl()).toBe(YO_IMAGE_URL);
});

it("保存してある URL が Yo に使えないもの（https でない等）なら、標準に戻す", () => {
  localStorage.setItem(YO_EMOJI_KEY, "http://emoji.example/a.png");
  reloadYoEmojiForTest();
  expect(yoEmojiUrl()).toBe(YO_IMAGE_URL);
});
