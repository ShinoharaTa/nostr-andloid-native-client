import { type FormEvent, useEffect, useId, useRef, useState } from "react";
import { useT } from "../../i18n";
import { requestZapInvoice } from "../../lib/lnurl";
import { ConfirmDialog } from "../../ui/ConfirmDialog";
import { QrCode } from "../../ui/QrCode";
import { showToast } from "../../ui/toast";
import styles from "./ZapDialog.module.css";
import { isTouchDevice, ZAP_WALLETS } from "./zapWallets";

/** WebLN（Alby 等の拡張がページに注入する window.webln）のうち使うところ */
type WebLNProvider = {
  enable(): Promise<void>;
  sendPayment(paymentRequest: string): Promise<{ preimage: string }>;
};

declare global {
  interface Window {
    webln?: WebLNProvider;
  }
}

/** 金額のプリセット（sats。ネイティブ ZAP_PRESETS） */
export const ZAP_PRESETS: readonly number[] = [21, 100, 500, 1000, 5000, 10000];
const DEFAULT_AMOUNT = 100;

/** カスタム額（数字だけ）。1 以上の整数ならそれ、それ以外は null（プリセットを使う） */
function customSats(value: string): number | null {
  if (!/^\d+$/.test(value)) return null;
  const n = Number(value);
  return Number.isSafeInteger(n) && n > 0 ? n : null;
}

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/**
 * Zap ダイアログ（ネイティブ ZapSheet.kt ZapSheetImpl）。金額（プリセット / カスタム）とコメントを選び、
 * LNURL-pay で invoice を取って支払う。渡し先は payWithWallet（NWC、#537。invoice 取得後に確認ダイアログを
 * 経てから呼ぶ）→ window.webln → 外部ウォレット（invoice の QR・`lightning:` リンク・コピーを閉じるまで出す）。
 * eventId が無ければプロフィール Zap。処理中はスピナーで閉じない。
 * 送った後の合計は receipt の購読（#523）で増える（楽観更新はしない）。
 */
export function ZapDialog({
  recipient,
  recipientName,
  lud16,
  eventId,
  targetKind,
  payWithWallet,
  onClose,
}: {
  recipient: string;
  recipientName: string;
  lud16: string;
  eventId?: string;
  targetKind?: number;
  /** ウォレット接続（NWC）での支払い。#537 が渡す。無ければ未接続 */
  payWithWallet?: (pr: string) => Promise<void>;
  onClose(): void;
}) {
  const t = useT();
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descId = useId();
  const formId = useId();
  const [amount, setAmount] = useState(DEFAULT_AMOUNT);
  const [custom, setCustom] = useState("");
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 外部ウォレットで払う invoice（出したら閉じるまでそのまま）
  const [invoice, setInvoice] = useState<string | null>(null);
  const [copyStatus, setCopyStatus] = useState<string | null>(null);
  // [#994] ウォレットの一覧を出すか（開いている間は変えない）
  const [touchDevice] = useState(isTouchDevice);
  // [#537] NWC の送金確認（毎回）。invoice 取得後、確定で payWithWallet を呼ぶ
  const [confirmInvoice, setConfirmInvoice] = useState<string | null>(null);
  const effectiveAmount = customSats(custom) ?? amount;

  useEffect(() => {
    const d = dialog.current;
    if (d && !d.open) d.showModal();
  }, []);

  async function send(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    const pr = await requestZapInvoice({
      recipient,
      lud16,
      amountSats: effectiveAmount,
      comment,
      eventId,
      targetKind,
    });
    if (pr === null) {
      setBusy(false);
      setError(t("zap_invoice_failed"));
      return;
    }
    if (payWithWallet) {
      // [#537] ウォレット接続済みは毎回確認してから支払う（ネイティブ ZapSheetImpl と同じ）
      setBusy(false);
      setConfirmInvoice(pr);
      return;
    }
    const webln = window.webln;
    if (!webln) {
      setBusy(false);
      setInvoice(pr);
      return;
    }
    try {
      await webln.enable();
      await webln.sendPayment(pr);
    } catch (err) {
      // 拒否・失敗は外部ウォレットへ逃がす（ネイティブ nwc_pay_failed_fmt）
      setBusy(false);
      setError(t("nwc_pay_failed_fmt", messageOf(err)));
      setInvoice(pr);
      return;
    }
    showToast(t("nwc_paid"));
    onClose();
  }

  // [#537] NWC の送金確認の確定。成功でトーストを出して閉じ、失敗は外部ウォレットへ逃がす
  async function confirmPay(pr: string) {
    setConfirmInvoice(null);
    setBusy(true);
    setError(null);
    try {
      await payWithWallet?.(pr);
    } catch (err) {
      setBusy(false);
      setError(t("nwc_pay_failed_fmt", messageOf(err)));
      setInvoice(pr);
      return;
    }
    setBusy(false);
    showToast(t("nwc_paid"));
    onClose();
  }

  async function copyInvoice(pr: string) {
    try {
      await navigator.clipboard.writeText(pr);
      setCopyStatus(t("copied"));
    } catch {
      setCopyStatus(t("web_copy_failed"));
    }
  }

  return (
    <>
      <dialog
        ref={dialog}
        className={styles.dialog}
        aria-labelledby={titleId}
        aria-describedby={descId}
        aria-busy={busy}
        // React は cancel を親へ伝えるので、外側の dialog を一緒に閉じないよう止める
        onCancel={(e) => {
          e.preventDefault();
          e.stopPropagation();
          if (!busy) onClose();
        }}
      >
        <h2 id={titleId} className={styles.title}>
          ⚡ Zap
        </h2>
        <p id={descId} className={styles.desc}>
          {t("zap_desc_fmt", recipientName)}
        </p>
        {invoice === null ? (
          <form id={formId} className={styles.form} onSubmit={send}>
            <fieldset className={styles.presets} aria-label={t("web_zap_amount")} disabled={busy}>
              {ZAP_PRESETS.map((sats) => {
                const active = custom === "" && amount === sats;
                return (
                  <button
                    key={sats}
                    type="button"
                    className={active ? `${styles.preset} ${styles.active}` : styles.preset}
                    aria-pressed={active}
                    onClick={() => {
                      setAmount(sats);
                      setCustom("");
                    }}
                  >
                    {sats}
                  </button>
                );
              })}
            </fieldset>
            <input
              className={styles.input}
              type="text"
              inputMode="numeric"
              aria-label={t("zap_custom_amount")}
              placeholder={t("zap_custom_amount")}
              value={custom}
              disabled={busy}
              onChange={(e) => setCustom(e.target.value.replace(/\D/g, ""))}
            />
            <input
              className={styles.input}
              type="text"
              aria-label={t("zap_comment")}
              placeholder={t("zap_comment")}
              value={comment}
              disabled={busy}
              onChange={(e) => setComment(e.target.value)}
            />
          </form>
        ) : (
          <div className={styles.invoice}>
            <QrCode
              value={`lightning:${invoice}`.toUpperCase()}
              label={t("web_zap_invoice_label", effectiveAmount)}
            />
            <div className={styles.invoiceActions}>
              <a className={styles.external} href={`lightning:${invoice}`}>
                {t("nwc_open_external")}
              </a>
              <button type="button" className={styles.textButton} onClick={() => void copyInvoice(invoice)}>
                {t("common_copy")}
              </button>
            </div>
            {copyStatus && (
              <p role="status" className={styles.status}>
                {copyStatus}
              </p>
            )}
            {/* [#994] スマホのブラウザでは、ウォレットを指定して開ける（iOS の lightning: は選べないため） */}
            {touchDevice && (
              <details className={styles.otherWallets}>
                <summary>{t("web_zap_other_wallets")}</summary>
                <p className={styles.desc}>{t("web_zap_other_wallets_hint")}</p>
                <ul className={styles.walletList}>
                  {ZAP_WALLETS.map((w) => (
                    <li key={w.id}>
                      <a className={styles.walletLink} href={`${w.prefix}${invoice}`}>
                        {w.name}
                      </a>
                    </li>
                  ))}
                </ul>
              </details>
            )}
          </div>
        )}
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        {payWithWallet && invoice === null && <p className={styles.desc}>{t("nwc_via")}</p>}
        <div className={styles.footer}>
          <span className={styles.to}>{t("zap_to_fmt", lud16)}</span>
          {busy ? (
            <span className={styles.spinner} aria-hidden="true" />
          ) : invoice === null ? (
            <>
              <button type="button" className={styles.textButton} onClick={onClose}>
                {t("common_cancel")}
              </button>
              <button type="submit" form={formId} className={styles.primary}>
                {`⚡ ${effectiveAmount}`}
              </button>
            </>
          ) : (
            <button type="button" className={styles.textButton} onClick={onClose}>
              {t("common_close")}
            </button>
          )}
        </div>
      </dialog>
      {confirmInvoice !== null && (
        <ConfirmDialog
          title={t("nwc_pay_confirm_title")}
          text={t("web_zap_pay_confirm_text", recipientName, effectiveAmount)}
          confirmLabel={t("nwc_pay_confirm")}
          onConfirm={() => void confirmPay(confirmInvoice)}
          onDismiss={() => setConfirmInvoice(null)}
        />
      )}
    </>
  );
}
