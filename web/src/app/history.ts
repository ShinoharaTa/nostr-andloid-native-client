import { useCallback, useEffect, useRef, useState } from "react";
import { NavigationType, useLocation, useNavigate, useNavigationType } from "react-router";
import { useDeck } from "../store/deck";

/** アプリの配信パス（Router の basename。#647 で /app から / に統合） */
export const APP_BASENAME = "/";

/** 直リンクで開いたとき下にデッキを敷くパス（詳細 /e /p と、一時カラムになる /t） */
const DETAIL_PATH = /^\/(e|p|t)\/[^/]+\/?$/;

function stateObject(state: unknown): Record<string, unknown> | null {
  return typeof state === "object" && state !== null ? (state as Record<string, unknown>) : null;
}

/**
 * アプリ内に戻れる履歴があるか。react-router の history.state.idx（最初のエントリが 0、push ごとに +1）に依存する。
 * react-router 8.4.0 の lib/router/history.js で確認済み。メジャー更新時に見直す。
 */
export function canGoBackInApp(state: unknown = window.history.state): boolean {
  const idx = stateObject(state)?.idx;
  return typeof idx === "number" && idx > 0;
}

/**
 * 履歴の先頭で /e /p /t を開いたら、下に / を 1 つ敷く（Router を作る前に 1 回だけ呼ぶ）。
 * 共有リンクから開いても「戻る = 詳細を閉じてデッキ」、もう一度戻る = アプリを出る（ネイティブと同じ順）。
 */
export function synthesizeBaseEntry(win: Window = window): boolean {
  const { pathname, search, hash } = win.location;
  if (!DETAIL_PATH.test(pathname) || canGoBackInApp(win.history.state)) return false;
  const url = pathname + search + hash;
  const usr = stateObject(win.history.state)?.usr ?? null;
  win.history.replaceState({ usr: null, key: "default", idx: 0 }, "", APP_BASENAME);
  win.history.pushState({ usr, key: "synthbase", idx: 1 }, "", url);
  return true;
}

/** 一時カラムを開いた履歴エントリの印（location.state のキー。値はカラム id） */
export const DECK_TRANSIENT = "deckTransient";

export function transientIdOf(state: unknown): string | null {
  const id = stateObject(state)?.[DECK_TRANSIENT];
  return typeof id === "string" ? id : null;
}

/** 詳細を閉じる（「←」・スクリム・Esc）。アプリ内に戻れれば戻る、無ければデッキへ置き換える */
export function useCloseOverlay(): () => void {
  const navigate = useNavigate();
  return useCallback(() => {
    if (canGoBackInApp()) void navigate(-1);
    else void navigate("/", { replace: true });
  }, [navigate]);
}

/**
 * 一時カラムの印を持つエントリから戻ったら、そのカラムを閉じる（ネイティブの DeckState.back()）。
 * 固定済み・既に無いカラムには何もしない。
 */
export function useTransientHistory(): void {
  const location = useLocation();
  const navigationType = useNavigationType();
  const prev = useRef(location);

  useEffect(() => {
    const before = prev.current;
    prev.current = location;
    if (navigationType !== NavigationType.Pop || before === location) return;
    const id = transientIdOf(before.state);
    if (id === null || id === transientIdOf(location.state)) return;
    const s = useDeck.getState();
    const col = s.columns.find((c) => c.id === id);
    if (col && !col.pinned) s.back();
  }, [location, navigationType]);
}

/** [#540] ⋯ メニュー（カラム・投稿・プロフィール）が開いている印。履歴の state（usr）に持たせる */
const MENU_OPEN_KEY = "menuOpen";

function isMenuOpenState(state: unknown): boolean {
  return (
    typeof state === "object" && state !== null && (state as Record<string, unknown>)[MENU_OPEN_KEY] === true
  );
}

/**
 * [#540] ⋯ メニュー（カラム・投稿・プロフィール）を開いている間の「戻る」は、メニューを閉じるだけにする
 * （それまでは履歴が戻ってメニューの裏の画面まで動いてしまっていた）。
 * open の間だけ同じ URL に印付きの履歴エントリを 1 つ積み、そこから「戻る」で離れた（＝印無しへの POP）
 * ら onClose を呼ぶ。項目選択・Escape・外側クリックなど別の理由で閉じたときは、積んだ分を自分で
 * 1 つ戻って消す（次の本当の「戻る」が 2 回に増えないように）。
 */
export function useCloseMenuOnBack(open: boolean, onClose: () => void): void {
  const navigate = useNavigate();
  const location = useLocation();
  const navigationType = useNavigationType();
  const pushed = useRef(false);
  const prevLocation = useRef(location);
  const latest = useRef({ navigate, location });
  latest.current = { navigate, location };
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  // 戻る/進むの検知。自分が積んだ印から離れたら閉じる
  useEffect(() => {
    const before = prevLocation.current;
    prevLocation.current = location;
    if (!pushed.current || navigationType !== NavigationType.Pop || before === location) return;
    if (isMenuOpenState(before.state) && !isMenuOpenState(location.state)) {
      pushed.current = false;
      onCloseRef.current();
    }
  }, [location, navigationType]);

  // open が true の間だけ履歴を 1 つ積み、false に戻ったら（アンマウント含め）自分で消す
  useEffect(() => {
    if (!open) return;
    pushed.current = true;
    const { navigate: nav, location: loc } = latest.current;
    const base = stateObject(loc.state) ?? {};
    void nav(
      { pathname: loc.pathname, search: loc.search, hash: loc.hash },
      { state: { ...base, [MENU_OPEN_KEY]: true } },
    );
    return () => {
      // pushed のままなら、まだ自分の積んだ印が残っている（＝戻るでは消費されていない）
      if (!pushed.current) return;
      pushed.current = false;
      void latest.current.navigate(-1);
    };
  }, [open]);
}

/**
 * [#797] ⋯ メニューの項目で画面を移るとき用。useCloseMenuOnBack が積んだ印のエントリが戻って消えてから run を呼ぶ
 * （消える前に移ると、印を消すための「戻る」が移った先のエントリを消してしまう）。印が無ければ次の描画の後に呼ぶ。
 * run は呼ぶときの場所（閉じた後の場所）で行き先を決めること。
 */
export function useAfterMenuClosed(): (run: () => void) => void {
  const location = useLocation();
  const pending = useRef<(() => void) | null>(null);
  const [requested, setRequested] = useState(0);

  useEffect(() => {
    void requested;
    const run = pending.current;
    if (!run || isMenuOpenState(location.state)) return;
    pending.current = null;
    run();
  }, [location, requested]);

  return useCallback((run: () => void) => {
    pending.current = run;
    setRequested((n) => n + 1);
  }, []);
}
