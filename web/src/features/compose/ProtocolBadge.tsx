import { useT } from "../../i18n";
import type { UploadProtocol } from "./mediaServer";
import styles from "./ProtocolBadge.module.css";

/** [#873] アップロード方式のラベル（NIP-96 / Blossom） */
export function useProtocolLabel(): (protocol: UploadProtocol) => string {
  const t = useT();
  return (protocol) => (protocol === "blossom" ? t("media_protocol_blossom") : t("media_protocol_nip96"));
}

/** [#873] 方式のバッジ（ネイティブ UploadProtocolBadge）。設定の一覧・投稿画面の「送信先」で共有する */
export function ProtocolBadge({ protocol }: { protocol: UploadProtocol }) {
  const label = useProtocolLabel();
  return (
    <span className={styles.badge} data-protocol={protocol}>
      {label(protocol)}
    </span>
  );
}
