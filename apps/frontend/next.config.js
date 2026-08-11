const path = require('path');

const workspaceRoot = path.join(__dirname, '../..');
const additionalDevOrigins = (process.env.DEV_ALLOWED_ORIGINS || '')
  .split(',')
  .map((origin) => origin.trim())
  .filter(Boolean);
const cdnAssetPrefix = (process.env.CDN_ASSET_PREFIX || '').replace(/\/$/, '');

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // 같은 LAN의 다른 기기에서 dev 서버(/_next 자산·HMR)에 접근하도록 허용한다. dev 전용이고
  // localhost는 Next가 항상 허용하므로 이 목록이 로컬 접속에 영향을 주지 않는다.
  // 패턴은 점 단위로 매칭돼서 사설망 IP는 네 칸을 다 써야 한다 — '192.168.*'는 매칭되지 않는다.
  allowedDevOrigins: [
    '192.168.*.*',
    '10.*.*.*',
    ...additionalDevOrigins
  ],
  transpilePackages: ['@vapor-ui/core', '@vapor-ui/icons'],
  turbopack: {
    root: workspaceRoot
  },
  // Docker 빌드를 위한 standalone 출력 모드 (개발 환경에는 영향 없음)
  output: 'standalone',
  // CI의 CDN 업로드 빌드와 Docker 빌드는 별도로 실행된다. 두 빌드가 동일한
  // /_next/static/<build-id> 경로를 만들도록 커밋 SHA를 공통 build ID로 사용한다.
  ...(process.env.DEPLOY_BUILD_ID && {
    generateBuildId: async () => process.env.DEPLOY_BUILD_ID
  }),
  // monorepo에서 standalone 빌드 시 중첩 경로 방지
  outputFileTracingRoot: workspaceRoot,
  // 정적 자산(_next/static)을 CDN(CloudFront → S3)에서 서빙하는 배포에서만 적용한다.
  // CDN_ASSET_PREFIX가 설정된 경우에만 켠다 — NODE_ENV==='production'으로는 구분할 수
  // 없다: `next build`는 어떤 스크립트로 부르든 NODE_ENV를 항상 production으로 강제해서,
  // Docker 빌드(apps/frontend/Dockerfile이 CDN 업로드 없이 plain `next build`만 실행하고
  // 컨테이너가 자체적으로 정적 자산을 서빙함)에서도 그 조건이 참이 되어 실제로는 업로드
  // 안 된 CDN URL이 그대로 박혀 모든 JS/CSS 청크가 404 나는 사고가 났었다.
  // CDN 배포 CI와 Docker 빌드에는 같은 CDN_ASSET_PREFIX를 전달해야 한다. 그래야
  // 컨테이너가 렌더링한 HTML도 CI가 업로드한 /_next/static 자산을 참조한다.
  //
  // package.json의 deploy:cdn은 파일명이 콘텐츠 해시라 겹쳐쓰기 걱정이 없어 --delete
  // 없이 업로드만 한다 — 배포 직전에 옛 HTML을 이미 받은 사용자가 옛 해시의 청크를
  // 여전히 참조할 수 있어서, 배포 순간 즉시 지우면 그 사용자들이 깨진다. 옛 자산 정리는
  // 별도 수명주기(S3 lifecycle) 정책으로 안전한 기간 뒤에 한다.
  assetPrefix: cdnAssetPrefix || undefined
};

module.exports = nextConfig;
