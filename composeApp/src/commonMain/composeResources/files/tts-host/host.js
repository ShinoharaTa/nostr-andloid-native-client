// [#948] Nostrism の読み上げの仲介（アプリの中の見えない WebView で動く）。
// 音声合成：ZEN-TTS（https://zenryokukikai.com）。ランタイムは改変せずにそのまま読み込む（../zen-tts/）。
// ネイティブからの依頼で合成し、1 文ずつ 16bit PCM（base64）にしてネイティブへ返す。再生はネイティブがする。
//
//   ネイティブ → ここ: nostrismTtsLoad(id, voice) / nostrismTtsSpeak(id, text) / nostrismTtsStop()
//   ここ → ネイティブ: {type:'ready'} / {type:'loaded', id} / {type:'chunk', id, index, sampleRate, pcm}
//                      / {type:'end', id} / {type:'error', id, message}
import { ZenTts } from '../zen-tts/index.js';

let tts = null;
/** 合成中の依頼。止められたら -1 にして、残りの文を捨てる。 */
let current = -1;

function post(message) {
  const s = JSON.stringify(message);
  if (window.NostrismTts) window.NostrismTts.post(s);              // Android（addJavascriptInterface）
  else window.webkit?.messageHandlers?.tts?.postMessage(s);         // iOS（WKScriptMessageHandler）
}

function errorText(e) {
  return String((e && e.message) || e);
}

/** float の音声（-1〜1）→ 16bit PCM（リトルエンディアン）の base64 */
function pcm16Base64(f32) {
  const bytes = new Uint8Array(f32.length * 2);
  const view = new DataView(bytes.buffer);
  for (let i = 0; i < f32.length; i++) view.setInt16(i * 2, Math.max(-1, Math.min(1, f32[i])) * 0x7fff, true);
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

window.nostrismTtsLoad = async (id, voice) => {
  try {
    if (tts) { tts.destroy(); tts = null; }
    // CPU（WASM SIMD）で動かす。WebView の WebGPU は端末によってまちまちなので使わない
    tts = await ZenTts.load({ modelsUrl: '/models', cpuWasmUrl: '/zen-tts/cpu/tts-cpu.wasm', backend: 'cpu', voice });
    post({ type: 'loaded', id });
  } catch (e) {
    post({ type: 'error', id, message: errorText(e) });
  }
};

/** 文（区切りの記号・改行まで）。ZEN-TTS は読む文字の無い文で失敗し、その後の文も読めなくなるので、1 文ずつ渡す。 */
const SENTENCES = /[^。！？!?\n]*[。！？!?\n]+|[^。！？!?\n]+$/g;
const SPEAKABLE = /[\p{L}\p{N}]/u;

window.nostrismTtsSpeak = async (id, text) => {
  current = id;
  if (!tts) { post({ type: 'error', id, message: 'not loaded' }); return; }
  let index = 0;
  let lastError = null;
  for (const sentence of text.match(SENTENCES) ?? []) {
    if (!SPEAKABLE.test(sentence)) continue;
    try {
      for await (const chunk of tts.speakStream(sentence)) {
        if (current !== id) return;   // 止められた・次の依頼が来た
        post({ type: 'chunk', id, index: index++, sampleRate: chunk.sampleRate, pcm: pcm16Base64(chunk.pcm) });
      }
    } catch (e) {
      lastError = e;   // その文だけ飛ばして続ける
    }
    if (current !== id) return;
  }
  // 1 文も読めなかったときだけ失敗として返す
  post(index === 0 && lastError ? { type: 'error', id, message: errorText(lastError) } : { type: 'end', id });
};

window.nostrismTtsStop = () => { current = -1; };

post({ type: 'ready' });
