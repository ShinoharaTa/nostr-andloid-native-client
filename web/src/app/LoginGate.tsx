import { type FormEvent, useEffect, useId, useRef, useState } from "react";
import { Navigate, useSearchParams } from "react-router";
import { t, useT } from "../i18n";
import { waitForNostr } from "../signer/nip07";
import {
  nostrConnectRelayChoices,
  parseNostrConnectRelay,
  setNostrConnectRelay,
  useNostrConnectRelay,
} from "../signer/nip46RelayPref";
import { nsecHead } from "../signer/nsec";
import { LoginError, type NewKey, type NostrConnectLogin, useSession } from "../signer/session";
import { ConfirmDialog } from "../ui/ConfirmDialog";
import { QrCode } from "../ui/QrCode";
import { Loading } from "./Loading";
import styles from "./LoginGate.module.css";
import { UpdateToast } from "./UpdateToast";

type ExtensionState = "checking" | "found" | "missing";
/** 処理中のログイン方法（どれかの処理中は他も押せない） */
type Busy = "nip07" | "nip46" | "nsec" | "new" | null;

const EXTERNAL_LINK = { target: "_blank", rel: "noopener noreferrer" } as const;

/**
 * 未ログイン時のゲート（ネイティブの LoginGate に対応）。鍵は勝手に生成せず、ログイン方法を選ばせる。
 * 並びは NIP-07 → リモート署名（NIP-46、折りたたみ）→ 秘密鍵（nsec、折りたたみ）→ 新規生成。
 * ログイン済みなら ?next=（無ければ /）へ戻す。
 */
export function LoginGate() {
  const t = useT();
  const status = useSession((s) => s.status);
  const login = useSession((s) => s.login);
  const generateNewKey = useSession((s) => s.generateNewKey);
  const [params] = useSearchParams();
  const [extension, setExtension] = useState<ExtensionState>("checking");
  const [busy, setBusy] = useState<Busy>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmNewKey, setConfirmNewKey] = useState(false);
  const [newKeyError, setNewKeyError] = useState<string | null>(null);
  // 生成した鍵の控え（表示は 1 回だけ。ログインを確定したら捨てる）
  const [newKey, setNewKey] = useState<NewKey | null>(null);

  useEffect(() => {
    let active = true;
    void waitForNostr().then((nostr) => {
      if (active) setExtension(nostr ? "found" : "missing");
    });
    return () => {
      active = false;
    };
  }, []);

  if (status === "loading") return <Loading />;
  if (status === "in") return <Navigate to={safeNext(params.get("next"))} replace />;
  if (newKey) return <NewKeyBackup newKey={newKey} onDone={() => setNewKey(null)} />;

  async function onLogin() {
    setBusy("nip07");
    setError(null);
    try {
      await login();
    } catch (e) {
      if (e instanceof LoginError && e.reason === "missing") setExtension("missing");
      setError(errorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  async function onGenerate() {
    setBusy("new");
    setNewKeyError(null);
    try {
      setNewKey(await generateNewKey());
    } catch {
      setNewKeyError(t("web_login_unavailable"));
    } finally {
      setBusy(null);
    }
  }

  return (
    <main className={styles.gate}>
      {/* AppShell を通らないので、SW の更新通知はここでも出す（#595） */}
      <UpdateToast />
      {/* ネイティブ AppMark 56dp と同じ置き場所（L5） */}
      <img className={styles.logo} src="/icons/icon-192.png" alt="" width={56} height={56} />
      <h1 className={styles.title}>{t("login_welcome")}</h1>
      <p className={styles.lead}>{t("web_login_lead")}</p>

      <button type="button" className={styles.primary} onClick={onLogin} disabled={busy !== null}>
        {busy === "nip07" ? t("web_login_nip07_waiting") : t("web_login_nip07")}
      </button>
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}

      {extension === "missing" && (
        <section className={styles.help} aria-label={t("web_login_help_label")}>
          <p className={styles.helpTitle}>{t("web_login_help_title")}</p>
          <p>{t("web_login_help_install")}</p>
          <ul className={styles.helpList}>
            <li>
              {t("web_login_help_pc")}{" "}
              <a
                href="https://chromewebstore.google.com/detail/nos2x/kpgefcfmnafjgpblomihpgmejjdanjjp"
                {...EXTERNAL_LINK}
              >
                nos2x
              </a>{" "}
              /{" "}
              <a href="https://getalby.com/" {...EXTERNAL_LINK}>
                Alby
              </a>
            </li>
            <li>
              {t("web_login_help_ios")}{" "}
              <a href="https://apps.apple.com/app/nostash/id6744309333" {...EXTERNAL_LINK}>
                Nostash
              </a>
            </li>
          </ul>
        </section>
      )}

      <details className={styles.more}>
        <summary>{t("nip46_title")}</summary>
        <div className={styles.moreBody}>
          <Nip46LoginForm busy={busy !== null} onBusy={(b) => setBusy(b ? "nip46" : null)} />
        </div>
      </details>

      <details className={styles.more}>
        <summary>{t("nsec_login_title")}</summary>
        <div className={styles.moreBody}>
          <NsecLoginForm busy={busy !== null} onBusy={(b) => setBusy(b ? "nsec" : null)} />
        </div>
      </details>

      <section className={styles.newKey} aria-labelledby="newkey-title">
        <p id="newkey-title" className={styles.newKeyTitle}>
          {t("web_login_no_account")}
        </p>
        <button
          type="button"
          className={styles.ghost}
          disabled={busy !== null}
          onClick={() => setConfirmNewKey(true)}
        >
          {t("nsec_generate")}
        </button>
        {newKeyError && (
          <p role="alert" className={styles.error}>
            {newKeyError}
          </p>
        )}
      </section>

      {confirmNewKey && (
        <ConfirmDialog
          title={t("keyswitch_generate_title")}
          text={t("web_login_new_key_confirm_text")}
          confirmLabel={t("web_login_new_key_confirm_label")}
          destructive
          onConfirm={() => {
            setConfirmNewKey(false);
            void onGenerate();
          }}
          onDismiss={() => setConfirmNewKey(false)}
        />
      )}
    </main>
  );
}

/**
 * リモート署名（NIP-46）で接続する（ネイティブの Nip46Login）。先に nostrconnect:// の QR・リンク、その下に bunker:// の貼り付け。
 * bunker:// は secret を含むので、NsecLoginForm と同じく value は React で持たない（DOM 属性に出さない）。
 * nostrconnect:// の URI も secret を含むので、文字列は画面に出さない（QR・リンク・コピーだけ）。
 */
function Nip46LoginForm({ busy, onBusy }: { busy: boolean; onBusy(busy: boolean): void }) {
  const t = useT();
  const loginWithBunker = useSession((s) => s.loginWithBunker);
  const startNostrConnectLogin = useSession((s) => s.startNostrConnectLogin);
  const input = useRef<HTMLInputElement>(null);
  const controller = useRef<AbortController | null>(null);
  const [ready, setReady] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // nostrconnect:// の承認待ち（QR を出している間だけ）
  const pending = useRef<NostrConnectLogin | null>(null);
  const [connectUri, setConnectUri] = useState<string | null>(null);
  const [copied, setCopied] = useState<"ok" | "failed" | null>(null);
  const [connectError, setConnectError] = useState<string | null>(null);
  const canPaste = typeof navigator.clipboard?.readText === "function";

  // 画面を離れたら接続の待ちもやめる
  useEffect(
    () => () => {
      controller.current?.abort();
      pending.current?.cancel();
    },
    [],
  );

  async function onShowQr() {
    setConnectError(null);
    setCopied(null);
    let login: NostrConnectLogin;
    try {
      login = startNostrConnectLogin();
    } catch (err) {
      setConnectError(nostrConnectErrorMessage(err));
      setConnectUri(null);
      onBusy(false);
      return;
    }
    pending.current = login;
    onBusy(true);
    setConnectUri(login.uri);
    try {
      await login.done;
    } catch (err) {
      if (pending.current === login) setConnectError(nostrConnectErrorMessage(err));
    } finally {
      // 中継リレーを変えて作り直した後は、新しい待ちの表示を残す
      if (pending.current === login) {
        pending.current = null;
        setConnectUri(null);
        onBusy(false);
      }
    }
  }

  /** 中継リレーを変える。承認待ちならやめて、そのリレーで URI を作り直す。wss:// でなければ false */
  function onRelay(input: string): boolean {
    const relay = parseNostrConnectRelay(input);
    if (relay === null) return false;
    if (relay === useNostrConnectRelay.getState().relay) return true;
    setNostrConnectRelay(relay);
    const old = pending.current;
    if (old) {
      pending.current = null;
      old.cancel();
      void onShowQr();
    }
    return true;
  }

  async function onCopy() {
    if (!connectUri) return;
    try {
      await navigator.clipboard.writeText(connectUri);
      setCopied("ok");
    } catch {
      setCopied("failed");
    }
  }

  function onInput(value: string) {
    setReady(value.trim().startsWith("bunker://"));
    setError(null);
  }

  async function onPaste() {
    try {
      const text = (await navigator.clipboard.readText()).trim();
      if (text && input.current) {
        input.current.value = text;
        onInput(text);
      }
    } catch {
      // 読めなければ何もしない（権限の拒否など）
    }
  }

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const el = input.current;
    if (!el) return;
    const abort = new AbortController();
    controller.current = abort;
    onBusy(true);
    setConnecting(true);
    setError(null);
    try {
      await loginWithBunker(el.value, abort.signal);
      el.value = "";
      setReady(false);
    } catch (err) {
      setError(nip46ErrorMessage(err));
    } finally {
      if (controller.current === abort) controller.current = null;
      setConnecting(false);
      onBusy(false);
    }
  }

  return (
    <>
      <p>{t("web_login_nip46_desc")}</p>
      {connectUri ? (
        <div className={styles.connect}>
          <QrCode value={connectUri} label={t("web_login_nip46_qr_label")} />
          <p>{t("web_login_nip46_qr_hint")}</p>
          <div className={styles.actions}>
            <a href={connectUri} className={styles.openLink}>
              {t("web_login_nip46_open")}
            </a>
            <button type="button" className={styles.ghost} onClick={onCopy}>
              {t("common_copy")}
            </button>
          </div>
          {copied && (
            <p role="status" className={styles.note}>
              {copied === "ok" ? t("copied") : t("web_copy_failed")}
            </p>
          )}
          <div className={styles.actions}>
            <p role="status" className={styles.note}>
              {t("nip46_waiting")}
            </p>
            <button type="button" className={styles.ghost} onClick={() => pending.current?.cancel()}>
              {t("web_login_cancel")}
            </button>
          </div>
        </div>
      ) : (
        <button type="button" className={styles.submit} onClick={onShowQr} disabled={busy}>
          {t("web_login_nip46_show_qr")}
        </button>
      )}
      {connectError && (
        <p role="alert" className={styles.error}>
          {connectError}
        </p>
      )}
      <NostrConnectRelayPicker disabled={busy && connectUri === null} onRelay={onRelay} />
      <form className={styles.moreBody} onSubmit={onSubmit}>
        <label htmlFor="bunker-input">{t("web_login_nip46_bunker")}</label>
        <div className={styles.field}>
          <input
            ref={input}
            id="bunker-input"
            name="bunker"
            type="text"
            autoComplete="off"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
            placeholder="bunker://…"
            onChange={(e) => onInput(e.target.value)}
            disabled={busy}
          />
          {canPaste && (
            <button type="button" className={styles.fieldAction} onClick={onPaste} disabled={busy}>
              {t("theme_code_paste")}
            </button>
          )}
        </div>
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        <div className={styles.actions}>
          <button type="submit" className={styles.submit} disabled={busy || !ready} aria-busy={connecting}>
            {connecting ? t("web_login_nip46_connecting") : t("nwc_connect")}
          </button>
          {connecting && (
            <button type="button" className={styles.ghost} onClick={() => controller.current?.abort()}>
              {t("web_login_cancel")}
            </button>
          )}
        </div>
      </form>
    </>
  );
}

/**
 * nostrconnect:// の中継リレー（#899）。候補のラジオと手入力（設定のリレー追加と同じ見た目）。
 * 選ぶと設定値に保存する（onRelay が保存と URI の作り直しをする）
 */
function NostrConnectRelayPicker({
  disabled,
  onRelay,
}: {
  disabled: boolean;
  onRelay(input: string): boolean;
}) {
  const t = useT();
  const relay = useNostrConnectRelay((s) => s.relay);
  const inputId = useId();
  const [value, setValue] = useState("");
  const [error, setError] = useState<string | null>(null);

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!onRelay(value)) {
      setError(
        isWsInput(value) ? t("web_settings_relay_ws_unsupported") : t("web_settings_relay_url_invalid"),
      );
      return;
    }
    setValue("");
    setError(null);
  }

  return (
    <fieldset className={styles.relay} disabled={disabled}>
      <legend className={styles.relayTitle}>{t("web_login_nip46_relay_title")}</legend>
      <p className={styles.note}>{t("web_login_nip46_relay_desc")}</p>
      {nostrConnectRelayChoices(relay).map((url) => (
        <label key={url} className={styles.check}>
          <input
            type="radio"
            name="nostrconnect-relay"
            value={url}
            checked={url === relay}
            onChange={() => onRelay(url)}
          />
          {url.replace(/^wss:\/\//, "")}
        </label>
      ))}
      <form className={styles.relayAdd} onSubmit={submit}>
        <label htmlFor={inputId} className="srOnly">
          {t("web_settings_relays_url_label")}
        </label>
        <input
          id={inputId}
          className={styles.relayInput}
          type="text"
          inputMode="url"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          placeholder="wss://…"
          value={value}
          aria-invalid={error !== null}
          onChange={(e) => {
            setValue(e.target.value);
            setError(null);
          }}
        />
        <button type="submit" className={styles.ghost} disabled={value.trim() === ""}>
          {t("common_add")}
        </button>
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
      </form>
    </fieldset>
  );
}

// ws:// のリレーは Web では使えないので専用の文言を出す（設定のリレー追加と同じ）
function isWsInput(input: string): boolean {
  return input.trim().toLowerCase().startsWith("ws://");
}

/**
 * 秘密鍵（nsec）の取り込み（ネイティブの LocalSignerLogin）。入力はマスクし、パスワードマネージャーの自動入力に載せる。
 * 入力値を DOM 属性に出さないよう value は React で持たない（制御すると value 属性に同期されるため）。
 */
function NsecLoginForm({ busy, onBusy }: { busy: boolean; onBusy(busy: boolean): void }) {
  const t = useT();
  const loginWithNsec = useSession((s) => s.loginWithNsec);
  const input = useRef<HTMLInputElement>(null);
  const [filled, setFilled] = useState(false);
  const [reveal, setReveal] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const canPaste = typeof navigator.clipboard?.readText === "function";

  function onInput(value: string) {
    setFilled(value.trim() !== "");
    setError(null);
  }

  async function onPaste() {
    try {
      const text = (await navigator.clipboard.readText()).trim();
      if (text && input.current) {
        input.current.value = text;
        onInput(text);
      }
    } catch {
      // 読めなければ何もしない（権限の拒否など）
    }
  }

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const el = input.current;
    if (!el) return;
    const value = el.value;
    onBusy(true);
    setError(null);
    try {
      await loginWithNsec(value);
      el.value = "";
      setFilled(false);
    } catch (err) {
      setError(nsecErrorMessage(err, value));
    } finally {
      onBusy(false);
    }
  }

  return (
    <>
      <div className={styles.warn}>
        <p>{t("web_login_nsec_warn1")}</p>
        <p>{t("web_login_nsec_warn2")}</p>
      </div>
      <form className={styles.moreBody} onSubmit={onSubmit}>
        <label htmlFor="nsec-input" className="srOnly">
          {t("web_login_nsec_label")}
        </label>
        <div className={styles.field}>
          <input
            ref={input}
            id="nsec-input"
            name="nsec"
            type={reveal ? "text" : "password"}
            autoComplete="current-password"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
            placeholder={t("nsec_placeholder")}
            onChange={(e) => onInput(e.target.value)}
            disabled={busy}
          />
          {canPaste && (
            <button type="button" className={styles.fieldAction} onClick={onPaste} disabled={busy}>
              {t("theme_code_paste")}
            </button>
          )}
          <button
            type="button"
            className={styles.fieldAction}
            aria-pressed={reveal}
            onClick={() => setReveal((r) => !r)}
          >
            {reveal ? t("common_hide") : t("web_login_nsec_show")}
          </button>
        </div>
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        <button type="submit" className={styles.submit} disabled={busy || !filled} aria-busy={busy}>
          {t("nsec_import")}
        </button>
      </form>
    </>
  );
}

/**
 * 生成した鍵の控え。生成の直後に 1 回だけ nsec を出し、「控えた」にチェックを入れるまで先へ進ませない。
 * 閉じる・再読み込みすると二度と表示しない（保管した鍵の表示・書き出しは作らない）。
 */
function NewKeyBackup({ newKey, onDone }: { newKey: NewKey; onDone(): void }) {
  const t = useT();
  const loginWithNewKey = useSession((s) => s.loginWithNewKey);
  const [saved, setSaved] = useState(false);
  const [copied, setCopied] = useState<"ok" | "failed" | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const canCopy = typeof navigator.clipboard?.writeText === "function";

  async function onCopy() {
    try {
      await navigator.clipboard.writeText(newKey.nsec);
      setCopied("ok");
    } catch {
      setCopied("failed");
    }
  }

  async function onContinue() {
    setBusy(true);
    setError(null);
    try {
      await loginWithNewKey(newKey.pubkey);
      onDone();
    } catch {
      setError(t("web_login_unavailable"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className={styles.gate}>
      <h1 className={styles.title}>{t("web_login_backup_title")}</h1>
      <div className={styles.warn}>
        <p>{t("web_login_backup_warn1")}</p>
        <p>{t("web_login_backup_warn2")}</p>
      </div>
      <p className={styles.secret}>{newKey.nsec}</p>
      {canCopy && (
        <button type="button" className={styles.ghost} onClick={onCopy}>
          {t("common_copy")}
        </button>
      )}
      {copied && (
        <p role="status" className={styles.note}>
          {copied === "ok" ? t("web_login_backup_copied") : t("web_login_backup_copy_failed")}
        </p>
      )}
      <label className={styles.check}>
        <input type="checkbox" checked={saved} onChange={(e) => setSaved(e.target.checked)} />
        {t("web_login_backup_saved")}
      </label>
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
      <button
        type="button"
        className={styles.submit}
        disabled={!saved || busy}
        aria-busy={busy}
        onClick={onContinue}
      >
        {t("web_login_backup_next")}
      </button>
    </main>
  );
}

// アプリ内のパスだけを許す（//host 等で外へ飛ばさない）
function safeNext(next: string | null): string {
  return next?.startsWith("/") && !next.startsWith("//") ? next : "/";
}

function errorMessage(e: unknown): string {
  if (e instanceof LoginError && e.reason === "missing") {
    return t("web_login_err_missing");
  }
  if (e instanceof LoginError) {
    return t("web_login_err_denied");
  }
  return t("web_login_err_failed");
}

// 例外のメッセージは出さない（署名アプリの応答や bunker:// の secret を含むことがある）
function nip46ErrorMessage(e: unknown): string | null {
  if (!(e instanceof LoginError)) return t("web_login_err_failed");
  switch (e.reason) {
    case "invalid-uri":
      return t("web_login_err_bunker_uri");
    case "timeout":
      return t("web_login_err_nip46_timeout");
    case "rejected":
      return t("web_login_err_rejected");
    case "unavailable":
      return t("web_login_err_unavailable");
    case "cancelled":
      return null;
    default:
      return t("web_login_err_failed");
  }
}

// nostrconnect:// の失敗。承認されないまま時間切れのときだけ文言が bunker:// と違う
function nostrConnectErrorMessage(e: unknown): string | null {
  if (e instanceof LoginError && e.reason === "timeout") {
    return t("web_login_err_connect_timeout");
  }
  return nip46ErrorMessage(e);
}

// 例外のメッセージは出さない（bech32 のエラーは入力を丸ごと含むことがある）
function nsecErrorMessage(e: unknown, value: string): string {
  if (!(e instanceof LoginError)) return t("web_login_err_failed");
  switch (e.reason) {
    case "invalid-format":
      return t("nsec_invalid_fmt", nsecHead(value));
    case "invalid-key":
      return t("web_login_err_invalid_key");
    case "unavailable":
      return t("web_login_unavailable");
    default:
      return t("web_login_err_failed");
  }
}
