/**
 * RGBA の PNG エンコード（仕様: docs/emoji-maker.md §5.4）。依存を足さない。
 * IHDR（bit depth 8・color type 6・インターレース無し）+ 各行の先頭にフィルタ 0 を付けて
 * CompressionStream("deflate")（zlib 形式。workerd・Node 両対応）で圧縮した IDAT 1 つ + IEND。
 */

const SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(bytes: Uint8Array, start: number, end: number): number {
  let c = 0xffffffff;
  for (let i = start; i < end; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

async function deflate(data: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
  const stream = new Blob([data]).stream().pipeThrough(new CompressionStream("deflate"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

/** rgba（width × height × 4、ストレートアルファ）を PNG にする。 */
export async function encodePng(
  rgba: Uint8Array,
  width: number,
  height: number,
): Promise<Uint8Array<ArrayBuffer>> {
  const rowBytes = width * 4;
  const raw = new Uint8Array((rowBytes + 1) * height);
  for (let y = 0; y < height; y++) {
    // 先頭の 1 バイトはフィルタ種別 0（None）。new Uint8Array が 0 で埋めている
    raw.set(rgba.subarray(y * rowBytes, (y + 1) * rowBytes), y * (rowBytes + 1) + 1);
  }
  const idat = await deflate(raw);

  const ihdr = new Uint8Array(13);
  const ihdrView = new DataView(ihdr.buffer);
  ihdrView.setUint32(0, width);
  ihdrView.setUint32(4, height);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // color type: RGBA
  // [10] 圧縮 0、[11] フィルタ 0、[12] インターレース 0

  const chunks: [string, Uint8Array][] = [
    ["IHDR", ihdr],
    ["IDAT", idat],
    ["IEND", new Uint8Array(0)],
  ];
  const size = SIGNATURE.length + chunks.reduce((sum, [, data]) => sum + 12 + data.length, 0);
  const png = new Uint8Array(size);
  const view = new DataView(png.buffer);
  png.set(SIGNATURE, 0);
  let offset = SIGNATURE.length;
  for (const [type, data] of chunks) {
    view.setUint32(offset, data.length);
    for (let i = 0; i < 4; i++) png[offset + 4 + i] = type.charCodeAt(i);
    png.set(data, offset + 8);
    view.setUint32(offset + 8 + data.length, crc32(png, offset + 4, offset + 8 + data.length));
    offset += 12 + data.length;
  }
  return png;
}
