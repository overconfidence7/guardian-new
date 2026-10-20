import { describe, expect, it } from 'vitest';
import rules from '../../../firebase/firestore.rules?raw';
import { centerSquare, fitWithin, PHOTO_CAPTION_MAX, PHOTO_MAX_EDGE } from './image';

describe('photo captions', () => {
  it('allow as many characters as firestore.rules does (200)', () => {
    expect(PHOTO_CAPTION_MAX).toBe(200);
    expect(rules).toContain(`isShortString(request.resource.data.caption, ${PHOTO_CAPTION_MAX})`);
  });
});


describe('fitWithin', () => {
  it('scales the longest edge to the limit, keeping aspect ratio', () => {
    expect(fitWithin(4032, 3024, PHOTO_MAX_EDGE)).toEqual({ width: 1600, height: 1200, scale: 1600 / 4032 });
    expect(fitWithin(3024, 4032, 1600)).toMatchObject({ width: 1200, height: 1600 });
    expect(fitWithin(5000, 1000, 1600)).toMatchObject({ width: 1600, height: 320 });
  });
  it('never upscales', () => {
    expect(fitWithin(800, 600, 1600)).toEqual({ width: 800, height: 600, scale: 1 });
    expect(fitWithin(1600, 1600, 1600)).toMatchObject({ width: 1600, height: 1600, scale: 1 });
  });
  it('rounds to whole pixels and keeps at least 1px', () => {
    const r = fitWithin(4001, 3, 1600);
    expect(Number.isInteger(r.width) && Number.isInteger(r.height)).toBe(true);
    expect(r.height).toBe(1);
  });
  it('rejects empty images', () => {
    expect(() => fitWithin(0, 100, 1600)).toThrow();
    expect(() => fitWithin(Number.NaN, 100, 1600)).toThrow();
  });
});

describe('centerSquare', () => {
  it('crops the middle square', () => {
    expect(centerSquare(400, 300)).toEqual({ sx: 50, sy: 0, size: 300 });
    expect(centerSquare(300, 500)).toEqual({ sx: 0, sy: 100, size: 300 });
  });
});
