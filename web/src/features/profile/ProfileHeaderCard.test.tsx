import { act, screen } from "@testing-library/react";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { afterEach, describe, expect, it, vi } from "vitest";
import { unixNow } from "../../lib/time";
import { eventStore } from "../../nostr/store";
import { renderWithRouter } from "../../test/renderWithRouter";
import { ProfileHeaderCard } from "./ProfileHeaderCard";
import styles from "./ProfileHeaderCard.module.css";

afterEach(() => {
  vi.useRealTimers();
});

function status(key: Uint8Array, d: string, content: string, tags: string[][] = []): NostrEvent {
  return finalizeEvent({ kind: 30315, created_at: unixNow() - 60, tags: [["d", d], ...tags], content }, key);
}

/** 自己紹介を持つ本人のヘッダを描く */
function renderHeader(key: Uint8Array) {
  const pubkey = getPublicKey(key);
  eventStore.add(
    finalizeEvent(
      {
        kind: 0,
        created_at: unixNow(),
        tags: [],
        content: JSON.stringify({ name: "alice", about: "自己紹介です" }),
      },
      key,
    ),
  );
  return renderWithRouter(
    <ProfileHeaderCard
      pubkey={pubkey}
      isMe
      me={pubkey}
      following={false}
      followsMe={false}
      followingCount={0}
      onShowFollowing={() => {}}
      onShowFollowers={() => {}}
    />,
  );
}

describe("ProfileHeaderCard のステータス（#821）", () => {
  it("自己紹介の下に本人のステータスを印・本文・リンク行で出す（カードの名前・アイコンは出さない）", () => {
    const key = generateSecretKey();
    act(() => {
      eventStore.add(status(key, "general", "作業中", [["r", "https://example.test/work"]]));
    });
    const { container } = renderHeader(key);

    const statuses = container.getElementsByClassName(styles.statuses);
    expect(statuses).toHaveLength(1);
    expect(screen.getByText("作業中")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "ステータス" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "example.test で開く" })).toHaveAttribute(
      "href",
      "https://example.test/work",
    );
    // 自己紹介の後ろに並ぶ
    const about = screen.getByText("自己紹介です");
    expect(about.compareDocumentPosition(statuses[0]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // 枠の名前（リンク）は出さない
    expect(screen.queryByRole("link", { name: "alice" })).toBeNull();
  });

  it("general と music の両方があれば general → music の順に 2 件", () => {
    const key = generateSecretKey();
    act(() => {
      eventStore.add(status(key, "music", "DEEP BREATH - ROLLY"));
      eventStore.add(status(key, "general", "ハイキング中"));
      // 定義外の d は出さない
      eventStore.add(status(key, "presence", "online"));
    });
    const { container } = renderHeader(key);

    const items = container.getElementsByClassName(styles.status);
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("ハイキング中");
    expect(items[1]).toHaveTextContent("DEEP BREATH - ROLLY");
    expect(screen.getByRole("img", { name: "Now Playing" })).toBeInTheDocument();
    expect(screen.queryByText("online")).toBeNull();
  });

  it("ステータスが無い・空（クリア）なら何も出さない", () => {
    const none = renderHeader(generateSecretKey());
    expect(none.container.getElementsByClassName(styles.statuses)).toHaveLength(0);
    none.unmount();

    const key = generateSecretKey();
    act(() => {
      eventStore.add(status(key, "general", "  "));
    });
    const cleared = renderHeader(key);
    expect(cleared.container.getElementsByClassName(styles.statuses)).toHaveLength(0);
  });

  it("期限が過ぎるとその場で消える", () => {
    vi.useFakeTimers();
    const key = generateSecretKey();
    act(() => {
      eventStore.add(status(key, "music", "もうすぐ終わる曲", [["expiration", String(unixNow() + 15)]]));
      eventStore.add(status(key, "general", "期限なし"));
    });
    const { container } = renderHeader(key);
    expect(screen.getByText("もうすぐ終わる曲")).toBeInTheDocument();
    expect(screen.getByText("まもなく終了")).toBeInTheDocument();

    act(() => vi.advanceTimersByTime(20_000));
    expect(screen.queryByText("もうすぐ終わる曲")).toBeNull();
    expect(container.getElementsByClassName(styles.status)).toHaveLength(1);
    expect(screen.getByText("期限なし")).toBeInTheDocument();
  });
});
