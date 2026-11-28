import { act, render, screen } from "@testing-library/react";
import type { NostrEvent } from "nostr-tools/pure";
import { afterEach, beforeEach, expect, it } from "vitest";
import { unixNow } from "../../lib/time";
import { useSession } from "../../signer/session";
import { useYoSending } from "./sendYo";
import { YoHeaderPill } from "./YoHeaderPill";
import { yoDraft } from "./yoModel";
import { reloadYoStyleForTest, setYoStyle, YO_STYLE_KEY } from "./yoStyle";

const ME = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d";
const ALICE = "a".repeat(64);
const BOB = "b".repeat(64);
const PROFILE = "d".repeat(64);

let seq = 0;
/** from から自分あての Yo（署名は見ないので空） */
function yo(from: string, secondsAgo: number): NostrEvent {
  seq++;
  return {
    id: seq.toString(16).padStart(64, "0"),
    pubkey: from,
    created_at: unixNow() - secondsAgo,
    kind: 7,
    tags: yoDraft(ME, PROFILE).tags,
    content: ":yo:",
    sig: "",
  };
}

beforeEach(() => {
  useSession.setState({ status: "in", method: "nip07", pubkey: ME });
  useYoSending.setState({ sending: new Set(), sentAt: new Map() });
});

afterEach(() => {
  localStorage.removeItem(YO_STYLE_KEY);
  reloadYoStyleForTest();
});

it("モノクロ（既定）では出さない", () => {
  const { container } = render(<YoHeaderPill events={[yo(ALICE, 30)]} />);
  expect(container).toBeEmptyDOMElement();
});

it("紫では、まだ返していない相手の数を「Yo! 2」と出す。返すと減り、0 になれば消える", () => {
  setYoStyle("color");
  const alice = yo(ALICE, 30);
  const bob = yo(BOB, 60);
  // 同じ人の Yo は 1 人と数える
  const { container } = render(<YoHeaderPill events={[alice, yo(ALICE, 90), bob]} />);
  expect(screen.getByText("Yo! 2")).toBeInTheDocument();

  act(() => useYoSending.setState({ sentAt: new Map([[ALICE, alice.created_at]]) }));
  expect(screen.getByText("Yo! 1")).toBeInTheDocument();

  act(() =>
    useYoSending.setState({
      sentAt: new Map([
        [ALICE, alice.created_at],
        [BOB, bob.created_at],
      ]),
    }),
  );
  expect(container).toBeEmptyDOMElement();
});

it("紫でも、Yo が無ければ出さない", () => {
  setYoStyle("color");
  const { container } = render(<YoHeaderPill events={[]} />);
  expect(container).toBeEmptyDOMElement();
});
