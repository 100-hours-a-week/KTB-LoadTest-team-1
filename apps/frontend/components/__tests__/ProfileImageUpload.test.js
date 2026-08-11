import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const apiPost = vi.fn();
const apiDelete = vi.fn();
const axiosPut = vi.fn();
const saveStoredUser = vi.fn();

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'user-1', token: 'token-1', name: '테스트', profileImage: '' },
  }),
}));

vi.mock('@/components/CustomAvatar', () => ({
  default: () => React.createElement('div', { 'data-testid': 'avatar-stub' }),
}));

vi.mock('@/components/Toast', () => ({
  Toast: { success: vi.fn(), error: vi.fn() },
}));

vi.mock('@/lib/api/client', () => ({
  default: {
    post: (...args) => apiPost(...args),
    delete: (...args) => apiDelete(...args),
  },
}));

vi.mock('@/lib/auth/authStorage', () => ({
  saveStoredUser: (...args) => saveStoredUser(...args),
}));

vi.mock('axios', () => ({
  default: { put: (...args) => axiosPut(...args) },
}));

const { default: ProfileImageUpload } = await import('../ProfileImageUpload');

const makeImageFile = () => new File(['binary'], 'avatar.png', { type: 'image/png' });

describe('ProfileImageUpload', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('발급 → S3 직접 PUT → 확인(기존 /api/users/profile-image URL) 순서로 업로드한다', async () => {
    apiPost
      .mockResolvedValueOnce({
        data: {
          success: true,
          uploadUrl: 'https://bucket.s3.ap-northeast-2.amazonaws.com/profiles/avatar.png?X-Amz-Signature=1',
          key: 'profiles/avatar.png',
        },
      })
      .mockResolvedValueOnce({
        data: {
          success: true,
          message: '프로필 이미지가 업데이트되었습니다.',
          imageUrl: '/api/files/profiles/avatar.png',
        },
      });
    axiosPut.mockResolvedValue({ status: 200 });

    const onImageChange = vi.fn();
    render(React.createElement(ProfileImageUpload, { currentImage: '', onImageChange }));

    const input = screen.getByTestId('profile-image-file-input');
    fireEvent.change(input, { target: { files: [makeImageFile()] } });

    await waitFor(() => expect(onImageChange).toHaveBeenCalledWith('/api/files/profiles/avatar.png'));

    expect(apiPost).toHaveBeenNthCalledWith(1, '/api/users/presigned-upload/profile-image', {
      filename: 'avatar.png',
      contentType: 'image/png',
      size: expect.any(Number),
    });

    expect(axiosPut).toHaveBeenCalledWith(
      'https://bucket.s3.ap-northeast-2.amazonaws.com/profiles/avatar.png?X-Amz-Signature=1',
      expect.anything(),
      expect.objectContaining({ headers: { 'Content-Type': 'image/png' } })
    );

    expect(apiPost).toHaveBeenNthCalledWith(2, '/api/users/profile-image', {
      key: 'profiles/avatar.png',
      filename: 'avatar.png',
      contentType: 'image/png',
      size: expect.any(Number),
    });

    expect(saveStoredUser).toHaveBeenCalledWith(
      expect.objectContaining({ profileImage: '/api/files/profiles/avatar.png' })
    );
  });

  it('발급 단계가 실패하면 에러를 표시하고 S3 PUT을 시도하지 않는다', async () => {
    apiPost.mockResolvedValueOnce({
      data: { success: false, message: '이미지 파일만 업로드할 수 있습니다.' },
    });

    render(React.createElement(ProfileImageUpload, { currentImage: '', onImageChange: vi.fn() }));

    const input = screen.getByTestId('profile-image-file-input');
    fireEvent.change(input, { target: { files: [makeImageFile()] } });

    await waitFor(() =>
      expect(screen.getByText('이미지 파일만 업로드할 수 있습니다.')).toBeInTheDocument()
    );
    expect(axiosPut).not.toHaveBeenCalled();
  });
});
