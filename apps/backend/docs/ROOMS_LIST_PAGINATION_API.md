# `GET /api/rooms` 페이지네이션 API 변경 명세

## 무엇이 바뀌었나

기존에는 파라미터 없이 방 전체를 한 번에 다 내려줬다. 이제 페이지 단위로 잘라서 내려준다.
**파라미터를 안 보내도 동작한다**(기본값 `page=0&size=30`) — 기존 프론트 호출부는 코드 수정 없이 그대로 동작한다.

## 요청

```
GET /api/rooms?page={page}&size={size}
Authorization: Bearer {token}
```

| 파라미터 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `page` | int | `0` | 0부터 시작하는 페이지 번호 |
| `size` | int | `30` | 페이지당 방 개수 |

정렬은 항상 최신 생성순(`createdAt` 내림차순) 고정이며 변경 파라미터는 없다.

## 응답

```json
{
  "success": true,
  "data": [
    {
      "_id": "60d5ec49f1b2c8b9e8c4f2a1",
      "name": "프로젝트 논의방",
      "hasPassword": false,
      "creator": { "id": "...", "name": "...", "email": "...", "profileImage": null },
      "participants": [ { "id": "...", "name": "...", "email": "...", "profileImage": null } ],
      "participantsCount": 1,
      "isCreator": true,
      "recentMessageCount": 23,
      "createdAt": "2026-08-11T11:52:52.433Z"
    }
  ],
  "metadata": {
    "page": 0,
    "pageSize": 30,
    "hasMore": true,
    "currentCount": 1
  }
}
```

`data` 배열의 각 항목 구조(`RoomResponse`)는 이전과 동일 — 바뀐 건 몇 개가 담겨 오느냐뿐이다.

### `metadata` 필드

| 필드 | 설명 |
|---|---|
| `page` | 요청한(또는 기본) 페이지 번호 |
| `pageSize` | 요청한(또는 기본) 페이지 크기 |
| `hasMore` | 다음 페이지가 더 있는지 여부 |
| `currentCount` | 이번 응답의 `data` 개수 |

**제거된 필드**: `total`, `totalPages`, `sort` — 프론트/e2e 어디서도 참조하지 않아 제거했다. 전체 방 개수가
필요해지면 별도로 알려달라(카운트 쿼리를 다시 추가해야 해서 비용이 든다).

## 다음 페이지를 어떻게 가져오나

`hasMore: true`인 동안 `page`를 1씩 증가시키며 같은 방식으로 요청하면 된다. 무한 스크롤/"더 보기" 버튼
구현 시 이 값만 보면 된다.

## 호환성

- 파라미터 없이 호출 시 기존과 동일하게 200 OK + 방 목록을 받는다(다만 30개 넘게 있으면 최신 30개만).
- `e2e`/Artillery 시나리오는 목록의 특정 개수나 전체 여부에 의존하지 않아 별도 수정 불필요(확인 완료).
