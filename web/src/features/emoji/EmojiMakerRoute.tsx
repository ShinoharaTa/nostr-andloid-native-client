import { type FormEvent, useId, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { UpdateToast } from "../../app/UpdateToast";
import { t, useT } from "../../i18n";
import { useSession } from "../../signer/session";
import { Toaster } from "../../ui/Toaster";
import { showToast } from "../../ui/toast";
import { appendToEmojiList, EmojiListError, parseEmojiShortcode } from "../compose/customEmojis";
import { EmojiMakerForm, useEmojiMaker } from "./EmojiMakerForm";
import styles from "./EmojiMakerRoute.module.css";
import { type FontId, inputFromQuery } from "./emojiUrl";
import { loadLastMakerInput } from "./lastMakerInput";

export { PREVIEW_DEBOUNCE_MS } from "./EmojiMakerForm";

/** フォントのライセンス表記（§3.4。OFL 本文は public/fonts/ に同梱） */
const FONT_LICENSES: readonly { font: FontId; href: string }[] = [
  { font: "notosans", href: "/fonts/OFL-notosansjp.txt" },
  { font: "mplusrounded", href: "/fonts/OFL-mplusrounded1c.txt" },
  { font: "delagothic", href: "/fonts/OFL-delagothicone.txt" },
];

/**
 * 文字から絵文字画像を作るページ（/emoji。仕様: docs/emoji-maker.md §7）。ログイン不要。
 * フォームは EmojiMakerForm（設定「カスタム絵文字」の「文字から作る」と共通）。ここでは URL の表示・コピーと追加を持つ。
 * 入力は URL に書き戻さない（履歴が汚れる）。
 * [#783] クエリが無ければ前回の設定（文字色・縁取り・フォント）で始める。URL をコピーした・リストに追加したら覚える。
 */
export function EmojiMakerRoute() {
  const t = useT();
  const status = useSession((s) => s.status);
  const me = useSession((s) => s.pubkey);
  const [search] = useSearchParams();
  const maker = useEmojiMaker(() => inputFromQuery(search, loadLastMakerInput));
  const { url, error } = maker;
  const urlRef = useRef<HTMLInputElement>(null);
  const urlId = useId();

  async function copy() {
    if (url === null) return;
    try {
      await navigator.clipboard.writeText(url);
      maker.remember();
      showToast(t("web_emoji_copied"));
    } catch {
      urlRef.current?.select();
      showToast(t("web_emoji_copy_failed"));
    }
  }

  return (
    <main className={styles.page}>
      {/* AppShell を通らないので、SW の更新通知とトーストはここで出す */}
      <UpdateToast />
      <header className={styles.header}>
        <img className={styles.logo} src="/icons/icon-192.png" alt="" width={32} height={32} />
        <span className={styles.brand}>Nostrism</span>
        <Link className={styles.back} to={status === "in" ? "/" : "/about"}>
          {t("web_emoji_back")}
        </Link>
      </header>

      <h1 className={styles.title}>{t("web_emoji_title")}</h1>
      <p className={styles.lead}>{t("web_emoji_desc")}</p>

      <EmojiMakerForm maker={maker} />

      <div className={styles.field}>
        <label htmlFor={urlId} className={styles.label}>
          {t("web_emoji_url_label")}
        </label>
        <div className={styles.row}>
          <input
            id={urlId}
            ref={urlRef}
            className={styles.input}
            type="text"
            readOnly
            value={url ?? ""}
            onFocus={(e) => e.currentTarget.select()}
          />
          <button
            type="button"
            className={styles.primary}
            disabled={url === null}
            onClick={() => void copy()}
          >
            {t("common_copy")}
          </button>
        </div>
      </div>

      {status === "in" && me !== null && (
        <AddToListForm me={me} url={url !== null && error === null ? url : null} onAdded={maker.remember} />
      )}
      {status === "out" && (
        <p className={styles.note}>
          {t("web_emoji_login_hint")} <Link to="/login?next=%2Femoji">{t("web_emoji_login_link")}</Link>
        </p>
      )}

      <footer className={styles.footer}>
        {t("web_emoji_license_label")}{" "}
        {FONT_LICENSES.map(({ font, href }, i) => (
          <span key={font}>
            {i > 0 && ", "}
            <a href={href} target="_blank" rel="noopener noreferrer">
              {t(`web_emoji_font_${font}`)}
            </a>
          </span>
        ))}{" "}
        — SIL Open Font License 1.1
      </footer>

      <div className={styles.toastLayer}>
        <Toaster />
      </div>
    </main>
  );
}

/**
 * 自分の絵文字リスト（kind:10030）へ足す欄（ログイン中だけ。docs/emoji-maker.md §7.4）。
 * shortcode の形と重複は欄の下に、発行の成否はトーストで出す。url が null（入力が通らない・エラー中）なら押せない。
 * 追加できたら onAdded（前回の設定として覚える。#783）。
 */
function AddToListForm({ me, url, onAdded }: { me: string; url: string | null; onAdded(): void }) {
  const t = useT();
  const codeId = useId();
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (url === null) return;
    const shortcode = parseEmojiShortcode(code);
    if (!shortcode) {
      setError(t("web_settings_emoji_input_invalid"));
      return;
    }
    setAdding(true);
    try {
      await appendToEmojiList(me, { shortcode, url });
      onAdded();
      setCode("");
      setError(null);
      showToast(t("emoji_saved"));
    } catch (e) {
      if (e instanceof EmojiListError && e.reason === "duplicate")
        setError(t("web_settings_emoji_already_added"));
      else showToast(addFailureMessage(e));
    } finally {
      setAdding(false);
    }
  }

  return (
    <form className={styles.field} onSubmit={(e) => void submit(e)}>
      <div className={styles.labelRow}>
        <label htmlFor={codeId} className={styles.label}>
          {t("web_emoji_shortcode_label")}
        </label>
        {/* 足した絵文字の確認・削除は設定の絵文字画面で（#763） */}
        <Link className={styles.settingsLink} to="/settings/emoji">
          {t("web_emoji_open_settings")}
        </Link>
      </div>
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
        <button
          type="submit"
          className={styles.primary}
          disabled={adding || url === null || code.trim() === ""}
        >
          {adding ? t("web_emoji_adding") : t("web_emoji_add")}
        </button>
      </div>
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
    </form>
  );
}

/** 追加の失敗の文言（重複は欄の下に出すのでここには来ない。stale は追加では起きない） */
function addFailureMessage(e: unknown): string {
  if (e instanceof EmojiListError && e.reason === "no-emoji-list") return t("web_emoji_no_base");
  // ネイティブ emoji_save_failed
  return t("emoji_save_failed");
}
