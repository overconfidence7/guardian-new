/** Client-side photo resizing before upload (docs/API.md: dashboard resizes to 1600 px). */

export const PHOTO_MAX_EDGE = 1600;
export const CONTACT_PHOTO_MAX_EDGE = 512;
/** Photo captions are up to 200 characters (docs/API.md, enforced by firestore.rules). */
export const PHOTO_CAPTION_MAX = 200;
export const JPEG_QUALITY = 0.85;

/**
 * Scale (width, height) so the longest edge is at most `maxEdge`, preserving aspect
 * ratio. Never upscales. Results are whole pixels and at least 1.
 */
export function fitWithin(width: number, height: number, maxEdge: number): { width: number; height: number; scale: number } {
  if (!(width > 0) || !(height > 0)) throw new RangeError('Image has no size');
  const longest = Math.max(width, height);
  const scale = longest > maxEdge ? maxEdge / longest : 1;
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
    scale,
  };
}

/** Square center-crop box for avatar photos. */
export function centerSquare(width: number, height: number): { sx: number; sy: number; size: number } {
  const size = Math.min(width, height);
  return { sx: Math.round((width - size) / 2), sy: Math.round((height - size) / 2), size };
}

async function loadBitmap(file: Blob): Promise<ImageBitmap | HTMLImageElement> {
  if ('createImageBitmap' in window) {
    try {
      // `from-image` applies EXIF orientation so phone photos aren't sideways.
      return await createImageBitmap(file, { imageOrientation: 'from-image' });
    } catch {
      /* fall through (e.g. HEIC on browsers that can't decode it) */
    }
  }
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.decoding = 'async';
    img.src = url;
    await img.decode();
    return img;
  } finally {
    URL.revokeObjectURL(url);
  }
}

function toJpeg(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  return new Promise((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Could not encode JPEG'))), 'image/jpeg', quality),
  );
}

export interface ResizedImage {
  blob: Blob;
  width: number;
  height: number;
}

/** Resize to a JPEG whose longest edge is at most `maxEdge` (optionally square-cropped). */
export async function resizeImage(
  file: Blob,
  { maxEdge = PHOTO_MAX_EDGE, square = false, quality = JPEG_QUALITY }: { maxEdge?: number; square?: boolean; quality?: number } = {},
): Promise<ResizedImage> {
  const source = await loadBitmap(file);
  const srcW = source.width;
  const srcH = source.height;
  const crop = square ? centerSquare(srcW, srcH) : { sx: 0, sy: 0, size: 0 };
  const inW = square ? crop.size : srcW;
  const inH = square ? crop.size : srcH;
  const { width, height } = fitWithin(inW, inH, maxEdge);
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Canvas is not available');
  ctx.fillStyle = '#ffffff'; // PNG transparency becomes white, not black, in JPEG
  ctx.fillRect(0, 0, width, height);
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(source, crop.sx, crop.sy, inW, inH, 0, 0, width, height);
  if ('close' in source) source.close();
  return { blob: await toJpeg(canvas, quality), width, height };
}
