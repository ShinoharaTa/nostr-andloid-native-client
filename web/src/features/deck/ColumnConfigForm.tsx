import { type FormEvent, useId, useState } from "react";
import { useT } from "../../i18n";
import {
  buildColumn,
  type ColumnSpec,
  type ColumnTemplate,
  NOTIF_KINDS,
  notifKindLabel,
  templateHint,
  templateLabel,
} from "../../lib/columns";
import { unixNow } from "../../lib/time";
import { useReadRelays } from "../../nostr/pool";
import { useSession } from "../../signer/session";
import { useDeck } from "../../store/deck";
import { HashtagChipRows, toggleTagPin } from "../compose/HashtagChipRows";
import { loadUsedHashtags, recentHashtagChips, usePinnedHashtags } from "../compose/storage";
import styles from "./ColumnDialog.module.css";

/** 任意入力の 1 件。wss:// で始まらなければ無視する（ネイティブと同じ既定リレーの体裁を保つ） */
function normalizeCustomRelay(value: string): string | null {
  const url = value.trim();
  return url.startsWith("wss://") ? url : null;
}

/**
 * テンプレの設定入力（カラム追加・フィルター編集で共通。ネイティブの ConfigPane / EditColumnDialog の中身）。
 * TEXT = 1 行入力、NOTIF_FILTER = 種別のチェック、RELAY_SET = リレー URL を 1 行 1 つ。
 */
export function ColumnConfigForm({
  template,
  initialText = "",
  initialRelays = [],
  initialKinds,
  submitLabel,
  cancelLabel,
  onCancel,
  onSubmit,
}: {
  template: ColumnTemplate;
  initialText?: string;
  initialRelays?: readonly string[];
  /** 通知種別の初期値（無ければすべて on） */
  initialKinds?: readonly number[];
  submitLabel: string;
  cancelLabel: string;
  onCancel: () => void;
  onSubmit: (spec: ColumnSpec) => void;
}) {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const pinnedTags = usePinnedHashtags(me);
  const [usedTags] = useState(loadUsedHashtags);
  const recentTags = recentHashtagChips(usedTags, pinnedTags);
  const [text, setText] = useState(initialText);
  const [relays, setRelays] = useState<readonly string[]>(initialRelays);
  const [customRelay, setCustomRelay] = useState("");
  const [kinds, setKinds] = useState<ReadonlySet<number>>(
    () => new Set(initialKinds ?? NOTIF_KINDS.map((k) => k.kind)),
  );
  const [invalid, setInvalid] = useState(false);
  const errorId = useId();
  const canSubmit = template.config !== "TEXT" || text.trim() !== "";
  // 購読中の読むリレー（候補）+ 選択済みだが候補に無い URL（カスタム追加分）。ネイティブと同じ並び
  const readRelays = useReadRelays();
  const relayRows = [...readRelays, ...relays.filter((r) => !readRelays.includes(r))].filter(
    (r, i, all) => all.indexOf(r) === i,
  );

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    const existing = new Set(useDeck.getState().columns.map((c) => c.id));
    const spec = buildColumn(
      template.template,
      {
        text,
        notifKinds: NOTIF_KINDS.filter((k) => kinds.has(k.kind)).map((k) => k.kind),
        relays: [...relays],
      },
      existing,
      unixNow(),
    );
    if (spec === null) {
      setInvalid(true);
      return;
    }
    onSubmit(spec);
  };

  const toggleKind = (kind: number, checked: boolean) => {
    setKinds((prev) => {
      const next = new Set(prev);
      if (checked) next.add(kind);
      else next.delete(kind);
      return next;
    });
  };

  const toggleRelay = (url: string, checked: boolean) => {
    setRelays((prev) => (checked ? [...prev, url] : prev.filter((r) => r !== url)));
  };

  const addCustomRelay = () => {
    const url = normalizeCustomRelay(customRelay);
    if (url && !relays.includes(url)) setRelays((prev) => [...prev, url]);
    setCustomRelay("");
  };

  return (
    <form className={styles.form} onSubmit={submit}>
      {template.config === "TEXT" && (
        <div className={styles.field}>
          <input
            type="text"
            className={styles.input}
            value={text}
            placeholder={templateHint(template.template)}
            aria-label={templateLabel(template.template)}
            aria-invalid={invalid}
            aria-describedby={invalid ? errorId : undefined}
            autoComplete="off"
            onChange={(e) => {
              setText(e.target.value);
              setInvalid(false);
            }}
          />
          {invalid && (
            <p id={errorId} className={styles.error}>
              {t("web_columnform_npub_required")}
            </p>
          )}
          {/* [#642] ハッシュタグのときだけ投稿画面と同じ 2 段のチップ（ネイティブ AddColumnSheet。編集は Web 独自） */}
          {template.template === "HASHTAG" && (
            <div>
              <HashtagChipRows
                pinned={pinnedTags}
                recent={recentTags}
                onTap={(tag) => {
                  setText(tag);
                  setInvalid(false);
                }}
                onToggle={(tag, pin) => toggleTagPin(me, pinnedTags, tag, pin)}
              />
            </div>
          )}
        </div>
      )}
      {template.config === "NOTIF_FILTER" && (
        <fieldset className={styles.kinds}>
          <legend className={styles.caption}>{t("add_column_kinds")}</legend>
          {NOTIF_KINDS.map((k) => (
            <label key={k.kind} className={styles.check}>
              <input
                type="checkbox"
                checked={kinds.has(k.kind)}
                onChange={(e) => toggleKind(k.kind, e.target.checked)}
              />
              {notifKindLabel(k.kind)}
            </label>
          ))}
        </fieldset>
      )}
      {template.config === "RELAY_SET" && (
        <fieldset className={styles.relaySet}>
          <legend className={styles.caption}>
            {relays.length === 0 ? t("relayset_all") : t("relayset_count_fmt", relays.length)}
          </legend>
          <div className={styles.relayList}>
            {relayRows.map((url) => (
              <label key={url} className={styles.check}>
                <input
                  type="checkbox"
                  checked={relays.includes(url)}
                  onChange={(e) => toggleRelay(url, e.target.checked)}
                />
                <span className={styles.relayUrl}>{url}</span>
              </label>
            ))}
          </div>
          <div className={styles.relayAdd}>
            <input
              type="text"
              className={styles.input}
              value={customRelay}
              placeholder={t("web_columnform_relay_placeholder")}
              aria-label={t("web_columnform_relay_url")}
              autoComplete="off"
              spellCheck={false}
              onChange={(e) => setCustomRelay(e.target.value)}
            />
            <button
              type="button"
              className={styles.ghost}
              aria-label={t("web_columnform_relay_add")}
              disabled={normalizeCustomRelay(customRelay) === null}
              onClick={addCustomRelay}
            >
              {t("common_add")}
            </button>
          </div>
        </fieldset>
      )}
      <div className={styles.actions}>
        <button type="button" className={styles.ghost} onClick={onCancel}>
          {cancelLabel}
        </button>
        <button type="submit" className={styles.primary} disabled={!canSubmit}>
          {submitLabel}
        </button>
      </div>
    </form>
  );
}
