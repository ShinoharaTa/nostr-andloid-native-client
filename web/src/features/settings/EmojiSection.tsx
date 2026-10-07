import { use$ } from "applesauce-react/hooks/use-$";
import type { NostrEvent } from "nostr-tools/pure";
import { type FormEvent, useId, useMemo, useState } from "react";
import { t, useT } from "../../i18n";
import { markProxyBlocked, originOf, proxied } from "../../lib/imageProxy";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { showToast } from "../../ui/toast";
import {
  type CustomEmoji,
  customEmojisFrom,
  EmojiListError,
  emojiListChanges,
  parseEmojiShortcode,
  parseEmojiUrl,
  publishEmojiList,
} from "../compose/customEmojis";
import { EmojiMakerForm, useEmojiMaker } from "../emoji/EmojiMakerForm";
import makerStyles from "../emoji/EmojiMakerRoute.module.css";
import { loadLastMakerInput } from "../emoji/lastMakerInput";
import styles from "./SettingsSections.module.css";

/** 保存の失敗の文言 */
function failureMessage(e: unknown): string {
  if (e instanceof EmojiListError) {
    switch (e.reason) {
      case "no-emoji-list":
        return t("web_emoji_no_base");
      case "stale":
        return t("web_emoji_stale");
    }
  }
  // ネイティブ emoji_save_failed
  return t("emoji_save_failed");
}

/** 自分の kind:10030 直下の emoji タグ（30030 セット由来は含まない。ネイティブ myEmojiListFlow 相当） */
function useOwnEmojiList(me: string | null): { latest: NostrEvent | undefined; current: CustomEmoji[] } {
  const latest = use$(() => (me ? eventStore.replaceable({ kind: 10030, pubkey: me }) : undefined), [me]);
  const current = useMemo(() => customEmojisFrom(latest, []), [latest]);
  return { latest, current };
}

/**
 * カスタム絵文字（ネイティブ EmojiEditorSettings。NIP-51 kind:10030 の emoji タグだけを編集）。
 * 編集は手元の下書きに溜め、「保存して公開」で kind:10030 を再発行する（保存のたびに発行しない）。
 * 発行は最新版のタグを土台に、削除したものを除き追加分を末尾に足す（#762）。
 * 30030 セット参照（a タグ）や https でない emoji タグはそのまま維持し、ここには出さない。
 */
export function EmojiSection() {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const { latest, current } = useOwnEmojiList(me);
  const [draft, setDraft] = useState<CustomEmoji[] | null>(null);
  // 編集を始めた時点の自分の kind:10030（無ければ null）。保存の直前に取り直した版と違えば公開しない
  const [basedOnId, setBasedOnId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const list = draft ?? current;

  function edit(next: CustomEmoji[]) {
    if (draft === null) setBasedOnId(latest?.id ?? null);
    setDraft(next);
  }

  async function save() {
    if (!me) return;
    setSaving(true);
    try {
      // 下書きの差分（削除・追加）だけを取り直した最新版に当てる。画面に出さない emoji タグは保たれる（#762）
      await publishEmojiList(
        me,
        emojiListChanges(current, list),
        draft === null ? (latest?.id ?? null) : basedOnId,
      );
      setDraft(null);
      showToast(t("emoji_saved"));
    } catch (e) {
      // 最新版と食い違っていたら下書きを捨てて最新の内容を出し直す
      if (e instanceof EmojiListError && e.reason === "stale") setDraft(null);
      showToast(failureMessage(e));
    } finally {
      setSaving(false);
    }
  }

  if (!me) return null;

  return (
    <>
      <div className={styles.block}>
        <p className={styles.desc}>{t("web_settings_emoji_desc")}</p>
      </div>
      <div className={styles.block}>
        {list.length === 0 ? (
          <p className={styles.desc}>{t("emoji_empty")}</p>
        ) : (
          <ul className={styles.relays} aria-label={t("web_settings_emoji_list_label")}>
            {list.map((emoji) => (
              <EmojiRow
                key={emoji.shortcode}
                emoji={emoji}
                onRemove={() => edit(list.filter((e) => e.shortcode !== emoji.shortcode))}
              />
            ))}
          </ul>
        )}
        <AddEmojiPanel list={list} onAdd={(emoji) => edit([...list, emoji])} />
        <button
          type="button"
          className={`${styles.primary} ${styles.alignStart}`}
          disabled={saving || draft === null}
          onClick={() => void save()}
        >
          {saving ? t("web_settings_emoji_publishing") : t("emoji_save")}
        </button>
      </div>
    </>
  );
}

function EmojiRow({ emoji, onRemove }: { emoji: CustomEmoji; onRemove(): void }) {
  const t = useT();
  const [src, setSrc] = useState<string | null>(() => proxied(emoji.url, 48, 80, true));

  function onError() {
    const origin = originOf(src);
    if (origin) {
      markProxyBlocked(origin);
      setSrc(origin);
    } else {
      setSrc(null);
    }
  }

  return (
    <li className={styles.relay}>
      {src !== null && (
        <img
          className={styles.emojiThumb}
          src={src}
          alt=""
          loading="lazy"
          decoding="async"
          referrerPolicy="no-referrer"
          onError={onError}
        />
      )}
      <span className={styles.relayUrl}>{`:${emoji.shortcode}:`}</span>
      <button
        type="button"
        className={styles.textButton}
        aria-label={t("web_settings_relay_remove_label", `:${emoji.shortcode}:`)}
        onClick={onRemove}
      >
        {t("common_delete")}
      </button>
    </li>
  );
}

type AddMode = "url" | "maker";

/** 追加欄（「URL で追加」と「文字から作る」の切り替え。#763）。どちらも下書きに足すだけで、発行は「保存して公開」 */
function AddEmojiPanel({ list, onAdd }: { list: readonly CustomEmoji[]; onAdd(emoji: CustomEmoji): void }) {
  const t = useT();
  const [mode, setMode] = useState<AddMode>("url");

  const choice = (value: AddMode, label: string) => (
    <button
      type="button"
      className={styles.choice}
      aria-pressed={mode === value}
      onClick={() => setMode(value)}
    >
      {label}
    </button>
  );

  return (
    <>
      <div className={styles.choices}>
        {choice("url", t("web_settings_emoji_add_by_url"))}
        {choice("maker", t("web_settings_emoji_add_by_maker"))}
      </div>
      {mode === "url" ? (
        <AddEmojiForm list={list} onAdd={onAdd} />
      ) : (
        <MakeEmojiForm list={list} onAdd={onAdd} />
      )}
    </>
  );
}

/**
 * 追加欄の入力を検証する（「URL で追加」「文字から作る」で共通）。通れば絵文字、通らなければ欄の下に出す文言。
 * url は検証済みの画像 URL（使えなければ null）。
 */
function parseNewEmoji(
  list: readonly CustomEmoji[],
  rawCode: string,
  url: string | null,
): CustomEmoji | string {
  const shortcode = parseEmojiShortcode(rawCode);
  if (!shortcode || !url) return t("web_settings_emoji_input_invalid");
  if (list.some((e) => e.shortcode === shortcode)) return t("web_settings_emoji_already_added");
  return { shortcode, url };
}

function AddEmojiForm({ list, onAdd }: { list: readonly CustomEmoji[]; onAdd(emoji: CustomEmoji): void }) {
  const t = useT();
  const codeId = useId();
  const urlId = useId();
  const [code, setCode] = useState("");
  const [url, setUrl] = useState("");
  const [error, setError] = useState<string | null>(null);

  function submit(e: FormEvent) {
    e.preventDefault();
    const emoji = parseNewEmoji(list, code, parseEmojiUrl(url));
    if (typeof emoji === "string") {
      setError(emoji);
      return;
    }
    onAdd(emoji);
    setCode("");
    setUrl("");
    setError(null);
  }

  return (
    <form className={styles.row} onSubmit={submit}>
      <label htmlFor={codeId} className="srOnly">
        {t("web_settings_emoji_shortcode_label")}
      </label>
      <input
        id={codeId}
        className={styles.input}
        type="text"
        autoCapitalize="off"
        autoCorrect="off"
        spellCheck={false}
        placeholder={t("emoji_shortcode_hint")}
        value={code}
        onChange={(e) => {
          setCode(e.target.value);
          setError(null);
        }}
      />
      <label htmlFor={urlId} className="srOnly">
        {t("web_settings_emoji_url_label")}
      </label>
      <input
        id={urlId}
        className={styles.input}
        type="text"
        inputMode="url"
        autoCapitalize="off"
        autoCorrect="off"
        spellCheck={false}
        placeholder={t("emoji_url_hint")}
        value={url}
        onChange={(e) => {
          setUrl(e.target.value);
          setError(null);
        }}
      />
      <button type="submit" className={styles.ghost} disabled={code.trim() === "" || url.trim() === ""}>
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

/**
 * 「文字から作る」（#763）。/emoji と同じフォーム（EmojiMakerForm）で作り、ショートコードを付けて下書きに足す。
 * 初期値は前回使った絵文字の文字色・縁取り・フォント（無ければ黒文字 + 白縁取り。#783）。下書きに足したら覚える。
 * テキストが空・プレビューがエラーの間は押せない。
 */
function MakeEmojiForm({ list, onAdd }: { list: readonly CustomEmoji[]; onAdd(emoji: CustomEmoji): void }) {
  const t = useT();
  const codeId = useId();
  const maker = useEmojiMaker(loadLastMakerInput);
  const url = maker.url !== null && maker.error === null ? maker.url : null;
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);

  function submit(e: FormEvent) {
    e.preventDefault();
    if (url === null) return;
    const emoji = parseNewEmoji(list, code, url);
    if (typeof emoji === "string") {
      setError(emoji);
      return;
    }
    onAdd(emoji);
    maker.remember();
    setCode("");
    setError(null);
  }

  return (
    <form className={makerStyles.form} onSubmit={submit}>
      <EmojiMakerForm maker={maker} />
      <div className={makerStyles.field}>
        <label htmlFor={codeId} className={makerStyles.label}>
          {t("web_settings_emoji_shortcode_label")}
        </label>
        <div className={styles.row}>
          <input
            id={codeId}
            className={styles.input}
            type="text"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
            placeholder={t("emoji_shortcode_hint")}
            value={code}
            onChange={(e) => {
              setCode(e.target.value);
              setError(null);
            }}
          />
          <button type="submit" className={styles.ghost} disabled={url === null || code.trim() === ""}>
            {t("common_add")}
          </button>
        </div>
        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}
      </div>
    </form>
  );
}
