# instant-hotspot
인스턴트 핫스팟 — BLE 기반 기기 연결 및 핫스팟 요청 앱. Android: Kotlin + Jetpack Compose / iOS: Swift + SwiftUI.

최소 안드로이드 버전 8

## Android 앱

`android/`를 Android Studio에서 열면 Kotlin + Jetpack Compose 프로젝트를 실행할 수 있습니다.
폰과 태블릿에 같은 APK를 설치하고 **인터넷 제공 / 인터넷 연결** 역할을 선택합니다.

- 흰색·연한 하늘색 UI와 Lucide 벡터 아이콘, 태블릿 반응형 레이아웃
- 10자리 등록 코드, BLE GATT 검색·상호 인증·핫스팟 요청
- 저전력 광고와 알림이 표시되는 요청 대기 서비스
- 기기 관리, 등록 해제, 코드·역할 변경

**0.1 버전은 BLE 요청까지 구현되어 있습니다. 폰의 핫스팟 켜기와 태블릿의 Wi-Fi 연결은 시스템 설정에서 직접 진행합니다.**
실제 인터넷 공유가 시작됐다고 가정하거나 가짜 연결 성공/데이터 사용량을 표시하지 않습니다.

자세한 빌드·사용 방법과 실기기 확인표는 [Android 안내](docs/ANDROID.md), 인증 과정은 [BLE 프로토콜](docs/PROTOCOL.md)을 참고하세요.

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Swift + SwiftUI 기반 iOS 앱은 추후 별도 구현 예정입니다.
