import { npubEncode } from "nostr-tools/nip19";
import { useEffect, useMemo, useState } from "react";
import { LogoutButton } from "../../app/LogoutButton";
import { t, useT } from "../../i18n";
import { shortNpub } from "../../lib/npub";
import { displayName, useProfile } from "../../nostr/loaders";
import { nip46Relays } from "../../signer/nip46";
import { getPasskeyVault, isPasskeySupported } from "../../signer/passkeyVault";
import { type SessionMethod, useSession } from "../../signer/session";
import { AccountAvatar } from "../../ui/AccountAvatar";
import { ConfirmDialog } from "../../ui/ConfirmDialog";
import styles from "./SettingsSections.module.css";

const METHOD_LABEL: Record<SessionMethod, () => string> = {
  nip07: () => t("web_settings_account_method_nip07"),
  local: () => t("web_settings_account_method_local"),
  nip46: () => t("signer_nip46"),
};

/**
 * アカウント（① 現在のログインカード、② パスキー保護、③ 別のアカウントを使う、④ ログアウト）。
 * ネイティブ SettingsScreen.kt の SignerSettings と同じ構成・順序（#588）。nsec の表示は Web では出さない。
 */
export function AccountSection() {
  const me = useSession((s) => s.pubkey);
  const method = useSession((s) => s.method);
  // RequireSession の内側なので pubkey は必ずある
  if (!me) return null;
  return (
    <>
      <AccountCard me={me} method={method} />
      <NosskeyBlock />
      <ReloginBlock />
      <div className={styles.block}>
        <LogoutButton className={`${styles.ghost} ${styles.alignStart}`} />
      </div>
    </>
  );
}

/**
 * [#588] ① 現在のログインカード（ネイティブ SignerSettings 冒頭と同じ）。
 * アバター・名前・npub（先頭16 + … + 末尾6）・ログイン方法・有効バッジを 1 枚のカードにまとめる。
 */
function AccountCard({ me, method }: { me: string; method: SessionMethod | null }) {
  const t = useT();
  const profile = useProfile(me);
  const npub = useMemo(() => npubEncode(me), [me]);
  // [#899] リモート署名なら接続中の中継リレーを小さく出す（選び直しはログアウトして入り直す）
  const relays = method === "nip46" ? (nip46Relays() ?? []) : [];
  return (
    <div className={styles.card}>
      <div className={styles.cardHead}>
        <AccountAvatar size={40} />
        <span className={styles.names}>
          <span className={styles.name}>{displayName(profile, me)}</span>
          <span className={styles.shortNpub}>
            {npub.slice(0, 16)}…{npub.slice(-6)}
          </span>
        </span>
      </div>
      <div className={styles.cardDivider} />
      <div className={styles.cardMethod}>
        <span className={styles.cardMethodLabel}>{t("account_login_method_label")}</span>
        <span className={styles.cardMethodValue}>{method ? METHOD_LABEL[method]() : t("signer_none")}</span>
        <span className={styles.active}>{t("account_active")}</span>
      </div>
      {relays.length > 0 && (
        <div className={styles.cardRelay}>
          <span className={styles.cardMethodLabel}>{t("web_login_nip46_relay_title")}</span>
          <span>{relays.map((url) => url.replace(/\/$/, "")).join(" ")}</span>
        </div>
      )}
    </div>
  );
}

/**
 * [#588] ③ 別のアカウントを使う（ネイティブの account_switch_section / account_relogin_row）。
 * 誤タップでアカウントが切り替わる事故を防ぐため、警告ダイアログ（relogin_*）を経由してから
 * logout() する。ログイン方式を選び直す画面は Web では既に /login にあるので、そこは
 * RequireSession が status: "out" を見て自動で送る。
 */
function ReloginBlock() {
  const t = useT();
  const [confirming, setConfirming] = useState(false);
  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("account_switch_section")}</h3>
      <button
        type="button"
        className={`${styles.ghost} ${styles.alignStart}`}
        onClick={() => setConfirming(true)}
      >
        {t("account_relogin_row")}
      </button>
      {confirming && (
        <ConfirmDialog
          title={t("relogin_title")}
          text={t("relogin_text")}
          confirmLabel={t("relogin_confirm")}
          destructive
          onConfirm={() => {
            setConfirming(false);
            useSession.getState().logout();
          }}
          onDismiss={() => setConfirming(false)}
        />
      )}
    </div>
  );
}

/**
 * [#543] パスキー(WebAuthn PRF)で nsec を保護する（ネイティブ SettingsScreen.kt の NosskeyLogin）。
 *  - ローカル鍵のとき「パスキーで保護する」で登録（確認ダイアログを経由）。
 *  - 登録済み未解錠のとき「パスキーで解錠」。解錠済みは保護解除のみ。
 * PRF に対応していない・判定できない環境では項目自体を出さない。
 */
function NosskeyBlock() {
  const t = useT();
  const method = useSession((s) => s.method);
  const [supported, setSupported] = useState<boolean | null>(null);
  const [tick, setTick] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    let alive = true;
    void isPasskeySupported().then((ok) => {
      if (alive) setSupported(ok);
    });
    return () => {
      alive = false;
    };
  }, []);

  const vault = getPasskeyVault();
  // 開いた時点の保護状態を読み直す（restore() が済んでいれば結果は変わらないが、念のため）
  useEffect(() => {
    let alive = true;
    void vault.storedPubkey().then(() => {
      if (alive) setTick((t) => t + 1);
    });
    return () => {
      alive = false;
    };
  }, [vault]);

  // tick を読むことで enroll/unlock/unprotect の後に再評価する（isProtected/isUnlocked は毎回読み直す）
  void tick;
  const isProtected = vault.isProtected();
  const unlocked = isProtected && vault.isUnlocked();

  // 保護中でなく、対応の判定がまだ・非対応なら項目を出さない
  if (!isProtected && supported !== true) return null;

  async function enroll() {
    setBusy(true);
    setError(null);
    try {
      const pubkey = await vault.enroll();
      if (pubkey) setTick((t) => t + 1);
      else setError(t("nosskey_enroll_failed"));
    } catch {
      setError(t("nosskey_enroll_failed"));
    } finally {
      setBusy(false);
    }
  }

  async function unlock() {
    setBusy(true);
    setError(null);
    try {
      const pubkey = await vault.unlock();
      if (pubkey) setTick((t) => t + 1);
      else setError(t("nosskey_unlock_failed"));
    } catch {
      setError(t("nosskey_unlock_failed"));
    } finally {
      setBusy(false);
    }
  }

  async function unprotect() {
    setBusy(true);
    setError(null);
    try {
      const ok = await vault.unprotect();
      if (ok) setTick((t) => t + 1);
      else setError(t("nosskey_unlock_failed"));
    } catch {
      setError(t("nosskey_unlock_failed"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("nosskey_title")}</h3>
      <p className={styles.desc}>{t("nosskey_desc")}</p>
      {unlocked ? (
        <>
          <p className={styles.value}>{t("nosskey_protected_unlocked")}</p>
          <button
            type="button"
            className={`${styles.ghost} ${styles.alignStart}`}
            disabled={busy}
            onClick={() => void unprotect()}
          >
            {t("nosskey_unprotect_local")}
          </button>
        </>
      ) : isProtected ? (
        <>
          <p className={styles.value}>{t("nosskey_protected_locked")}</p>
          <button
            type="button"
            className={`${styles.primary} ${styles.alignStart}`}
            disabled={busy}
            onClick={() => void unlock()}
          >
            {busy ? t("nosskey_unlocking") : t("nosskey_unlock")}
          </button>
          <button
            type="button"
            className={`${styles.ghost} ${styles.alignStart}`}
            disabled={busy}
            onClick={() => void unprotect()}
          >
            {t("nosskey_unprotect")}
          </button>
        </>
      ) : method === "local" ? (
        <>
          <button
            type="button"
            className={`${styles.primary} ${styles.alignStart}`}
            disabled={busy}
            onClick={() => setConfirming(true)}
          >
            {busy ? t("nosskey_enrolling") : t("nosskey_enroll")}
          </button>
          {confirming && (
            <ConfirmDialog
              title={t("nosskey_title")}
              text={t("web_account_nosskey_confirm_text")}
              confirmLabel={t("nosskey_enroll")}
              onConfirm={() => {
                setConfirming(false);
                void enroll();
              }}
              onDismiss={() => setConfirming(false)}
            />
          )}
        </>
      ) : (
        <p className={styles.desc}>{t("nosskey_local_only")}</p>
      )}
      {error && <p className={styles.error}>{error}</p>}
    </div>
  );
}

/** 一覧の先頭のアカウント行（M0 の設定にあったもの。押すとアカウントを開く） */
export function AccountSummary({ onOpen }: { onOpen(): void }) {
  const me = useSession((s) => s.pubkey);
  const profile = useProfile(me ?? undefined);
  if (!me) return null;
  return (
    <button type="button" className={`${styles.account} ${styles.accountButton}`} onClick={onOpen}>
      <AccountAvatar size={40} />
      <span className={styles.names}>
        <span className={styles.name}>{displayName(profile, me)}</span>
        <span className={styles.shortNpub}>{shortNpub(me)}</span>
      </span>
    </button>
  );
}
