import type { EventPointer } from "applesauce-core/helpers/pointers";
import type { NostrEvent } from "nostr-tools/pure";
import { useMemo, useState } from "react";
import { useT } from "../i18n";
import { useEventByPointer } from "../nostr/loaders";
import styles from "./EventJsonDialog.module.css";
import { InfoDialog } from "./InfoDialog";
import { showToast } from "./toast";

const HEX64 = /^[0-9a-f]{64}$/i;

/** NIP-01 のイベントの形（id / pubkey / created_at / kind / tags / content / sig）で整形した JSON */
export function prettyEventJson(e: NostrEvent): string {
  return JSON.stringify(
    {
      id: e.id,
      pubkey: e.pubkey,
      created_at: e.created_at,
      kind: e.kind,
      tags: e.tags,
      content: e.content,
      sig: e.sig,
    },
    null,
    2,
  );
}

/** 参照先イベント。label は e / q（e のマーカー付きは e:reply など） */
export type EventRef = { label: string; pointer: EventPointer };

/** e / q タグが指すイベント ID（出現順・重複を除く。q のアドレス指定は除く） */
export function referencedEvents(e: NostrEvent): EventRef[] {
  const seen = new Set<string>();
  const refs: EventRef[] = [];
  for (const t of e.tags) {
    if ((t[0] !== "e" && t[0] !== "q") || typeof t[1] !== "string" || !HEX64.test(t[1])) continue;
    const id = t[1].toLowerCase();
    if (seen.has(id)) continue;
    seen.add(id);
    const marker = t[0] === "e" && t[3]?.trim() ? t[3].trim() : null;
    const relay = typeof t[2] === "string" && t[2].startsWith("wss://") ? [t[2]] : [];
    refs.push({ label: marker ? `${t[0]}:${marker}` : t[0], pointer: { id, relays: relay } });
  }
  return refs;
}

/**
 * イベントの生 JSON（ネイティブ EventJsonDialog。開発者モードで投稿の ⋯ から開く）。表示は手元（EventStore）の版。
 * e / q タグの参照先を並べ、押すとその JSON へ潜る（「←」で戻る。手元に無ければ eventLoader が取りに行き、
 * 届くまでは「イベントを取得中…」）。「テキストをコピー」で表示中の JSON をクリップボードへ。
 */
export function EventJsonDialog({ event, onDismiss }: { event: NostrEvent; onDismiss(): void }) {
  const t = useT();
  // 潜った参照先（末尾が表示中）。空なら event そのもの
  const [stack, setStack] = useState<EventRef[]>([]);
  const top = stack.at(-1) ?? null;
  const referenced = useEventByPointer(top?.pointer ?? null);
  const current = top ? referenced : event;
  const json = useMemo(() => (current ? prettyEventJson(current) : null), [current]);
  const refs = useMemo(() => (current ? referencedEvents(current) : []), [current]);

  async function copy() {
    if (json === null) return;
    try {
      await navigator.clipboard.writeText(json);
      showToast(t("json_copied_toast"));
    } catch {
      showToast(t("web_copy_failed"));
    }
  }

  return (
    <InfoDialog
      title={t("json_dialog_title")}
      subtitle={current ? `kind:${current.kind}` : undefined}
      onBack={stack.length > 0 ? () => setStack((s) => s.slice(0, -1)) : undefined}
      action={{ label: t("note_copy_text"), onClick: () => void copy(), disabled: json === null }}
      onDismiss={onDismiss}
    >
      <div className={styles.json}>
        {json !== null ? (
          <pre className={styles.pre}>{json}</pre>
        ) : (
          <p className={styles.loading}>{t("json_loading")}</p>
        )}
      </div>
      {refs.length > 0 && (
        <>
          <h3 className={styles.caption}>{t("json_refs")}</h3>
          <ul className={styles.refs}>
            {refs.map((r) => (
              <li key={r.pointer.id}>
                <button type="button" className={styles.ref} onClick={() => setStack((s) => [...s, r])}>
                  {`${r.label} ${r.pointer.id.slice(0, 16)}…`}
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
    </InfoDialog>
  );
}
