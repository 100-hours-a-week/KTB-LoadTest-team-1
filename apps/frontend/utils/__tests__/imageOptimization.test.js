import { afterEach, describe, expect, it, vi } from 'vitest';
import { optimizeImageForUpload } from '../imageOptimization';

const originalCreateElement = document.createElement.bind(document);

describe('optimizeImageForUpload', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('이미지가 아니거나 애니메이션일 수 있는 형식은 원본을 유지한다', async () => {
    const pdf = new File(['pdf'], 'document.pdf', { type: 'application/pdf' });
    const gif = new File(['gif'], 'animation.gif', { type: 'image/gif' });

    await expect(optimizeImageForUpload(pdf, { minBytes: 0 })).resolves.toBe(pdf);
    await expect(optimizeImageForUpload(gif, { minBytes: 0 })).resolves.toBe(gif);
  });

  it('임계값보다 작은 이미지는 디코딩하지 않고 원본을 유지한다', async () => {
    const image = new File(['small'], 'small.png', { type: 'image/png' });
    const createObjectUrl = vi.fn();
    vi.stubGlobal('URL', { createObjectURL: createObjectUrl, revokeObjectURL: vi.fn() });

    await expect(optimizeImageForUpload(image, { minBytes: image.size + 1 })).resolves.toBe(image);
    expect(createObjectUrl).not.toHaveBeenCalled();
  });

  it('큰 이미지를 지정한 최대 크기의 WebP 파일로 변환한다', async () => {
    const original = new File(['x'.repeat(1000)], 'photo.png', {
      type: 'image/png',
      lastModified: 123,
    });
    const drawImage = vi.fn();
    const canvas = {
      width: 0,
      height: 0,
      getContext: () => ({ drawImage }),
      toBlob: (callback) => callback(new Blob(['optimized'], { type: 'image/webp' })),
    };

    class FakeImage {
      naturalWidth = 4000;
      naturalHeight = 2000;

      set src(_value) {
        queueMicrotask(() => this.onload());
      }
    }

    vi.stubGlobal('Image', FakeImage);
    vi.stubGlobal('URL', { createObjectURL: () => 'blob:test', revokeObjectURL: vi.fn() });
    vi.spyOn(document, 'createElement').mockImplementation((tagName, options) => (
      tagName === 'canvas' ? canvas : originalCreateElement(tagName, options)
    ));

    const optimized = await optimizeImageForUpload(original, {
      maxDimension: 1000,
      minBytes: 0,
      minSavingsRatio: 0,
    });

    expect(optimized).not.toBe(original);
    expect(optimized.name).toBe('photo.webp');
    expect(optimized.type).toBe('image/webp');
    expect(optimized.size).toBeLessThan(original.size);
    expect(canvas.width).toBe(1000);
    expect(canvas.height).toBe(500);
    expect(drawImage).toHaveBeenCalledWith(expect.any(FakeImage), 0, 0, 1000, 500);
  });

  it('최적화 결과가 충분히 작아지지 않으면 원본을 유지한다', async () => {
    const original = new File(['x'.repeat(100)], 'photo.jpg', { type: 'image/jpeg' });
    const canvas = {
      width: 0,
      height: 0,
      getContext: () => ({ drawImage: vi.fn() }),
      toBlob: (callback) => callback(new Blob(['x'.repeat(95)], { type: 'image/webp' })),
    };

    class FakeImage {
      naturalWidth = 100;
      naturalHeight = 100;

      set src(_value) {
        queueMicrotask(() => this.onload());
      }
    }

    vi.stubGlobal('Image', FakeImage);
    vi.stubGlobal('URL', { createObjectURL: () => 'blob:test', revokeObjectURL: vi.fn() });
    vi.spyOn(document, 'createElement').mockImplementation((tagName, options) => (
      tagName === 'canvas' ? canvas : originalCreateElement(tagName, options)
    ));

    await expect(optimizeImageForUpload(original, {
      minBytes: 0,
      minSavingsRatio: 0.1,
    })).resolves.toBe(original);
  });
});
