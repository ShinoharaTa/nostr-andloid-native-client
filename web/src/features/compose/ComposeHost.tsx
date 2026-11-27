import { useEffect } from "react";
import { useT } from "../../i18n";
import { setPublishAccount, unconfirmed$ } from "../../nostr/publish";
import { useSession } from "../../signer/session";
import { EditIcon, MoodIcon } from "../../ui/icons";
import { Toaster } from "../../ui/Toaster";
import { showToast } from "../../ui/toast";
import { StatusEditorDialog } from "../status/StatusEditorDialog";
import { closeStatusEditor, openStatusEditor, useStatusEditor } from "../status/statusEditorStore";
import { ComposeDialog } from "./ComposeDialog";
import styles from "./ComposeHost.module.css";
import { openCompose, useCompose } from "./composeStore";

/**
 * 投稿まわり（AppShell から呼ぶ唯一の入口）: 投稿ボタン（FAB）・投稿シート・トースト。
 * ログイン中のアカウントを送信キューへ伝え、受理を確認できなかったらトーストを出す。
 * [#1020] 投稿ボタンの上に、自分のステータス（NIP-38）を更新するボタン（ログイン中だけ）。ステータスの画面もここに載せる
 * （レールのボタンからも開く）。
 */
export function ComposeHost({ showFab }: { showFab: boolean }) {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const request = useCompose((s) => s.request);
  const statusOpen = useStatusEditor((s) => s.open);

  useEffect(() => {
    if (me !== null) setPublishAccount(me);
  }, [me]);

  useEffect(() => {
    const subscription = unconfirmed$.subscribe(() => showToast(t("publish_unconfirmed")));
    return () => subscription.unsubscribe();
  }, [t]);

  return (
    <>
      {showFab && (
        <div className={styles.fabs}>
          {me !== null && (
            <button
              type="button"
              className={styles.statusFab}
              aria-label={t("status_edit_title")}
              title={t("status_edit_title")}
              onClick={openStatusEditor}
            >
              <MoodIcon className={styles.fabIcon} />
            </button>
          )}
          <button
            type="button"
            className={styles.fab}
            aria-label={t("fab_post")}
            onClick={() => openCompose({ mode: "new" })}
          >
            <EditIcon className={styles.fabIcon} />
          </button>
        </div>
      )}
      {statusOpen && me !== null && <StatusEditorDialog pubkey={me} onClose={closeStatusEditor} />}
      {request && (
        <ComposeDialog
          key={request.mode + (request.mode === "new" ? "" : request.target.id)}
          request={request}
        />
      )}
      <Toaster />
    </>
  );
}
