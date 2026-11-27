import { create } from "zustand";

/** [#1020] 自分のステータスを更新する画面を開いているか */
export const useStatusEditor = create<{ open: boolean }>()(() => ({ open: false }));

/** ステータスの画面を開く（右下のボタン・レールのボタンから） */
export function openStatusEditor(): void {
  useStatusEditor.setState({ open: true });
}

export function closeStatusEditor(): void {
  useStatusEditor.setState({ open: false });
}
