# BLE protocol v1

Android 클라이언트/서버가 실제로 교환하는 프로토콜입니다. 공개 서비스 UUID에는 코드나 코드의 해시를 포함하지 않습니다.

## 특성

서비스: `442faa80-96ba-4c06-b311-0bb4290d1000`

| 특성 UUID 끝자리 | 속성 | 값 |
|---|---|---|
| 1001 CHALLENGE | read | version(1) + 서버의 무작위 nonce(16) |
| 1002 IDENTITY | read/write | read: 서버 설치 UUID(16), write: 클라이언트 설치 UUID(16) |
| 1003 CONTROL | write with response | opcode(1) + HMAC 태그(16) |
| 1004 STATUS | read | status(1) + 서버 증명 태그(16), 미인증/만료 시 거절(1) |

모든 값은 17바이트 이하로 기본 ATT MTU 23에서도 전송됩니다. 작업은 read/write 콜백에 따라 하나씩 순서대로 실행합니다. prepared/offset write는 허용하지 않습니다.

## 인증과 요청

1. 두 기기가 사용자가 입력한 코드를 PBKDF2-HMAC-SHA256(고정된 v1 도메인 salt, 120,000회, 256비트)로 변환합니다.
2. 서버는 연결마다 새 128비트 무작위 nonce를 만들고 30초 후 세션을 만료합니다.
3. 클라이언트는 서버 ID/nonce를 읽고 자신의 설치 ID를 씁니다.
4. 클라이언트가 AUTH(1)와 `HMAC(key, domain("client") || nonce || serverId || clientId)[:16]`을 씁니다.
5. 서버는 기존 기기이거나 2분 등록 창이 열려 있을 때만 인증하고 저장합니다.
6. 클라이언트는 STATUS의 `HMAC(key, domain("server/1") || nonce || serverId || clientId)[:16]`을 검증하여 서버도 코드를 아는지 확인합니다.
7. REQUEST(2)와 별도 `domain("request")` 태그를 보냅니다. 세션당 한 번만 수락합니다.
8. 서버는 USER_ACTION_REQUIRED(2)와 `domain("server/2")` 태그로 응답합니다. 폰에서 핫스팟을 켜야 한다는 의미이며 핫스팟이 이미 켜졌다는 의미가 아닙니다.
9. 클라이언트는 응답을 검증한 뒤 GATT를 닫습니다.

domain의 실제 문자열은 `Hiru/v1/<purpose>`의 ASCII 바이트입니다. UUID는 big-endian 128비트입니다.
서버는 최대 4개 세션, 인증 실패 5회 후 전역 30초 대기, 요청 알림은 최소 10초 간격으로 제한합니다.

## 보안 범위

코드 원문을 저장하거나 전송하지 않습니다. 파생 키는 Android Keystore AES-GCM으로 암호화해 보관하고 앱 백업은 비활성화합니다. 태그는 상수 시간 비교를 사용하며 역할·설치 ID·nonce·응답 상태를 바인딩합니다.

이 방식은 PAKE가 아닙니다. 캡처된 인증 트래픽으로 코드의 오프라인 추측을 시도할 수 있으므로 예측하기 어려운 코드를 사용해야 합니다. 같은 코드를 아는 기기들은 같은 신뢰 그룹입니다. 앱 내부 인증이며 Bluetooth 시스템 페어링을 대체하는 전송 암호화가 아닙니다. Wi-Fi 비밀번호나 인터넷 트래픽은 BLE로 전송하지 않습니다. 민감한 추가 기능을 구현하기 전에는 검토된 보안 프로토콜/전송 암호화를 도입해야 합니다.
