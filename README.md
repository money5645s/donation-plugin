# Chzzk Donation Bridge (Paper 26.2)

`donation.json`의 금액과 명령어를 연결한 Paper 26.2 플러그인입니다. 플러그인은 치지직의 비공식 수집기(별도 프로세스)가 전달한 후원 이벤트만 받습니다. 이 분리는 네이버 쿠키/세션 정보를 마인크래프트 서버에 보관하지 않으며, 치지직의 비공식 웹소켓 변경에도 플러그인이 영향을 덜 받게 합니다.

## 설치

1. Java 25로 빌드한 JAR를 Paper 26.2 서버의 `plugins` 폴더에 넣습니다.
2. 첫 실행 후 생성되는 `plugins/ChzzkDonationBridge/config.yml`에서 `api-token`을 긴 무작위 값으로 변경합니다.
3. 같은 폴더의 `donation.json`에 후원 금액별 콘솔 명령을 작성합니다. 명령 앞의 `/`는 있어도 되고 없어도 됩니다.
4. API를 로컬 이외에서 받으려면 `bind-address`를 변경하고, 방화벽/리버스 프록시를 설정합니다. 공개 포트에는 반드시 강한 토큰을 사용하세요.

`donation.json`은 이벤트가 들어올 때마다 다시 읽으므로, 서버 재시작 없이 규칙을 수정할 수 있습니다.

```json
{
  "rules": [
    {
      "amount": 1000,
      "commands": [
        "say {player}님이 {amount}원을 후원했습니다!",
        "give {player} diamond 1"
      ]
    }
  ]
}
```

사용 가능한 치환값: `{player}`, `{amount}`, `{donor}`, `{channelId}`.

## 플러그인 단독 치지직 수신

플러그인은 기본적으로 비공식 치지직 공개 채팅 웹소켓에 **직접** 연결합니다. 따라서 Paper 서버만 실행 중이어도 `/api/login`으로 연결한 채널의 일반 후원을 실시간 수신합니다.

- `/api/login` 직후 연결을 시작합니다.
- 서버를 재시작해도 `bindings.json`에서 채널 목록을 읽어 자동으로 다시 연결합니다.
- `/api/logout`으로 마지막 연결을 해제하면 해당 채널의 웹소켓도 닫습니다.
- 방송이 꺼져 있으면 30초마다 방송 상태를 확인하고, 방송이 시작되면 자동 연결합니다.
- 금액이 있는 후원은 `donation.json`의 금액 규칙을 실행하고, 구독·파티 이벤트는 `eventRules` 규칙을 실행합니다.

`config.yml`의 `chzzk.auto-connect`를 `false`로 바꾸면 기존 외부 수신 API 방식만 사용합니다. `chzzk.log-chat-events`를 `true`로 바꾸면 수신 채팅도 서버 콘솔에 표시합니다.

## 웹 대시보드

서버가 실행 중이면 `http://서버주소:8765/dashboard`에서 대시보드를 열 수 있습니다. 사용자는 온라인 인원·연결된 스트리머·실시간 채팅/후원 이벤트를 읽기 전용으로 볼 수 있습니다. 관리자는 대시보드에 `config.yml`의 `api-token`을 입력해 스트리머 연결과 후원 수신 설정을 관리합니다. 외부 기기에서 열려면 `bind-address`를 서버 IP 또는 `0.0.0.0`으로 설정하고 방화벽에서 포트를 허용해야 합니다.

게임 안에서는 `/api 대시보드`를 입력하면 클릭 가능한 대시보드 링크를 받습니다. 외부 플레이어에게 올바른 링크를 보내려면 `config.yml`의 `dashboard-url`에 `http://공인IP:8765/dashboard` 또는 도메인 주소를 설정하세요.

## API

마인크래프트 서버 콘솔 또는 OP는 HTTP 요청 없이 아래 명령을 직접 사용할 수 있습니다.

```text
/api 로그인 <마크닉네임> <치지직채널ID>
/api 후원테스트 <마크닉네임> [금액]
/api 후원설정 <마크닉네임> <후원유형> <true|false>
/api 로그아웃 <마크닉네임>
```

후원 유형은 `채팅후원`, `영상후원`, `미션후원`, `구독후원`, `파티후원`입니다. 새로 로그인한 플레이어는 채팅·영상 후원만 기본 수신합니다. 설정은 `plugins/ChzzkDonationBridge/steamer.json`에 저장됩니다.

구독·파티처럼 금액이 확정되지 않는 이벤트는 `donation.json`의 `eventRules`에서 명령을 설정합니다. 파티 이벤트를 같은 채널에 연결된 모든 플레이어에게 동시에 실행하려면 `config.yml`의 `chzzk.party-donation.share-with-linked-players: true`로 바꾸고, 각 플레이어의 `파티후원` 수신을 켜세요.

`GET /api/health`를 제외한 모든 요청에는 아래 헤더가 필요합니다.

```text
Authorization: Bearer <config.yml의 api-token>
Content-Type: application/json
```

| 목적 | 요청 | JSON 본문 |
|---|---|---|
| 로그인/연결 | `POST /api/login` | `{"minecraftName":"Steve","channelId":"치지직채널ID"}` |
| 로그아웃/연결 해제 | `POST /api/logout` | `{"minecraftName":"Steve"}` |
| 후원 테스트 | `POST /api/donation-test` | `{"minecraftName":"Steve"}` |
| 실제 후원 수신 | `POST /api/chzzk/donation` | `{"channelId":"치지직채널ID","amount":1000,"donorName":"후원자","eventId":"고유이벤트ID"}` |

사용자가 요청한 경로 형식도 지원합니다: `POST /api/login/Steve/<channelId>`, `POST /api/logout/Steve`, `POST /api/donation-test/Steve` (그리고 `/api/후원테스트/Steve`).

테스트 요청의 금액은 `config.yml`의 `test-amount`를 사용합니다. 본문에 `amount`를 넣으면 그 금액으로 특정 규칙을 테스트할 수 있습니다. `eventId`를 보내면 같은 이벤트가 재전송되어도 한 번만 실행됩니다.

예시:

```bash
curl -X POST http://127.0.0.1:8765/api/login \
  -H "Authorization: Bearer YOUR_SECRET" -H "Content-Type: application/json" \
  -d '{"minecraftName":"Steve","channelId":"CHANNEL_ID"}'

curl -X POST http://127.0.0.1:8765/api/chzzk/donation \
  -H "Authorization: Bearer YOUR_SECRET" -H "Content-Type: application/json" \
  -d '{"channelId":"CHANNEL_ID","amount":1000,"donorName":"후원자","eventId":"donation-123"}'
```

## 비공식 치지직 수집기 연결

수집기는 비공식 치지직 웹소켓에서 후원 이벤트를 얻은 뒤 위의 `POST /api/chzzk/donation`으로 전달하면 됩니다. 이 수신 형식은 일반적인 비공식 라이브러리의 `channelId`, `donatorNickname`, `payAmount`를 그대로 매핑할 수 있게 만들었습니다.

비공식 웹소켓은 쿠키 인증과 내부 프로토콜에 의존해 변경 또는 차단될 수 있습니다. 수집기는 스트리머 본인의 계정/채널 권한으로만 운용하고, 쿠키나 API 토큰을 `donation.json`·소스 코드·공개 저장소에 넣지 마세요.

## 실시간 수집 대시보드

`collector` 폴더와 `dashboard` 폴더는 채팅·원본 이벤트를 별도 화면에서 확인하고 싶을 때만 쓰는 선택 도구입니다. 일반 후원 실행에는 필요하지 않습니다.

[대시보드 시작 파일](start-chzzk-dashboard.bat)을 실행하면 수집기와 화면이 함께 실행됩니다.

[수집기 실행 및 이벤트 분류 안내](collector/README.md)를 확인하세요.
