import { screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { renderWithRouter } from "../../test/renderWithRouter";
import { ReactorEmoji, ReactorRow } from "./ReactorRow";
import styles from "./ReactorRow.module.css";

/** [#904] 統計行の横幅（アバター列の幅を ResizeObserver で測り、reactorAvatarSlots で入る数と「+N」を決める） */

const people = Array.from({ length: 10 }, (_, i) => i.toString(16).padStart(64, "0"));
let width = 0;

beforeEach(() => {
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      disconnect() {}
    },
  );
  vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockImplementation(() => width);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it("測った幅に入る分だけアバターを出し、残りは +N（4 個 + +N = 128px）", () => {
  width = 128;
  renderWithRouter(<ReactorRow leading="👍" label="10" people={people} />);
  expect(screen.getAllByRole("link")).toHaveLength(4);
  expect(screen.getByText("+6")).toBeInTheDocument();
});

it("全員が入れば +N は出さない", () => {
  width = 10 * 20 + 9 * 4;
  renderWithRouter(<ReactorRow leading="👍" label="10" people={people} />);
  expect(screen.getAllByRole("link")).toHaveLength(10);
  expect(screen.queryByText(/^\+/)).toBeNull();
});

it("文字数のあるリアクションは 2 行で省略する枠、絵文字は 1 行の枠", () => {
  const { container } = renderWithRouter(
    <>
      <ReactorEmoji display="ありがとうございます" imageUrl={null} />
      <ReactorEmoji display="🎉" imageUrl={null} />
    </>,
  );
  expect(container.getElementsByClassName(styles.emojiWordy)).toHaveLength(1);
  expect(container.getElementsByClassName(styles.emojiText)).toHaveLength(1);
});
