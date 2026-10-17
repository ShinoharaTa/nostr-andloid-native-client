import { type FormEvent, useEffect, useId, useState } from "react";
import { useT } from "../../i18n";
import {
  DEFAULT_LOW_DIM,
  DEFAULT_MID_DIM,
  DEFAULT_QUALITY,
  DIM_MAX,
  DIM_MIN,
  QUALITY_MAX,
  QUALITY_MIN,
  resetImageCompression,
  setImageCompression,
  useImageCompression,
} from "../compose/imageCompression";
import {
  DEFAULT_MEDIA_SERVERS,
  parseServerInput,
  sameServer,
  serverChoices,
  serverKey,
  serverName,
  setMediaServer,
  UPLOAD_PROTOCOLS,
  type UploadProtocol,
  uploadServers,
  useMediaServer,
} from "../compose/mediaServer";
import { ProtocolBadge, useProtocolLabel } from "../compose/ProtocolBadge";
import styles from "./SettingsSections.module.css";

/**
 * メディアサーバー（ネイティブ MediaSettings の「アップロード先サーバー」+ 「アップロード時の圧縮」）。
 * 候補から選ぶか URL を入力して 1 つ選ぶ。選ばない間は既定の一覧を順に試す（ネイティブの初期状態と同じ）。
 * [#873] 方式（NIP-96 / Blossom）をバッジで出し、候補は方式の見出しで分ける。URL を入れるときは方式を選ぶ。
 */
export function MediaSection() {
  const t = useT();
  const protocolLabel = useProtocolLabel();
  const server = useMediaServer((s) => s.server);
  const current = uploadServers(server)[0];
  const choices = serverChoices(server);
  const inputId = useId();
  const [value, setValue] = useState("");
  const [protocol, setProtocol] = useState<UploadProtocol>("nip96");
  const [error, setError] = useState<string | null>(null);

  function submit(e: FormEvent) {
    e.preventDefault();
    const url = parseServerInput(value);
    if (!url) {
      setError(t("web_settings_media_url_invalid"));
      return;
    }
    setMediaServer({ url, protocol });
    setValue("");
    setError(null);
  }

  return (
    <>
      <div className={styles.block}>
        <h3 className={styles.caption}>{t("media_servers_title")}</h3>
        <p className={styles.desc}>
          {t("media_servers_desc")}
          {server === null &&
            t(
              "web_settings_media_default_order",
              DEFAULT_MEDIA_SERVERS.map((s) => serverName(s.url)).join(" → "),
            )}
        </p>
        {UPLOAD_PROTOCOLS.map((p) => (
          <div key={p} className={styles.presetGroup}>
            <p className={styles.presetCategory}>{protocolLabel(p)}</p>
            <div className={styles.choices}>
              {choices
                .filter((s) => s.protocol === p)
                .map((s) => (
                  <button
                    key={serverKey(s)}
                    type="button"
                    className={styles.choice}
                    aria-pressed={sameServer(s, current)}
                    onClick={() => setMediaServer(s)}
                  >
                    {serverName(s.url)} <ProtocolBadge protocol={s.protocol} />
                  </button>
                ))}
            </div>
          </div>
        ))}
        <form className={styles.row} onSubmit={submit}>
          <fieldset className={styles.protocolRow}>
            <legend className={styles.protocolLabel}>{t("media_add_protocol")}</legend>
            {UPLOAD_PROTOCOLS.map((p) => (
              <button
                key={p}
                type="button"
                className={styles.choice}
                aria-pressed={protocol === p}
                onClick={() => setProtocol(p)}
              >
                {protocolLabel(p)}
              </button>
            ))}
          </fieldset>
          <label htmlFor={inputId} className="srOnly">
            {t("web_settings_media_url_label")}
          </label>
          <input
            id={inputId}
            className={styles.input}
            type="text"
            inputMode="url"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
            placeholder="https://…"
            value={value}
            aria-invalid={error !== null}
            onChange={(e) => {
              setValue(e.target.value);
              setError(null);
            }}
          />
          <button type="submit" className={styles.ghost} disabled={value.trim() === ""}>
            {t("pick")}
          </button>
          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}
        </form>
        {server !== null && (
          <button
            type="button"
            className={`${styles.ghost} ${styles.alignStart}`}
            onClick={() => setMediaServer(null)}
          >
            {t("img_reset_defaults")}
          </button>
        )}
      </div>
      <ImageCompressionBlock />
    </>
  );
}

/**
 * [#533] 画像アップロード時の圧縮（ネイティブ MediaCompressionSheet / ImageCompressionBlock）。
 * 「低 / 中」の長辺（px）と再エンコード品質（%）。「高」は縮小しないが、EXIF を消すため同じ品質で
 * 再エンコードする（プラポリ 4.4。ネイティブは「高」= 無加工だが Web はこちらを優先）。
 */
function ImageCompressionBlock() {
  const t = useT();
  const prefs = useImageCompression((s) => s.prefs);
  const [low, setLow] = useState(String(prefs.lowMaxDim));
  const [mid, setMid] = useState(String(prefs.midMaxDim));
  const [quality, setQuality] = useState(String(prefs.quality));

  // 他所（既定に戻す等）で値が変わったら入力欄も合わせる。保存時に丸めた値もここで反映する
  useEffect(() => {
    setLow(String(prefs.lowMaxDim));
    setMid(String(prefs.midMaxDim));
    setQuality(String(prefs.quality));
  }, [prefs]);

  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("img_compress_title")}</h3>
      <p className={styles.desc}>
        {t(
          "web_settings_media_compress_desc",
          DEFAULT_LOW_DIM,
          DEFAULT_MID_DIM,
          DEFAULT_QUALITY,
          DIM_MIN,
          DIM_MAX,
          QUALITY_MIN,
          QUALITY_MAX,
        )}
      </p>
      <div className={styles.row}>
        <label className={styles.field}>
          {t("img_low_dim_label")}
          <input
            className={styles.input}
            type="number"
            inputMode="numeric"
            min={DIM_MIN}
            max={DIM_MAX}
            value={low}
            onChange={(e) => setLow(e.target.value)}
            onBlur={() => setImageCompression({ lowMaxDim: Number(low) || DEFAULT_LOW_DIM })}
          />
        </label>
        <label className={styles.field}>
          {t("img_mid_dim_label")}
          <input
            className={styles.input}
            type="number"
            inputMode="numeric"
            min={DIM_MIN}
            max={DIM_MAX}
            value={mid}
            onChange={(e) => setMid(e.target.value)}
            onBlur={() => setImageCompression({ midMaxDim: Number(mid) || DEFAULT_MID_DIM })}
          />
        </label>
        <label className={styles.field}>
          {t("img_quality_label")}
          <input
            className={styles.input}
            type="number"
            inputMode="numeric"
            min={QUALITY_MIN}
            max={QUALITY_MAX}
            value={quality}
            onChange={(e) => setQuality(e.target.value)}
            onBlur={() => setImageCompression({ quality: Number(quality) || DEFAULT_QUALITY })}
          />
        </label>
      </div>
      <button
        type="button"
        className={`${styles.ghost} ${styles.alignStart}`}
        aria-label={t("web_settings_media_compress_reset_label")}
        onClick={() => resetImageCompression()}
      >
        {t("img_reset_defaults")}
      </button>
    </div>
  );
}
