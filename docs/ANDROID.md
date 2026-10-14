# Android 0.1 — 실제 BLE 등록·요청 앱

## 개발 환경

- Kotlin 2.1.20, Jetpack Compose, Material 3
- JDK 17, Android SDK 35, Gradle 8.11.1, AGP 8.9.1
- 최소 Android 8.0(API 26), target SDK 35
- `com.hiru.instanthotspot`: 인터넷 제공/연결 역할을 한 APK에 포함
- Android Studio에서 `android/` 디렉터리를 열면 됩니다.

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

테스트용 APK: `app/build/outputs/apk/debug/app-debug.apk`.
GitHub Actions도 같은 명령으로 검사하고 디버그 APK를 아티팩트로 보관합니다.
디버그 APK는 개발용 서명을 사용하며, 배포용 서명키나 비밀정보는 저장소에 포함하지 않습니다.

## 두 기기로 사용하기

1. 같은 APK를 폰과 태블릿에 설치합니다.
2. 폰에서 **인터넷 제공**을 선택하고 직접 만든 10자리 코드를 입력합니다. ASCII 대문자, 소문자, 숫자, 특수기호가 모두 필요합니다.
3. 안내되는 Bluetooth/근처 기기 권한을 허용합니다. 알림 권한을 허용하면 연결 요청을 알림창에서도 볼 수 있습니다.
4. 폰은 요청 대기를 시작하며, 초기 2분 동안 새 기기 등록을 허용합니다. 이후에는 **기기 등록**을 눌러 다시 열 수 있습니다.
5. 태블릿에서 **인터넷 연결**을 선택하고 **동일한 코드**를 입력합니다. Android 8에서는 위치 권한과 시스템 위치 서비스가 BLE 검색에 필요합니다.
6. 검색된 Hiru 폰을 선택하면 실제 GATT 연결, 상호 인증, 기기 등록, 핫스팟 요청을 수행합니다.
7. **이번 버전에서는 폰에서 요청 알림/화면을 확인하고 핫스팟을 직접 켜야 합니다.** 앱의 '핫스팟 설정 열기' 버튼을 사용할 수 있습니다.
8. 태블릿의 'Wi-Fi 설정 열기'를 눌러 폰의 핫스팟에 연결합니다. Android 8 태블릿과 호환되는 주파수/보안 모드(예: 2.4 GHz, WPA2)를 폰에서 선택합니다.

핫스팟 요청 수신을 '인터넷 연결 성공'으로 표시하지 않습니다. Wi-Fi의 인터넷 검증 상태도 요청한 폰의 SSID와 동일하다는 의미는 아니므로 별도로 안내합니다.

## 구현 범위와 제한

| 항목 | 동작 |
|---|---|
| UI | 연한 하늘색/흰색, 반응형 2열 카드, 폰 하단 내비게이션/태블릿 레일, Lucide 벡터 |
| 등록 | 같은 코드로 인증, 폰의 2분 등록 창이 열려 있을 때 새 설치 ID만 등록 |
| 검색 | 앱 서비스 UUID로 필터링, balanced 모드, 10초 제한, 화면을 벗어나면 중단 |
| 폰 대기 | 저전력 BLE 광고와 GATT 서버, connectedDevice 포그라운드 서비스 |
| 요청 | 인증된 요청을 받고 폰 화면·알림에 표시, 응답 확인 후 클라이언트 BLE 연결 종료 |
| 기기 관리 | 기기 목록, 등록 해제, 코드/역할 변경 시 기존 등록 초기화 |
| 자동 핫스팟 | 미구현. Android 15 일반 앱의 공개 API 제약 때문에 설정 화면으로 안내 |
| 자동 Wi-Fi 연결 | 미구현. Wi-Fi 설정을 열어 사용자가 연결 |
| iOS | 이 PR에는 포함하지 않음 |

앱을 강제 종료하거나 OS/제조사 절전 관리가 프로세스를 종료하면 대기가 중지될 수 있습니다. 부팅 시 자동 시작이나 절전 예외 권한은 요청하지 않습니다. 앱에서 '대기 시작'을 다시 누르세요. BLE 광고를 지원하지 않는 기기는 연결 역할로 사용해야 합니다.

## 권한

| 권한 | 사용 범위 |
|---|---|
| BLUETOOTH / BLUETOOTH_ADMIN | Android 11 이하, 일반 권한 |
| ACCESS_FINE_LOCATION | Android 11 이하의 BLE 검색, 런타임 권한 |
| BLUETOOTH_SCAN | Android 12 이상 클라이언트 검색, neverForLocation |
| BLUETOOTH_CONNECT | Android 12 이상 GATT 통신 |
| BLUETOOTH_ADVERTISE | Android 12 이상 폰의 광고 |
| FOREGROUND_SERVICE / FOREGROUND_SERVICE_CONNECTED_DEVICE | 폰의 요청 대기 유지 |
| POST_NOTIFICATIONS | Android 13 이상 요청 알림, 거부해도 앱 내 대기는 가능 |
| ACCESS_NETWORK_STATE | 태블릿의 검증된 Wi-Fi 인터넷 연결 여부 확인 |

인터넷 권한, Wi-Fi 변경, 위치 백그라운드, 접근성, 루트, WRITE_SETTINGS, TETHER_PRIVILEGED는 요청하지 않습니다.

## 실기기 확인표

- [ ] Android 15 폰 ↔ Android 8 태블릿에 동일 APK 설치
- [ ] 권한 거부/재허용, Bluetooth 꺼짐, 위치 서비스 꺼짐 안내
- [ ] 코드가 다르면 인증 거절, 코드 원문 미전송
- [ ] 폰의 등록 창이 닫혀 있으면 신규 기기 등록 거절
- [ ] 등록된 기기는 등록 창이 닫혀도 요청 가능
- [ ] 화면 끄기/앱 전환 상태에서 폰의 요청 수신과 알림
- [ ] 검색 10초 제한, 검색 취소, 범위 이탈/연결 시간 초과 후 재시도
- [ ] 폰의 수동 핫스팟 켜기 → 태블릿의 Wi-Fi 연결 → 인터넷 확인
- [ ] 기기 등록 해제/코드 변경, 프로세스 종료 후 재시작
- [ ] 8시간 대기 소모량과 요청 20회 성공률/발견 시간 측정

실기기에서 확인하기 전에는 위 항목을 완료 처리하지 않습니다.

## 출처

- [Android BLE 백그라운드 통신](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [Android Bluetooth 권한](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Lucide](https://lucide.dev/) — ISC/MIT 고지는 `android/app/src/main/assets/lucide-LICENSE.txt`에 포함
