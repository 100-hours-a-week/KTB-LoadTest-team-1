const DEFAULT_OPTIONS = Object.freeze({
  maxDimension: 1920,
  quality: 0.82,
  minBytes: 256 * 1024,
  minSavingsRatio: 0.1,
});

export const IMAGE_OPTIMIZATION_PRESETS = Object.freeze({
  chat: DEFAULT_OPTIONS,
  profile: Object.freeze({
    maxDimension: 512,
    quality: 0.82,
    minBytes: 64 * 1024,
    minSavingsRatio: 0.1,
  }),
});

const SKIPPED_IMAGE_TYPES = new Set(['image/gif', 'image/webp']);

const replaceExtension = (filename, extension) => {
  const lastDot = filename.lastIndexOf('.');
  const basename = lastDot > 0 ? filename.slice(0, lastDot) : filename;
  return `${basename}.${extension}`;
};

const loadImage = (file) => new Promise((resolve, reject) => {
  const objectUrl = URL.createObjectURL(file);
  const image = new Image();

  const cleanup = () => URL.revokeObjectURL(objectUrl);

  image.onload = () => {
    cleanup();
    resolve(image);
  };
  image.onerror = () => {
    cleanup();
    reject(new Error('이미지를 디코딩할 수 없습니다.'));
  };
  image.src = objectUrl;
});

const canvasToBlob = (canvas, type, quality) => new Promise((resolve) => {
  canvas.toBlob(resolve, type, quality);
});

/**
 * 업로드 전에 큰 정지 이미지를 리사이즈하고 WebP로 압축한다.
 *
 * 최적화가 지원되지 않거나 결과가 충분히 작아지지 않으면 원본을 그대로 반환한다.
 * GIF/WebP는 애니메이션 손실 및 불필요한 재압축을 피하기 위해 변환하지 않는다.
 */
export const optimizeImageForUpload = async (file, options = {}) => {
  if (!file?.type?.startsWith('image/') || SKIPPED_IMAGE_TYPES.has(file.type)) {
    return file;
  }

  const settings = { ...DEFAULT_OPTIONS, ...options };
  if (file.size < settings.minBytes || typeof document === 'undefined' || typeof Image === 'undefined') {
    return file;
  }

  try {
    const image = await loadImage(file);
    const sourceWidth = image.naturalWidth || image.width;
    const sourceHeight = image.naturalHeight || image.height;

    if (!sourceWidth || !sourceHeight) return file;

    const scale = Math.min(1, settings.maxDimension / Math.max(sourceWidth, sourceHeight));
    const width = Math.max(1, Math.round(sourceWidth * scale));
    const height = Math.max(1, Math.round(sourceHeight * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;

    const context = canvas.getContext('2d');
    if (!context) return file;

    context.drawImage(image, 0, 0, width, height);
    const blob = await canvasToBlob(canvas, 'image/webp', settings.quality);
    if (!blob) return file;

    const minimumSaving = file.size * settings.minSavingsRatio;
    if (file.size - blob.size < minimumSaving) return file;

    return new File(
      [blob],
      replaceExtension(file.name, 'webp'),
      { type: 'image/webp', lastModified: file.lastModified }
    );
  } catch (error) {
    console.warn('Image optimization skipped:', error);
    return file;
  }
};
