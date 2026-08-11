import { afterEach, describe, expect, it, vi } from 'vitest';

const axiosInstancePost = vi.fn();
const axiosPut = vi.fn();

vi.mock('../../components/Toast', () => ({
  Toast: {
    error: vi.fn(),
  },
}));

vi.mock('../axios', () => ({
  default: { post: (...args) => axiosInstancePost(...args) },
}));

vi.mock('axios', () => ({
  default: { put: (...args) => axiosPut(...args) },
  isCancel: () => false,
  CancelToken: { source: () => ({ token: 'cancel-token' }) },
}));

const { default: fileService } = await import('../fileService');

const makeImageFile = (overrides = {}) => ({
  name: 'photo.png',
  type: 'image/png',
  size: 1024,
  ...overrides,
});

describe('fileService', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    axiosInstancePost.mockReset();
    axiosPut.mockReset();
  });

  it('handles upload size limit errors without logging console errors', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    const result = fileService.handleUploadError(
      Object.assign(new Error('파일 크기는 5MB를 초과할 수 없습니다.'), {
        status: 413,
      })
    );

    expect(result).toEqual({
      success: false,
      message: '파일 크기는 5MB를 초과할 수 없습니다.',
    });
    expect(consoleError).not.toHaveBeenCalled();
  });

  describe('uploadFile (presigned URL 3단계)', () => {
    it('발급 → S3 직접 PUT → 확인 순서로 호출하고, 확인 응답을 기존 shape으로 반환한다', async () => {
      axiosInstancePost
        .mockResolvedValueOnce({
          data: {
            success: true,
            uploadUrl: 'https://bucket.s3.ap-northeast-2.amazonaws.com/chat/abc.png?X-Amz-Signature=1',
            key: 'chat/abc.png',
          },
        })
        .mockResolvedValueOnce({
          data: {
            success: true,
            message: '파일 업로드 성공',
            file: {
              _id: 'file-1',
              filename: 'abc.png',
              originalname: 'photo.png',
              mimetype: 'image/png',
              size: 1024,
              uploadDate: '2026-08-11T00:00:00.000Z',
            },
          },
        });
      axiosPut.mockResolvedValue({ status: 200 });

      const result = await fileService.uploadFile(makeImageFile(), vi.fn());

      expect(result.success).toBe(true);
      expect(result.data.file._id).toBe('file-1');
      expect(result.data.file.url).toContain('/api/files/view/abc.png');

      // 1) 발급은 인증된 axiosInstance로
      expect(axiosInstancePost).toHaveBeenNthCalledWith(
        1,
        expect.stringContaining('/api/files/presigned-upload'),
        { filename: 'photo.png', contentType: 'image/png', size: 1024 }
      );

      // 2) S3 PUT은 plain axios로, presigned uploadUrl에 파일을 그대로 보낸다
      expect(axiosPut).toHaveBeenCalledWith(
        'https://bucket.s3.ap-northeast-2.amazonaws.com/chat/abc.png?X-Amz-Signature=1',
        expect.anything(),
        expect.objectContaining({
          headers: {
            'Content-Type': 'image/png',
            'Cache-Control': 'private, max-age=240',
          },
        })
      );

      // 3) 확인은 기존 /api/files/upload와 같은 URL로, key를 포함한 JSON body로
      expect(axiosInstancePost).toHaveBeenNthCalledWith(
        2,
        expect.stringContaining('/api/files/upload'),
        { key: 'chat/abc.png', filename: 'photo.png', contentType: 'image/png', size: 1024 }
      );
    });

    it('발급 단계가 실패하면 S3 PUT을 시도하지 않는다', async () => {
      axiosInstancePost.mockResolvedValueOnce({
        data: { success: false, message: '허용되지 않는 파일 형식입니다.' },
      });

      const result = await fileService.uploadFile(makeImageFile());

      expect(result).toEqual({ success: false, message: '허용되지 않는 파일 형식입니다.' });
      expect(axiosPut).not.toHaveBeenCalled();
    });
  });
});
