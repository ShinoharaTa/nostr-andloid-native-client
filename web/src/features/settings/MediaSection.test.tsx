import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it } from "vitest";
import { COMPRESSION_KEY, resetImageCompression, useImageCompression } from "../compose/imageCompression";
import { MEDIA_SERVER_KEY, parseStoredServer, setMediaServer, useMediaServer } from "../compose/mediaServer";
import { MediaSection } from "./MediaSection";

afterEach(() => {
  setMediaServer(null);
  resetImageCompression();
  localStorage.clear();
});

it("未選択は既定の先頭（nostr.build）が選択中。候補を押すとそのサーバーだけにする", async () => {
  const user = userEvent.setup();
  render(<MediaSection />);

  expect(screen.getByText(/nostr\.build → nostrcheck\.me の順に試します/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "nostr.build NIP-96" })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("button", { name: "files.sovbit.host NIP-96" })).toHaveAttribute(
    "aria-pressed",
    "false",
  );

  await user.click(screen.getByRole("button", { name: "nostpic.com NIP-96" }));
  expect(useMediaServer.getState().server).toEqual({ url: "https://nostpic.com", protocol: "nip96" });
  expect(JSON.parse(localStorage.getItem(MEDIA_SERVER_KEY) ?? "")).toEqual({
    url: "https://nostpic.com",
    protocol: "nip96",
  });
  expect(screen.getByRole("button", { name: "nostpic.com NIP-96" })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("button", { name: "nostr.build NIP-96" })).toHaveAttribute("aria-pressed", "false");

  await user.click(screen.getByRole("button", { name: "既定に戻す" }));
  expect(useMediaServer.getState().server).toBeNull();
  expect(screen.queryByRole("button", { name: "既定に戻す" })).toBeNull();
});

it("[#873] 候補は方式の見出しで分け、各行に方式のバッジ。Blossom の候補を選べる", async () => {
  const user = userEvent.setup();
  render(<MediaSection />);
  expect(screen.getByRole("heading", { name: "アップロード先（NIP-96 / Blossom）" })).toBeInTheDocument();

  const nip96 = screen.getByText("NIP-96", { selector: "p" }).parentElement as HTMLElement;
  const blossom = screen.getByText("Blossom", { selector: "p" }).parentElement as HTMLElement;
  expect(
    within(nip96)
      .getAllByRole("button")
      .map((b) => b.textContent),
  ).toEqual([
    "share.yabu.me NIP-96",
    "nostrcheck.me NIP-96",
    "nostr.build NIP-96",
    "nostpic.com NIP-96",
    "files.sovbit.host NIP-96",
    "nostrmedia.com NIP-96",
  ]);
  expect(
    within(blossom)
      .getAllByRole("button")
      .map((b) => b.textContent),
  ).toEqual([
    "cdn.nostrcheck.me Blossom",
    "blossom.band Blossom",
    "nostr.download Blossom",
    "blossom.primal.net Blossom",
  ]);

  await user.click(within(blossom).getByRole("button", { name: "blossom.band Blossom" }));
  expect(useMediaServer.getState().server).toEqual({ url: "https://blossom.band", protocol: "blossom" });
});

it("URL を入力して選べる（https のみ）。方式を選べ（既定は NIP-96）、選んだものは候補に並ぶ", async () => {
  const user = userEvent.setup();
  render(<MediaSection />);
  const input = screen.getByRole("textbox", { name: "アップロード先サーバーの URL" });
  const protocols = screen.getByRole("group", { name: "方式" });
  expect(within(protocols).getByRole("button", { name: "NIP-96" })).toHaveAttribute("aria-pressed", "true");
  expect(within(protocols).getByRole("button", { name: "Blossom" })).toHaveAttribute("aria-pressed", "false");

  await user.type(input, "http://example.com");
  await user.click(screen.getByRole("button", { name: "選択" }));
  expect(screen.getByRole("alert")).toHaveTextContent("https:// で始まるサーバーの URL を入力してください");
  expect(useMediaServer.getState().server).toBeNull();

  await user.clear(input);
  await user.type(input, "https://media.example.com/");
  await user.click(screen.getByRole("button", { name: "選択" }));
  expect(useMediaServer.getState().server).toEqual({ url: "https://media.example.com", protocol: "nip96" });
  expect(screen.getByRole("button", { name: "media.example.com NIP-96" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  expect(input).toHaveValue("");

  await user.click(within(protocols).getByRole("button", { name: "Blossom" }));
  await user.type(input, "https://blob.example.com");
  await user.click(screen.getByRole("button", { name: "選択" }));
  expect(useMediaServer.getState().server).toEqual({ url: "https://blob.example.com", protocol: "blossom" });
  expect(screen.getByRole("button", { name: "blob.example.com Blossom" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
});

it("[#873] 以前の保存形式（URL の文字列）の送信先は NIP-96 として選択中に出る", () => {
  setMediaServer(parseStoredServer("https://media.example.com"));
  render(<MediaSection />);
  expect(screen.getByRole("button", { name: "media.example.com NIP-96" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
});

it("画像アップロードの圧縮: 既定値。範囲外は保存時に丸め、既定に戻すと消える", async () => {
  const user = userEvent.setup();
  render(<MediaSection />);

  const low = screen.getByRole("spinbutton", { name: "「低」の長辺（px）" });
  const mid = screen.getByRole("spinbutton", { name: "「中」の長辺（px）" });
  const quality = screen.getByRole("spinbutton", { name: "再エンコード品質（%）" });
  expect(low).toHaveValue(640);
  expect(mid).toHaveValue(1200);
  expect(quality).toHaveValue(85);

  await user.clear(low);
  await user.type(low, "127");
  await user.tab();
  expect(low).toHaveValue(128);
  expect(useImageCompression.getState().prefs.lowMaxDim).toBe(128);
  expect(JSON.parse(localStorage.getItem(COMPRESSION_KEY) ?? "")).toMatchObject({ lowMaxDim: 128 });

  await user.clear(quality);
  await user.type(quality, "29");
  await user.tab();
  expect(quality).toHaveValue(30);

  await user.click(screen.getByRole("button", { name: "画像アップロードの圧縮を既定に戻す" }));
  expect(low).toHaveValue(640);
  expect(quality).toHaveValue(85);
  expect(localStorage.getItem(COMPRESSION_KEY)).toBeNull();
});
