import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { proxiedEmoji } from "../../lib/imageProxy";
import { isWordyReaction, ReactionDisplay } from "./ReactionDisplay";
import styles from "./ReactionDisplay.module.css";

describe("isWordyReaction（ネイティブ ReactionDisplayTest と同じ例）", () => {
  it.each(["❤️", "👍🏽", "👨‍👩‍👧‍👦", "🇯🇵", "草", "+1", "ok"])("%s は大きい 1 行（偽）", (display) => {
    expect(isWordyReaction(display)).toBe(false);
  });

  it.each(["ありがとう", "nice!", "わかる🙏", "123"])("%s は文字数のあるリアクション（真）", (display) => {
    expect(isWordyReaction(display)).toBe(true);
  });
});

describe("ReactionDisplay", () => {
  it("絵文字・1〜2 文字は大きい 1 行、文字数のあるものは本文に近い大きさで 3 行まで", () => {
    const { rerender } = render(<ReactionDisplay display="草" imageUrl={null} />);
    expect(screen.getByText("草")).toHaveClass(styles.emojiText);
    rerender(<ReactionDisplay display="ありがとうございます" imageUrl={null} />);
    expect(screen.getByText("ありがとうございます")).toHaveClass(styles.wordy);
  });

  it("カスタム絵文字は proxiedEmoji の画像（alt は :code:）", () => {
    render(<ReactionDisplay display=":wide:" imageUrl="https://emoji.test/wide.png" />);
    const img = screen.getByRole("img", { name: ":wide:" });
    expect(img.getAttribute("src")).toBe(proxiedEmoji("https://emoji.test/wide.png", 80));
    expect(img).toHaveClass(styles.emojiImg);
  });
});
