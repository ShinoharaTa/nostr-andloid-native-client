import { useCallback, useRef } from "react";
import { useLocation, useNavigate } from "react-router";
import { useLayoutMode } from "../../ui/useLayoutMode";
import { settingsSectionTarget } from "./sections";

/**
 * [#807] 設定の項目を直接開く（自分のアイコンのメニュー・プロフィールの「編集」）。行き先と戻り先は
 * settingsSectionTarget。呼んだときの場所で決める（メニューを閉じた後に呼ばれても、閉じた後の場所を見る）。
 */
export function useOpenSettingsSection(): (id: string) => void {
  const navigate = useNavigate();
  const location = useLocation();
  const mode = useLayoutMode();
  const here = useRef({ pathname: location.pathname, state: location.state as unknown, mode });
  here.current = { pathname: location.pathname, state: location.state, mode };
  return useCallback(
    (id: string) => {
      const target = settingsSectionTarget(id, here.current);
      void navigate(target.to, { replace: target.replace, state: target.state });
    },
    [navigate],
  );
}
