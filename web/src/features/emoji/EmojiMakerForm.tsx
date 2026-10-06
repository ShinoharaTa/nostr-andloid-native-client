import { useEffect, useId, useMemo, useState } from "react";
import { useT } from "../../i18n";
import styles from "./EmojiMakerRoute.module.css";
import { emojiImageUrl, FONT_IDS, type MakerInput, normalizeColor, parseMakerInput } from "./emojiUrl";

/** 入力が止まってからプレビューを取りに行くまで（§7.3） */
export const PREVIEW_DEBOUNCE_MS = 400;

/** 上限（4 行・1 行 10 文字・生 200 文字）に掛かったときの code。テキスト欄の下に赤字で出す */
const LIMIT_ERRORS: ReadonlySet<string> = new Set(["too_many_lines", "line_too_long", "text_too_long"]);

/** プレビューの取得に失敗した理由（API の error code か network） */
type PreviewError = { code: string; char?: string };

/** 絵文字作成フォームの状態（useEmojiMaker）。/emoji と設定「カスタム絵文字」の「文字から作る」で使う */
export type EmojiMaker = {
  input: MakerInput;
  update(patch: Partial<MakerInput>): void;
  /** 入力を API と同じ規則で正規化した画像 URL（入力が通らなければ null） */
  url: string | null;
  /** 入力かプレビューの失敗（無ければ null。上限超えと空はここに入れない） */
  error: PreviewError | null;
  /** 上限に掛かっている */
  overLimit: boolean;
  /** 取れたプレビュー画像（objectURL） */
  previewSrc: string | null;
};

/**
 * 絵文字作成フォームの状態。入力を API と同じ規則で正規化した URL を出し、400 ms 止まったら同じ URL を
 * 取りに行ってプレビューする。
 */
export function useEmojiMaker(initial: MakerInput | (() => MakerInput)): EmojiMaker {
  const [input, setInput] = useState<MakerInput>(initial);
  const parsed = useMemo(() => parseMakerInput(input), [input]);
  const url = parsed.ok ? emojiImageUrl(window.location.origin, parsed.params) : null;
  const preview = usePreview(url);

  const overLimit = !parsed.ok && LIMIT_ERRORS.has(parsed.error);
  // 入力の段階で分かる失敗（上限・空は別に扱う）。それ以外はサーバーの応答で出す
  const localError: PreviewError | null =
    parsed.ok || overLimit || parsed.error === "empty_text" ? null : { code: parsed.error };

  return {
    input,
    update: (patch) => setInput((prev) => ({ ...prev, ...patch })),
    url,
    error: localError ?? preview.error,
    overLimit,
    previewSrc: preview.src,
  };
}

/**
 * 絵文字作成フォーム（テキスト・フォント・文字色・縁取り・明暗 2 枚のプレビュー・エラー。docs/emoji-maker.md §7）。
 * 状態は useEmojiMaker で親が持つ（URL の表示や追加は親の側）。
 */
export function EmojiMakerForm({ maker }: { maker: EmojiMaker }) {
  const t = useT();
  const { input, update, error, overLimit } = maker;
  const textId = useId();
  const limitId = useId();

  return (
    <div className={styles.form}>
      <div className={styles.field}>
        <label htmlFor={textId} className={styles.label}>
          {t("web_emoji_text_label")}
        </label>
        <textarea
          id={textId}
          className={styles.textarea}
          rows={4}
          spellCheck={false}
          value={input.text}
          aria-invalid={overLimit}
          aria-describedby={limitId}
          onChange={(e) => update({ text: e.target.value })}
        />
        <p id={limitId} className={overLimit ? styles.limitOver : styles.hint}>
          {t("web_emoji_limit")}
        </p>
      </div>

      <fieldset className={styles.fieldset}>
        <legend className={styles.label}>{t("web_emoji_font_label")}</legend>
        <div className={styles.radios}>
          {FONT_IDS.map((font) => (
            <label key={font} className={styles.radio}>
              <input
                type="radio"
                name="emoji-font"
                value={font}
                checked={input.font === font}
                onChange={() => update({ font })}
              />
              {t(`web_emoji_font_${font}`)}
            </label>
          ))}
        </div>
      </fieldset>

      <div className={styles.field}>
        <span className={styles.label}>{t("web_emoji_color_label")}</span>
        <ColorInput
          value={input.color}
          pickerLabel={t("web_emoji_color_label")}
          hexLabel={t("web_emoji_color_hex_label")}
          onChange={(color) => update({ color })}
        />
      </div>

      <div className={styles.field}>
        <label className={styles.check}>
          <input
            type="checkbox"
            checked={input.strokeOn}
            onChange={(e) => update({ strokeOn: e.target.checked })}
          />
          {t("web_emoji_stroke_enable")}
        </label>
        {input.strokeOn && (
          <ColorInput
            value={input.stroke}
            pickerLabel={t("web_emoji_stroke_color_label")}
            hexLabel={t("web_emoji_stroke_hex_label")}
            onChange={(stroke) => update({ stroke })}
          />
        )}
      </div>

      <section className={styles.field} aria-label={t("web_emoji_preview_label")}>
        <span className={styles.label}>{t("web_emoji_preview_label")}</span>
        <div className={styles.previews}>
          <PreviewBox src={error === null ? maker.previewSrc : null} dark={false} />
          <PreviewBox src={error === null ? maker.previewSrc : null} dark />
        </div>
        {error && (
          <p role="alert" className={styles.error}>
            {errorMessage(t, error)}
          </p>
        )}
      </section>
    </div>
  );
}

/** 色の入力（カラーピッカー + 16 進の欄。どちらを変えても揃える） */
function ColorInput({
  value,
  pickerLabel,
  hexLabel,
  onChange,
}: {
  value: string;
  pickerLabel: string;
  hexLabel: string;
  onChange(color: string): void;
}) {
  // 16 進の欄は打ちかけ（"ff0" → "ff00"）を保つため手元に持つ。読める値になったら親へ渡す
  const [draft, setDraft] = useState(value);
  const valid = normalizeColor(draft) !== null;

  return (
    <div className={styles.row}>
      <input
        className={styles.picker}
        type="color"
        aria-label={pickerLabel}
        // ピッカーは 6 桁しか持てないので、8 桁（アルファ付き）は色だけ渡す
        value={`#${value.slice(0, 6)}`}
        onChange={(e) => {
          const color = e.target.value.slice(1).toLowerCase();
          setDraft(color);
          onChange(color);
        }}
      />
      <input
        className={`${styles.input} ${styles.hex}`}
        type="text"
        aria-label={hexLabel}
        aria-invalid={!valid}
        autoCapitalize="off"
        autoCorrect="off"
        spellCheck={false}
        maxLength={9}
        value={draft}
        onChange={(e) => {
          setDraft(e.target.value);
          const color = normalizeColor(e.target.value);
          if (color !== null) onChange(color);
        }}
      />
    </div>
  );
}

function PreviewBox({ src, dark }: { src: string | null; dark: boolean }) {
  const t = useT();
  const label = dark ? t("web_emoji_preview_dark") : t("web_emoji_preview_light");
  return (
    <figure className={styles.preview}>
      <div className={`${styles.previewBox} ${dark ? styles.previewDark : styles.previewLight}`}>
        {src !== null && <img src={src} alt={label} width={128} height={128} />}
      </div>
      <figcaption className={styles.hint}>{label}</figcaption>
    </figure>
  );
}

/** 取りに行った結果（どの URL の結果か。src は取れた画像の objectURL） */
type PreviewResult = { url: string; src: string | null; error: PreviewError | null };

/**
 * 正規化した URL の画像を取りに行く（入力が止まって PREVIEW_DEBOUNCE_MS 後に 1 回）。
 * 取れたら objectURL にして返し、前の objectURL は捨てる。4xx は JSON の error、通信の失敗は network。
 * 次の結果が来るまでは前の画像を出したままにする（打つたびに消えないように）。エラーは今の URL の分だけ返す。
 */
function usePreview(url: string | null): { src: string | null; error: PreviewError | null } {
  const [result, setResult] = useState<PreviewResult | null>(null);

  useEffect(() => {
    if (url === null) {
      setResult(null);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      void (async () => {
        let next: PreviewResult;
        try {
          const response = await fetch(url, { signal: controller.signal });
          if (response.ok) {
            const blob = await response.blob();
            if (controller.signal.aborted) return;
            next = { url, src: URL.createObjectURL(blob), error: null };
          } else {
            const body: unknown = await response.json().catch(() => null);
            next = { url, src: null, error: previewError(body) };
          }
        } catch {
          next = { url, src: null, error: { code: "network" } };
        }
        if (!controller.signal.aborted) setResult(next);
      })();
    }, PREVIEW_DEBOUNCE_MS);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [url]);

  // 差し替えたら前の objectURL を捨てる（離れるときも）
  const src = result?.src ?? null;
  useEffect(
    () => () => {
      if (src !== null) URL.revokeObjectURL(src);
    },
    [src],
  );

  if (url === null) return { src: null, error: null };
  return { src, error: result?.url === url ? result.error : null };
}

/** API のエラー本文（{"error":"…","char":"…"}）を読む。読めなければ unknown */
function previewError(body: unknown): PreviewError {
  if (typeof body !== "object" || body === null) return { code: "unknown" };
  const { error, char } = body as { error?: unknown; char?: unknown };
  return {
    code: typeof error === "string" ? error : "unknown",
    ...(typeof char === "string" ? { char } : {}),
  };
}

function errorMessage(t: ReturnType<typeof useT>, error: PreviewError): string {
  switch (error.code) {
    case "too_many_lines":
    case "line_too_long":
    case "text_too_long":
      return t("web_emoji_limit");
    case "empty_text":
      return t("web_emoji_error_empty_text");
    case "invalid_text":
      return t("web_emoji_error_invalid_text");
    case "unsupported_char":
      return t("web_emoji_error_unsupported_char", error.char ?? "");
    case "invalid_color":
      return t("web_emoji_error_invalid_color");
    case "invalid_stroke":
      return t("web_emoji_error_invalid_stroke");
    case "font_unavailable":
      return t("web_emoji_error_font_unavailable");
    case "network":
      return t("web_emoji_error_network");
    default:
      return t("web_emoji_error_unknown");
  }
}
