import { type FormEvent, useId, useState } from "react";
import { t, useT } from "../../i18n";
import { shortNpub } from "../../lib/npub";
import { displayName, useProfile } from "../../nostr/loaders";
import { currentSigner, useSession } from "../../signer/session";
import { Icon } from "../../ui/icons";
import { showToast } from "../../ui/toast";
import { reportBlock } from "../mute/block";
import { type MuteCategory, type MuteEntry, useMute } from "../mute/muteList";
import { addMuteWord, MuteListError, saveMuteList, signerCanPrivateMute } from "../mute/muteSync";
import styles from "./SettingsSections.module.css";

/** 種別の見出し（ネイティブ mute_cat_*）と並び。ユーザー（p）は公開 / 非公開ではなくミュート / ブロックで出すので別に描く */
const categoryLabels = (): readonly { category: MuteCategory; label: string }[] => [
  { category: "word", label: t("mute_cat_words") },
  { category: "t", label: t("mute_cat_hashtags") },
  { category: "e", label: t("mute_cat_threads") },
];

/** 変更の失敗の文言 */
function failureMessage(e: unknown): string {
  if (e instanceof MuteListError) {
    switch (e.reason) {
      case "no-mute-list":
        return t("web_mute_no_base");
      case "stale":
        return t("web_mute_stale");
      case "locked":
        return t("mute_locked");
      case "no-cipher":
        return t("web_mute_no_cipher");
    }
  }
  // ネイティブ mute_save_failed
  return t("mute_save_failed");
}

/** 編集中の下書き。basedOnId = 編集を始めた時点の版の id（無ければ null。ネイティブに無い web 独自の #478 対策） */
type Draft = { basedOnId: string | null; entries: MuteEntry[] };

type MutePatch = Partial<Pick<MuteEntry, "isPublic" | "isPrivate">>;

/** [#963] ユーザーの行の 2 択。ミュート = 非公開の p だけ、ブロック = 公開の p だけ */
const MUTE_MODE_PATCH = {
  mute: { isPublic: false, isPrivate: true },
  block: { isPublic: true, isPrivate: false },
  remove: { isPublic: false, isPrivate: false },
} as const satisfies Record<string, MutePatch>;

/** 保存で新たにブロックになる人（前は公開の p に無く、下書きで公開の p に入れた人。ミュートからの切り替えを含む） */
export function newlyBlocked(before: readonly MuteEntry[], after: readonly MuteEntry[]): string[] {
  const wasBlocked = new Set(before.filter((e) => e.category === "p" && e.isPublic).map((e) => e.value));
  return [
    ...new Set(
      after.filter((e) => e.category === "p" && e.isPublic && !wasBlocked.has(e.value)).map((e) => e.value),
    ),
  ];
}

/**
 * ミュート（ネイティブ MuteSettings。NIP-51 kind:10000 の公開 + 復号した非公開）。行ごとの公開 / 非公開の
 * チェックを下書きに反映し、変更があれば下部の「保存」で 1 回にまとめて kind:10000 を再発行する
 * （muteSync.ts の saveMuteList。#478: 取り直し・応答なしなら発行しない・basedOnId 照合・非公開部分の再暗号化）。
 * 署名者が暗号（NIP-44 / NIP-04）を使えなければ非公開のチェックを無効にし、案内を出す（公開に黙って倒さない）。
 * [#963] ユーザーの行は「ミュート（非公開の p）| ブロック（公開の p）」の 2 択と外す（✕）。読み込んだ項目が両方に
 * 入っていればブロックとして出す。暗号を使えなければミュートを選べない。保存できたら、新たにブロックにした人へ
 * 通報（NIP-56。block.ts の reportBlock）を送る。
 * ⋯ メニューからの 1 件のミュート・ブロック / 解除（muteUser / blockUser など）はここを介さず、その場で発行する。
 */
export function MuteSection() {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const list = useMute((s) => s.list);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [saving, setSaving] = useState(false);
  // RequireSession の内側なので pubkey は必ずある
  if (!me) return null;

  const canPrivate = signerCanPrivateMute(currentSigner());
  const entries = draft?.entries ?? list?.entries ?? [];
  const editable = list !== null && !list.locked && !saving;

  function toggle(entry: MuteEntry, patch: MutePatch) {
    if (!list) return;
    setDraft((prev) => {
      const base = prev ?? { basedOnId: list.eventId, entries: list.entries };
      return {
        basedOnId: base.basedOnId,
        entries: base.entries.map((e) =>
          e.category === entry.category && e.value === entry.value ? { ...e, ...patch } : e,
        ),
      };
    });
  }

  async function save() {
    if (!me || !draft) return;
    setSaving(true);
    const blocked = newlyBlocked(list?.entries ?? [], draft.entries);
    try {
      await saveMuteList(me, draft.entries, draft.basedOnId);
      setDraft(null);
      // ネイティブ mute_saved
      showToast(t("mute_saved"));
      for (const pubkey of blocked) {
        reportBlock(pubkey).catch((e) => console.warn("[mute] Failed to report the block", e));
      }
    } catch (e) {
      // 最新版と食い違っていたら下書きを捨てて最新の内容を出し直す
      if (e instanceof MuteListError && e.reason === "stale") setDraft(null);
      showToast(failureMessage(e));
    } finally {
      setSaving(false);
    }
  }

  function addWord(word: string) {
    if (!me || !list || list.locked) return;
    addMuteWord(me, word, list.eventId)
      .then((result) => showToast(result === "done" ? t("mute_word_added") : t("mute_add_failed")))
      .catch((e: unknown) => showToast(failureMessage(e)));
  }

  return (
    <>
      <div className={styles.block}>
        <h3 className={styles.caption}>{t("mute_list_title")}</h3>
        <p className={styles.desc}>{t("mute_list_desc")}</p>
        <AddWordForm entries={entries} disabled={!editable} onAdd={addWord} />
      </div>
      <div className={styles.block}>
        {list === null ? (
          <p className={styles.desc} role="status">
            {t("mute_loading")}
          </p>
        ) : (
          <>
            {list.locked && <p className={styles.note}>{t("mute_locked")}</p>}
            {!canPrivate && <p className={styles.note}>{t("web_mute_no_cipher")}</p>}
            {entries.length === 0 ? (
              <p className={styles.desc}>{t("mute_empty")}</p>
            ) : (
              <>
                <MuteUserGroup
                  entries={entries.filter((e) => e.category === "p")}
                  editable={editable}
                  canPrivate={canPrivate}
                  onChange={toggle}
                />
                {entries.some((e) => e.category !== "p") && <MuteLegend />}
                {categoryLabels().map(({ category, label }) => (
                  <MuteGroup
                    key={category}
                    label={label}
                    entries={entries.filter((e) => e.category === category)}
                    editable={editable}
                    canPrivate={canPrivate}
                    onToggle={toggle}
                  />
                ))}
              </>
            )}
          </>
        )}
        {draft !== null && <SaveBar saving={saving} onSave={() => void save()} />}
      </div>
    </>
  );
}

function AddWordForm({
  entries,
  disabled,
  onAdd,
}: {
  entries: readonly MuteEntry[];
  disabled: boolean;
  onAdd(word: string): void;
}) {
  const inputId = useId();
  const [value, setValue] = useState("");
  const [error, setError] = useState<string | null>(null);

  function submit(e: FormEvent) {
    e.preventDefault();
    const word = value.trim();
    if (word === "" || disabled) return;
    if (entries.some((m) => m.category === "word" && m.value.toLowerCase() === word.toLowerCase())) {
      setError(t("web_settings_mute_word_already_added"));
      return;
    }
    onAdd(word);
    setValue("");
    setError(null);
  }

  return (
    <form className={styles.row} onSubmit={submit}>
      <label htmlFor={inputId} className="srOnly">
        {t("web_settings_mute_word_label")}
      </label>
      <input
        id={inputId}
        className={styles.input}
        type="text"
        autoCapitalize="off"
        autoCorrect="off"
        spellCheck={false}
        placeholder={t("mute_word_placeholder")}
        value={value}
        aria-invalid={error !== null}
        onChange={(e) => {
          setValue(e.target.value);
          setError(null);
        }}
      />
      <button type="submit" className={styles.ghost} disabled={disabled || value.trim() === ""}>
        {t("common_add")}
      </button>
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
    </form>
  );
}

/** 公開 / 非公開の列見出し（ネイティブ ColumnLegend） */
function MuteLegend() {
  const t = useT();
  return (
    <div className={styles.muteLegend} aria-hidden="true">
      <span className={styles.muteLegendLabel}>{t("mute_public")}</span>
      <span className={styles.muteLegendLabel}>{t("mute_private")}</span>
    </div>
  );
}

/** 種別ごとの一覧（見出しに件数。両方外した項目も編集中は残す） */
function MuteGroup({
  label,
  entries,
  editable,
  canPrivate,
  onToggle,
}: {
  label: string;
  entries: readonly MuteEntry[];
  editable: boolean;
  canPrivate: boolean;
  onToggle(entry: MuteEntry, patch: MutePatch): void;
}) {
  if (entries.length === 0) return null;
  const count = entries.filter((e) => e.isPublic || e.isPrivate).length;
  return (
    <section aria-label={label}>
      <h4 className={styles.caption}>{`${label} (${count})`}</h4>
      <ul className={styles.relays}>
        {entries.map((entry) => (
          <MuteRow
            key={`${entry.category}:${entry.value}`}
            entry={entry}
            editable={editable}
            canPrivate={canPrivate}
            onToggle={(patch) => onToggle(entry, patch)}
          />
        ))}
      </ul>
    </section>
  );
}

function MuteRow({
  entry,
  editable,
  canPrivate,
  onToggle,
}: {
  entry: MuteEntry;
  editable: boolean;
  canPrivate: boolean;
  onToggle(patch: MutePatch): void;
}) {
  const label = entryLabel(entry);
  const removed = !entry.isPublic && !entry.isPrivate;
  return (
    <li className={styles.relay}>
      <span className={`${styles.relayUrl} ${removed ? styles.dimmed : ""}`} title={entry.value}>
        {label}
      </span>
      <span className={styles.muteChecks}>
        <span className={styles.muteCheck}>
          <input
            type="checkbox"
            aria-label={t("web_settings_mute_public_label", label)}
            checked={entry.isPublic}
            disabled={!editable}
            onChange={(e) => onToggle({ isPublic: e.target.checked })}
          />
        </span>
        <span className={styles.muteCheck}>
          <input
            type="checkbox"
            aria-label={t("web_settings_mute_private_label", label)}
            checked={entry.isPrivate}
            disabled={!editable || !canPrivate}
            onChange={(e) => onToggle({ isPrivate: e.target.checked })}
          />
        </span>
      </span>
    </li>
  );
}

/** [#963] ユーザーの群（見出しに件数・説明。行は ミュート | ブロック の 2 択と外す） */
function MuteUserGroup({
  entries,
  editable,
  canPrivate,
  onChange,
}: {
  entries: readonly MuteEntry[];
  editable: boolean;
  canPrivate: boolean;
  onChange(entry: MuteEntry, patch: MutePatch): void;
}) {
  const t = useT();
  if (entries.length === 0) return null;
  const label = t("mute_cat_users");
  const count = entries.filter((e) => e.isPublic || e.isPrivate).length;
  return (
    <section aria-label={label}>
      <h4 className={styles.caption}>{`${label} (${count})`}</h4>
      <p className={styles.desc}>{t("mute_mode_desc")}</p>
      <ul className={styles.relays}>
        {entries.map((entry) => (
          <MuteUserRow
            key={entry.value}
            entry={entry}
            editable={editable}
            canPrivate={canPrivate}
            onChange={(patch) => onChange(entry, patch)}
          />
        ))}
      </ul>
    </section>
  );
}

function MuteUserRow({
  entry,
  editable,
  canPrivate,
  onChange,
}: {
  entry: MuteEntry;
  editable: boolean;
  canPrivate: boolean;
  onChange(patch: MutePatch): void;
}) {
  const t = useT();
  const label = entryLabel(entry);
  const removed = !entry.isPublic && !entry.isPrivate;
  // 両方に入っていればブロック（公開で見えているので）
  const blocked = entry.isPublic;
  const muted = !entry.isPublic && entry.isPrivate;
  return (
    <li className={`${styles.relay} ${styles.muteUserRow}`}>
      <MutedUserLabel pubkey={entry.value} dimmed={removed} />
      <span className={styles.muteUserControls}>
        <fieldset className={styles.muteModes}>
          <legend className="srOnly">{label}</legend>
          <button
            type="button"
            className={styles.muteMode}
            aria-pressed={muted}
            disabled={!editable || !canPrivate}
            onClick={() => onChange(MUTE_MODE_PATCH.mute)}
          >
            {t("mute_mode_mute")}
          </button>
          <button
            type="button"
            className={styles.muteMode}
            aria-pressed={blocked}
            disabled={!editable}
            onClick={() => onChange(MUTE_MODE_PATCH.block)}
          >
            {t("mute_mode_block")}
          </button>
        </fieldset>
        <button
          type="button"
          className={styles.muteRemove}
          aria-label={t("web_settings_mute_remove_label", label)}
          disabled={!editable || removed}
          onClick={() => onChange(MUTE_MODE_PATCH.remove)}
        >
          <Icon name="close" size="sm" />
        </button>
      </span>
    </li>
  );
}

function entryLabel(entry: MuteEntry): string {
  switch (entry.category) {
    case "p":
      return shortNpub(entry.value);
    case "t":
      return `#${entry.value}`;
    case "e":
      return `${entry.value.slice(0, 16)}…`;
    case "word":
      return entry.value;
  }
}

/** ミュート中のユーザー（kind:0 の名前 + npub の先頭） */
function MutedUserLabel({ pubkey, dimmed }: { pubkey: string; dimmed: boolean }) {
  const profile = useProfile(pubkey);
  return (
    <span className={`${styles.relayUrl} ${dimmed ? styles.dimmed : ""}`}>
      <span className={styles.name}>{displayName(profile, pubkey)}</span>
      <span className={styles.shortNpub}>{shortNpub(pubkey)}</span>
    </span>
  );
}

/** 変更があるときだけ出す保存バー（ネイティブ SaveBar）。保存中は安定するまで入力をロックする */
function SaveBar({ saving, onSave }: { saving: boolean; onSave(): void }) {
  const t = useT();
  return (
    <div className={styles.saveBar}>
      <p className={styles.caption} role="status">
        {
          saving
            ? t("mute_saving_wait") // ネイティブ mute_saving_wait
            : t("mute_dirty") /* ネイティブ mute_dirty */
        }
      </p>
      <button type="button" className={styles.primary} disabled={saving} onClick={onSave}>
        {saving ? t("common_saving") : t("common_save")}
      </button>
    </div>
  );
}
