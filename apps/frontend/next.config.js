const path = require('path');

const workspaceRoot = path.join(__dirname, '../..');
const additionalDevOrigins = (process.env.DEV_ALLOWED_ORIGINS || '')
  .split(',')
  .map((origin) => origin.trim())
  .filter(Boolean);

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
  // monorepo에서 standalone 빌드 시 중첩 경로 방지
  outputFileTracingRoot: workspaceRoot,
  // 정적 자산(_next/static)을 CDN(CloudFront → ktb-chat-nextjs-assets 버킷)에서 서빙한다.
  // dev 서버는 이 CDN에 자산을 올리지 않으므로 production 빌드에서만 적용한다.
  assetPrefix: process.env.NODE_ENV === 'production'
    ? 'https://d27gb4vxgiasxt.cloudfront.net'
    : undefined
};

module.exports = nextConfig;
