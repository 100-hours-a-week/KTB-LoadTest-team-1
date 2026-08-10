# 11. JVM 힙 기본값이 작다

> **한줄 요약**: 힙이 `-Xmx1024m`로 고정돼 있다. 메시지마다 객체가 많이 생성되는 구조라 GC 압박이 커질 수 있다.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| 미착수 | GC 압박, 전체 처리량 | Medium |

## 문제

`apps/backend/Makefile:14` (`JVM_OPTS ?= -Xmx1024m`) — 배포 스크립트(`app-control.sh`)도 이 값을
그대로 쓴다. 메시지 1건 처리마다 여러 개의 응답/DTO 객체가 생성되는 구조라
([4번 항목](04-chatmessage-duplicate-session.md)) GC 압박이 상당할 수 있다.

## 조치 (미착수)

- 부하테스트 서버 스펙에 맞춰 힙 크기를 올린다.
- GC 로그(`-Xlog:gc`)를 켜서 Grafana JVM
  대시보드(`monitoring/grafana/provisioning/dashboards/spring-boot-app-dashboard.json`)로
  GC pause time을 같이 관찰하는 걸 권장한다.
