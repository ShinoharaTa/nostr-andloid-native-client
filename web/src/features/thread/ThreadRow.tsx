import type { NostrEvent } from "nostr-tools/pure";
import type { CSSProperties, ReactNode } from "react";
import { clientNameOf } from "../../lib/content/tags";
import { formatAbsoluteTime } from "../../lib/time";
import { useTimeDisplay } from "../settings/timeDisplay";
import { NoteItem } from "../timeline/NoteItem";
import styles from "./ThreadRow.module.css";
import type { ThreadEntry } from "./threadTree";

/**
 * スレッドの 1 行（ネイティブの ThreadColumn.kt ThreadRow）。深さ × 16px 下げた NoteItem（押しても開かない）。
 * 背景は 起点 = AccentWeak / root = Accent の 5% / 他 = Surface。起点の下に日時と反応を出す。
 */
export function ThreadRow({ entry, stats }: { entry: ThreadEntry; stats?: ReactNode }) {
  return (
    <div
      className={styles.row}
      data-root={entry.isRoot}
      data-focused={entry.isFocused}
      style={{ "--depth": entry.depth } as CSSProperties}
    >
      <NoteItem event={entry.event} openable={false} />
      {entry.isFocused && (
        <>
          <FocusNoteMeta event={entry.event} />
          {stats}
        </>
      )}
    </div>
  );
}

/** 起点の日時（yyyy/MM/dd HH:mm。[#940] 時刻の表示が「日時（秒まで）」なら秒まで）+ client タグがあれば「 · via <client>」 */
function FocusNoteMeta({ event }: { event: NostrEvent }) {
  const client = clientNameOf(event);
  const timeDisplay = useTimeDisplay();
  return (
    <p className={styles.meta}>
      {formatAbsoluteTime(event.created_at, timeDisplay === "absolute_seconds")}
      {client ? ` · via ${client}` : ""}
    </p>
  );
}
