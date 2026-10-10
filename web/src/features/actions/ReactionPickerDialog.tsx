import type { NostrEvent } from "nostr-tools/pure";
import { type ReactNode, useEffect, useId, useMemo, useState } from "react";
import { useT } from "../../i18n";
import { markProxyBlocked, originOf, proxied } from "../../lib/imageProxy";
import { displayName, useProfile } from "../../nostr/loaders";
import { useSession } from "../../signer/session";
import { ArrowBackIcon } from "../../ui/icons";
import { ModalSheet } from "../../ui/ModalSheet";
import { parseEmojiShortcode, useCustomEmojis } from "../compose/customEmojis";
import { ProfileAvatar } from "../compose/ProfileAvatar";
import { autoEmojiShortcode } from "../emoji/autoShortcode";
import { EmojiMakerForm, useEmojiMaker } from "../emoji/EmojiMakerForm";
import makerStyles from "../emoji/EmojiMakerRoute.module.css";
import { loadLastMakerInput } from "../emoji/lastMakerInput";
import { NoteContent } from "../timeline/NoteContent";
import { EMOJI_CATEGORIES, type EmojiCategory, loadEmojiCatalog, searchEmojis } from "./emojiCatalog";
import type { MadeEmoji } from "./pickedReaction";
import styles from "./ReactionPickerDialog.module.css";
import { loadRecentEmojis } from "./reactionPrefs";

/**
 * リアクションピッカー（ネイティブ ReactionPicker.kt + AppModalSheet）。画面上端寄せのカードをモーダルで開く。
 * 検索なし = 最近 → カスタム絵文字 → Unicode のカテゴリタブ + グリッド、検索あり = カスタム → 絵文字。
 * 選ぶと onPick して閉じる。target があれば対象の投稿（アバター・名前・本文 2 行）を上に出す（投稿画面の絵文字ボタンでは無し）。
 * [#587] 器は共通の ModalSheet（旧: 自前の dialog + card）。
 * [#684] 開いたら emojibase-data（標準の絵文字全部）を動的 import。読み込み中は厳選リストのまま使え、
 * 終わったら全カテゴリに差し替わる。カテゴリは最大でも数百件なので、タブで選んだカテゴリだけ描画して仮想化の代わりにする。
 * [#768] target がある（= リアクションを送る）ときだけ「絵文字を作る」を出す。押すと一覧の代わりに作成フォーム
 * （MakeEmojiPane）になり、「この絵文字でリアクション」で onPick(:code:, 画像 URL, MadeEmoji) する。
 * 投稿画面の絵文字ボタン・設定の既定リアクションでは出さない（送るのはリアクションではないため）。
 */
export function ReactionPickerDialog({
  target,
  onPick,
  onClose,
}: {
  target?: NostrEvent;
  onPick(content: string, imageUrl: string | null, made?: MadeEmoji): void;
  onClose(): void;
}) {
  const t = useT();
  const [query, setQuery] = useState("");
  const [recent] = useState(loadRecentEmojis);
  const me = useSession((s) => s.pubkey);
  const customs = useCustomEmojis(me);
  const [categories, setCategories] = useState<readonly EmojiCategory[]>(EMOJI_CATEGORIES);
  const [activeTab, setActiveTab] = useState(0);
  const [making, setMaking] = useState(false);

  useEffect(() => {
    let alive = true;
    loadEmojiCatalog().then((full) => {
      if (!alive) return;
      setCategories(full);
      setActiveTab(0);
    });
    return () => {
      alive = false;
    };
  }, []);

  function pick(content: string, imageUrl: string | null, made?: MadeEmoji) {
    if (made) onPick(content, imageUrl, made);
    else onPick(content, imageUrl);
    onClose();
  }

  const q = query.trim();
  const matchedCustom = useMemo(() => {
    const lower = q.toLowerCase();
    return q === "" ? [] : customs.filter((c) => c.shortcode.toLowerCase().includes(lower));
  }, [customs, q]);
  // biome-ignore lint/correctness/useExhaustiveDependencies: categories は catalog 差し替えのキー（loadEmojiCatalog 完了で全絵文字に切り替わったら再検索するため）
  const matchedUnicode = useMemo(() => searchEmojis(q), [q, categories]);
  const activeCategory = categories[activeTab] ?? categories[0];

  return (
    <ModalSheet title={t("picker_title")} onDismiss={onClose}>
      {target && <TargetHeader target={target} />}
      {making ? (
        <MakeEmojiPane
          onBack={() => setMaking(false)}
          onReact={(shortcode, url, made) => pick(`:${shortcode}:`, url, made)}
        />
      ) : (
        <>
          <div className={styles.searchRow}>
            <input
              type="search"
              className={styles.search}
              aria-label={t("web_picker_search_label")}
              placeholder={t("picker_search_placeholder")}
              enterKeyHint="search"
              value={query}
              onChange={(e) => setQuery(e.currentTarget.value)}
            />
            {target && (
              <button type="button" className={styles.make} onClick={() => setMaking(true)}>
                {t("web_picker_make")}
              </button>
            )}
          </div>
          <div className={styles.scroll}>
            {q === "" ? (
              <>
                {recent.length > 0 && (
                  <Section title={t("picker_recent")}>
                    {recent.map((r) =>
                      r.imageUrl ? (
                        <ImageCell
                          key={r.content}
                          label={r.content}
                          url={r.imageUrl}
                          onClick={() => pick(r.content, r.imageUrl)}
                        />
                      ) : (
                        <TextCell key={r.content} char={r.content} onClick={() => pick(r.content, null)} />
                      ),
                    )}
                  </Section>
                )}
                {customs.length > 0 && (
                  <Section title={t("picker_custom_emoji")}>
                    {customs.map((c) => (
                      <ImageCell
                        key={c.shortcode}
                        label={`:${c.shortcode}:`}
                        url={c.url}
                        onClick={() => pick(`:${c.shortcode}:`, c.url)}
                      />
                    ))}
                  </Section>
                )}
                {activeCategory && (
                  <>
                    <div role="tablist" aria-label={t("web_picker_categories")} className={styles.tabs}>
                      {categories.map((category, i) => (
                        <button
                          key={category.title()}
                          type="button"
                          role="tab"
                          aria-selected={i === activeTab}
                          className={styles.tab}
                          onClick={() => setActiveTab(i)}
                        >
                          {category.title()}
                        </button>
                      ))}
                    </div>
                    <Section title={activeCategory.title()}>
                      {activeCategory.emojis.map((e) => (
                        <TextCell key={e.char} char={e.char} onClick={() => pick(e.char, null)} />
                      ))}
                    </Section>
                  </>
                )}
              </>
            ) : matchedCustom.length === 0 && matchedUnicode.length === 0 ? (
              <p className={styles.empty}>{t("picker_no_match")}</p>
            ) : (
              <>
                {matchedCustom.length > 0 && (
                  <Section title={t("picker_custom")}>
                    {matchedCustom.map((c) => (
                      <ImageCell
                        key={c.shortcode}
                        label={`:${c.shortcode}:`}
                        url={c.url}
                        onClick={() => pick(`:${c.shortcode}:`, c.url)}
                      />
                    ))}
                  </Section>
                )}
                {matchedUnicode.length > 0 && (
                  <Section title={t("picker_emoji")}>
                    {matchedUnicode.map((e) => (
                      <TextCell key={e.char} char={e.char} onClick={() => pick(e.char, null)} />
                    ))}
                  </Section>
                )}
              </>
            )}
          </div>
        </>
      )}
    </ModalSheet>
  );
}

/**
 * [#768] ピッカーの中の「絵文字を作る」（/emoji と同じ EmojiMakerForm。初期値は前回送った絵文字の文字色・縁取り・
 * フォントで、無ければ黒文字 + 白縁取り。#783）。
 * 「戻る」で一覧に戻る。ショートコードは任意（空なら autoEmojiShortcode の名前。プレースホルダに出す）で、入力したら
 * 設定画面と同じ parseEmojiShortcode で検証する。テキストが空・プレビューがエラー・今の入力のプレビューがまだ取れて
 * いない・名前が不正の間は送れない。フォームはスクロールし、送信ボタンは下に固定する。
 */
function MakeEmojiPane({
  onBack,
  onReact,
}: {
  onBack(): void;
  onReact(shortcode: string, url: string, made: MadeEmoji): void;
}) {
  const t = useT();
  const codeId = useId();
  const codeHintId = useId();
  const maker = useEmojiMaker(loadLastMakerInput);
  const autoName = useAutoShortcode(maker.url);
  const [code, setCode] = useState("");
  const [save, setSave] = useState(false);

  const typed = code.trim() !== "";
  const shortcode = typed ? parseEmojiShortcode(code) : autoName;
  const invalid = typed && shortcode === null;
  const url = maker.ready && maker.error === null ? maker.url : null;

  return (
    <>
      <div className={styles.makeHead}>
        <button type="button" className={styles.back} onClick={onBack}>
          <ArrowBackIcon className={styles.backIcon} />
          {t("common_back")}
        </button>
        <h3 className={styles.makeTitle}>{t("web_picker_make")}</h3>
      </div>
      <div className={`${styles.scroll} ${styles.makeScroll}`}>
        <EmojiMakerForm maker={maker} />
        <div className={makerStyles.field}>
          <label htmlFor={codeId} className={makerStyles.label}>
            {t("web_picker_make_shortcode_label")}
          </label>
          <input
            id={codeId}
            className={makerStyles.input}
            type="text"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
            placeholder={autoName ?? ""}
            value={code}
            aria-invalid={invalid}
            aria-describedby={codeHintId}
            onChange={(e) => setCode(e.target.value)}
          />
          <p id={codeHintId} className={invalid ? makerStyles.error : makerStyles.hint}>
            {invalid ? t("web_picker_make_shortcode_invalid") : t("web_picker_make_shortcode_hint")}
          </p>
        </div>
        <label className={makerStyles.check}>
          <input type="checkbox" checked={save} onChange={(e) => setSave(e.target.checked)} />
          {t("web_picker_make_save")}
        </label>
      </div>
      <div className={styles.makeFoot}>
        <button
          type="button"
          className={`${makerStyles.primary} ${styles.makeSend}`}
          disabled={url === null || shortcode === null}
          onClick={() => {
            if (url !== null && shortcode !== null) {
              maker.remember();
              onReact(shortcode, url, { made: true, autoName: !typed, save });
            }
          }}
        >
          {t("web_picker_make_react")}
        </button>
      </div>
    </>
  );
}

/** url の自動のショートコード（計算が済むまでと url が null の間は null） */
function useAutoShortcode(url: string | null): string | null {
  const [result, setResult] = useState<{ url: string; name: string } | null>(null);
  useEffect(() => {
    if (url === null) return;
    let alive = true;
    void autoEmojiShortcode(url).then((name) => {
      if (alive) setResult({ url, name });
    });
    return () => {
      alive = false;
    };
  }, [url]);
  return url !== null && result?.url === url ? result.name : null;
}

/** 対象の投稿（アバター 32px + 名前 + 本文 2 行）と区切り線 */
function TargetHeader({ target }: { target: NostrEvent }) {
  const profile = useProfile(target.pubkey);
  return (
    <>
      <div className={styles.target}>
        <ProfileAvatar pubkey={target.pubkey} size={32} />
        <div className={styles.targetText}>
          <p className={styles.targetName}>{displayName(profile, target.pubkey)}</p>
          <div className={styles.targetBody}>
            <NoteContent event={target} variant="quote" />
          </div>
        </div>
      </div>
      <hr className={styles.divider} />
    </>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title}>
      <h3 className={styles.section}>{title}</h3>
      <div className={styles.grid}>{children}</div>
    </section>
  );
}

function TextCell({ char, onClick }: { char: string; onClick(): void }) {
  return (
    <button type="button" className={styles.cell} aria-label={char} onClick={onClick}>
      {char}
    </button>
  );
}

/** カスタム絵文字の画像。プロキシが読めなければ元 URL で 1 度だけ取り直し、それも読めなければ :code: の文字 */
function ImageCell({ label, url, onClick }: { label: string; url: string; onClick(): void }) {
  const [src, setSrc] = useState<string | null>(() => proxied(url, 64, 80, true));

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
    <button type="button" className={styles.cell} aria-label={label} onClick={onClick}>
      {src !== null ? (
        <img
          src={src}
          alt=""
          loading="lazy"
          decoding="async"
          referrerPolicy="no-referrer"
          onError={onError}
        />
      ) : (
        <span className={styles.fallback}>{label}</span>
      )}
    </button>
  );
}
