import { useState } from "react";
import { useT } from "../i18n";
import { useSession } from "../signer/session";
import { ConfirmDialog } from "../ui/ConfirmDialog";

/** ログアウト（確認つき）。見た目は置き場所ごとに className で渡す（#463 の設定画面もこれを使う） */
export function LogoutButton({ className }: { className?: string }) {
  const t = useT();
  const [confirming, setConfirming] = useState(false);
  return (
    <>
      <button type="button" className={className} onClick={() => setConfirming(true)}>
        {t("logout")}
      </button>
      {confirming && <LogoutConfirmDialog onDismiss={() => setConfirming(false)} />}
    </>
  );
}

/**
 * ログアウトの確認ダイアログ（ネイティブ LogoutConfirmDialog）。設定のログアウトと自分のアイコンのメニュー（#797）の両方から使う。
 * 確認したら閉じてからログアウトする。
 */
export function LogoutConfirmDialog({ onDismiss }: { onDismiss(): void }) {
  const t = useT();
  const method = useSession((s) => s.method);
  return (
    <ConfirmDialog
      title={t("logout_title")}
      text={
        method === "local"
          ? t("web_logout_text_local")
          : method === "nip46"
            ? t("web_logout_text_nip46")
            : t("web_logout_text_nip07")
      }
      confirmLabel={t("logout")}
      destructive
      onConfirm={() => {
        onDismiss();
        useSession.getState().logout();
      }}
      onDismiss={onDismiss}
    />
  );
}
