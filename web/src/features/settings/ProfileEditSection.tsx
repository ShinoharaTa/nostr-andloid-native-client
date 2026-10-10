import { use$ } from "applesauce-react/hooks/use-$";
import { useEffect, useId, useMemo, useRef, useState } from "react";
import { t, useT } from "../../i18n";
import { proxied } from "../../lib/imageProxy";
import { refetchOwnReplaceable } from "../../nostr/ownReplaceable";
import { eventStore } from "../../nostr/store";
import { currentSigner, useSession } from "../../signer/session";
import { showToast } from "../../ui/toast";
import { uploadServers, useMediaServer } from "../compose/mediaServer";
import { uploadMedia } from "../compose/nip96";
import { processImage } from "../compose/processMedia";
import {
  changedFields,
  ProfileEditError,
  type ProfileFields,
  profileFieldsOf,
  publishProfile,
} from "../profile/profileEdit";
import styles from "./ProfileEditSection.module.css";
import sectionStyles from "./SettingsSections.module.css";

/** 保存の失敗の文言 */
function failureMessage(e: unknown): string {
  if (e instanceof ProfileEditError && e.reason === "unreachable") {
    return t("web_profile_edit_no_base");
  }
  if (e instanceof ProfileEditError && e.reason === "stale") {
    return t("web_profile_edit_stale");
  }
  return t("profile_save_failed");
}

type ImageField = "picture" | "banner";

/** 編集中の内容。basedOnId / initial = 編集を始めた時点の自分の kind:0 の id（無ければ null）とその値 */
type Draft = { basedOnId: string | null; initial: ProfileFields; fields: ProfileFields };

/**
 * プロフィール編集（ネイティブ AccountSettings のプロフィール欄）。開いたら自分の kind:0 を取り直し、
 * 終わるまで入力欄を無効にする。保存は変えた項目だけを、直前に取り直した最新版へ当てて kind:0 を発行する
 * （profileEdit.ts の publishProfile。取れない・食い違うときは発行しない）。
 */
export function ProfileEditSection() {
  const me = useSession((s) => s.pubkey);
  // RequireSession の内側なので pubkey は必ずある
  if (!me) return null;
  return <ProfileEditForm key={me} me={me} />;
}

function ProfileEditForm({ me }: { me: string }) {
  const t = useT();
  const latest = use$(() => eventStore.replaceable(0, me), [me]) ?? null;
  const current = useMemo(() => profileFieldsOf(latest), [latest]);
  // 開いた直後の取り直しが終わるまで（応答が無くても終われば編集できる。保存の直前にもう一度確かめる）
  const [loaded, setLoaded] = useState(false);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [uploading, setUploading] = useState<Record<ImageField, boolean>>({ picture: false, banner: false });
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const mediaServer = useMediaServer((s) => s.server);
  /** 画面を閉じたらアップロードを打ち切る */
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    let alive = true;
    refetchOwnReplaceable(me, 0)
      .catch(() => null)
      .finally(() => {
        if (alive) setLoaded(true);
      });
    return () => {
      alive = false;
    };
  }, [me]);

  useEffect(() => {
    const ac = new AbortController();
    controller.current = ac;
    return () => ac.abort();
  }, []);

  const fields = draft?.fields ?? current;

  /** 編集を始める（始めた時点の版を覚える）。patch があればその項目を変える */
  function edit(patch: Partial<ProfileFields>) {
    setSaved(false);
    setDraft((prev) => {
      const base = prev ?? { basedOnId: latest?.id ?? null, initial: current, fields: current };
      return { ...base, fields: { ...base.fields, ...patch } };
    });
  }

  async function upload(field: ImageField, file: File) {
    const signal = controller.current?.signal;
    const signer = currentSigner();
    if (!signer || !file.type.startsWith("image/")) {
      showToast(t("channel_icon_upload_failed"));
      return;
    }
    // 選んだ時点の版で編集を始める（アップロード中に届いた版で上書きしない）
    edit({});
    setUploading((u) => ({ ...u, [field]: true }));
    try {
      const processed = await processImage(file);
      const result = await uploadMedia(processed, uploadServers(mediaServer), signer, signal);
      if (signal?.aborted) return;
      if (result) edit({ [field]: result.url });
      else showToast(t("channel_icon_upload_failed"));
    } catch {
      if (!signal?.aborted) showToast(t("channel_icon_upload_failed"));
    } finally {
      if (!signal?.aborted) setUploading((u) => ({ ...u, [field]: false }));
    }
  }

  async function save() {
    const changed = draft ? changedFields(draft.initial, draft.fields) : {};
    // 変えた項目が無ければ発行しない（ネイティブと同じく保存済みとして扱う）
    if (!draft || Object.keys(changed).length === 0) {
      setDraft(null);
      setSaved(true);
      return;
    }
    setSaving(true);
    try {
      await publishProfile(me, changed, draft.basedOnId);
      setDraft(null);
      setSaved(true);
    } catch (e) {
      showToast(failureMessage(e));
    } finally {
      setSaving(false);
    }
  }

  const disabled = !loaded || saving;
  const canSave = loaded && !saving && !uploading.picture && !uploading.banner;

  return (
    <div className={sectionStyles.block}>
      <h3 className={sectionStyles.caption}>{t("tile_profile")}</h3>
      <p className={sectionStyles.desc}>{t("profile_publish_note")}</p>
      {!loaded && (
        <p className={sectionStyles.desc} role="status">
          {t("loading")}
        </p>
      )}
      <TextField
        label={t("field_display_name")}
        value={fields.name}
        disabled={disabled}
        onChange={(v) => edit({ name: v })}
      />
      <TextField
        label={t("field_about")}
        value={fields.about}
        disabled={disabled}
        multiline
        onChange={(v) => edit({ about: v })}
      />
      <ImageUrlField
        label={t("field_icon")}
        value={fields.picture}
        disabled={disabled}
        uploading={uploading.picture}
        banner={false}
        onChange={(v) => edit({ picture: v })}
        onPick={(file) => void upload("picture", file)}
      />
      <ImageUrlField
        label={t("field_banner")}
        value={fields.banner}
        disabled={disabled}
        uploading={uploading.banner}
        banner
        onChange={(v) => edit({ banner: v })}
        onPick={(file) => void upload("banner", file)}
      />
      <TextField
        label={t("field_lud16")}
        value={fields.lud16}
        disabled={disabled}
        inputMode="email"
        onChange={(v) => edit({ lud16: v })}
      />
      <TextField
        label="NIP-05"
        value={fields.nip05}
        disabled={disabled}
        inputMode="email"
        onChange={(v) => edit({ nip05: v })}
      />
      <TextField
        label={t("field_website")}
        value={fields.website}
        disabled={disabled}
        inputMode="url"
        onChange={(v) => edit({ website: v })}
      />
      <button
        type="button"
        className={`${sectionStyles.primary} ${sectionStyles.alignStart}`}
        disabled={!canSave}
        onClick={() => void save()}
      >
        {saving ? t("common_saving") : saved ? t("saved_check") : t("common_save")}
      </button>
    </div>
  );
}

function TextField({
  label,
  value,
  disabled,
  multiline = false,
  inputMode,
  onChange,
}: {
  label: string;
  value: string;
  disabled: boolean;
  multiline?: boolean;
  inputMode?: "email" | "url";
  onChange(value: string): void;
}) {
  const id = useId();
  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      {multiline ? (
        <textarea
          id={id}
          className={`${sectionStyles.input} ${styles.textarea}`}
          rows={4}
          value={value}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
        />
      ) : (
        <input
          id={id}
          className={sectionStyles.input}
          type="text"
          inputMode={inputMode}
          autoCapitalize={inputMode ? "off" : undefined}
          autoCorrect={inputMode ? "off" : undefined}
          spellCheck={inputMode ? false : undefined}
          value={value}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
        />
      )}
    </div>
  );
}

/**
 * 画像の URL 欄 +「画像を選ぶ」（選んだ画像は投稿と同じ既定で圧縮して NIP-96 でアップロードし、URL を入れる）。
 * URL があれば下にプレビュー（読み込み中/失敗/成功。banner=true は横長、false は正方形。ネイティブ
 * ProfileImageField と同じ。S16）。
 */
function ImageUrlField({
  label,
  value,
  disabled,
  uploading,
  banner,
  onChange,
  onPick,
}: {
  label: string;
  value: string;
  disabled: boolean;
  uploading: boolean;
  banner: boolean;
  onChange(value: string): void;
  onPick(file: File): void;
}) {
  const id = useId();
  const fileInput = useRef<HTMLInputElement>(null);
  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <div className={sectionStyles.row}>
        <input
          id={id}
          className={sectionStyles.input}
          type="text"
          inputMode="url"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          placeholder="https://…"
          value={value}
          disabled={disabled || uploading}
          onChange={(e) => onChange(e.target.value)}
        />
        <button
          type="button"
          className={sectionStyles.ghost}
          aria-label={t("web_settings_profile_pick_label", label)}
          disabled={disabled || uploading}
          onClick={() => fileInput.current?.click()}
        >
          {uploading ? t("web_uploading") : t("web_settings_profile_pick_image")}
        </button>
        <input
          ref={fileInput}
          type="file"
          accept="image/*"
          hidden
          aria-label={t("web_settings_profile_file_label", label)}
          onChange={(e) => {
            const input = e.currentTarget;
            const file = input.files?.[0];
            // 同じファイルをもう一度選べるように空にする
            input.value = "";
            if (file) onPick(file);
          }}
        />
      </div>
      {value.trim() !== "" && <ImagePreview key={value} url={value} banner={banner} label={label} />}
    </div>
  );
}

/** URL 欄のプレビュー（読み込み中/失敗/成功。S16）。呼び出し側で key={url} を付け、URL が変わったら作り直す */
function ImagePreview({ url, banner, label }: { url: string; banner: boolean; label: string }) {
  const t = useT();
  const [state, setState] = useState<"loading" | "loaded" | "error">("loading");
  const src = proxied(url, banner ? 800 : 256, 80);
  return (
    <div
      className={`${styles.preview} ${banner ? styles.previewBanner : styles.previewSquare}`}
      data-state={state}
    >
      <img
        className={styles.previewImg}
        src={src}
        alt={label}
        onLoad={() => setState("loaded")}
        onError={() => setState("error")}
      />
      {state === "loading" && <span className={styles.previewHint}>{t("loading")}</span>}
      {state === "error" && <span className={styles.previewError}>{t("image_load_failed")}</span>}
    </div>
  );
}
