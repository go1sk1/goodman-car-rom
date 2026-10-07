# 차량 전송 개발 소스

2026-10-06: 프로젝트 소스에서 `GoodmanCarBridge` 서비스와 USB 연결 화면에 이 모듈을 연결했습니다. 현재 실행 중인 기본 이미지 빌드에는 아직 반영되지 않았으며, 완료 후 별도의 증분 빌드로 적용합니다. ccNC에서의 실제 차량 투사 기능 완성을 뜻하지 않습니다.

## 작성된 처리

- 후속 소스에 차량 기본 화면 전환과 미러링 복귀 버튼을 연결했습니다. 영상 포커스 요청의 모드 필드를 2번 필드로 수정했고, 명령 전송 시점의 로컬 콜백을 차량 응답으로 오인하지 않도록 분리했습니다. 3초 내 응답이 없으면 전환 성공으로 표시하지 않습니다. [원본 요청 스키마](https://github.com/f1xpl/aasdk/blob/master/aasdk_proto/VideoFocusRequestMessage.proto)를 기준으로 작성했으며 실제 ccNC 홈 화면 진입은 미검증입니다.

- `AaWire`: USB/무선 공통 프레임, 암호화된 조각별 복호화, 메시지 재조립. 프레임 65,535바이트, 메시지 4MiB, 미완성 메시지 합계 8MiB로 제한합니다.
- `UsbAccessoryLink`: 사용자가 허용한 USB 액세서리의 양방향 입출력과 해제.
- `BluetoothSetupLink`: 선택한 페어링 차량의 AA RFCOMM 서비스 연결. S25+ HFP 연결 대상은 변경하지 않습니다.
- `NetworkProjectionLink`: 선택한 Wi-Fi의 Network에 소켓을 묶습니다. 무선 설정용 외부 TLS 적용도 지원합니다.
- `WifiSetupWire`: RFCOMM/WPP용 길이·메시지 형식 처리. AP 암호 등 데이터는 로그에 출력하지 않습니다.
- `AaTls`, `ProjectionIdentity`: Android Keystore의 개별 RSA 키와 사용자가 등록한 차량 인증서 해시로 TLS 1.2 상호 인증. 다른 제품의 인증서 개인키를 포함하지 않습니다.
- `AaSession`: 초기 버전 교환 → TLS → 인증 완료 → 서비스 탐색. 현재 명시적으로 구현한 범위는 legacy GAL 1.1입니다. 더 높은 버전이 필수인 차량은 연결을 거절하며, 해당 버전을 지원한다고 응답하지 않습니다.
- `ServiceCatalog`: 차량이 광고한 채널·PCM 형식 해석. 차량별 채널 번호를 고정하지 않습니다.
- `VehicleInputChannel`, `ButtonDecoder`: 채널 개방, 광고된 버튼 등록, 버튼 코드·누름/뗌·길게 누름 수신.
- `TouchDecoder`: 화면 범위·포인터 ID·누름/이동/뗌 순서를 확인해 터치 데이터를 해석합니다. 아래 Android 입력 브리지에 연결했습니다.
- `AvOutput`: PCM/H.264 출력 채널 개방·설정·시작, 수신 확인에 따른 전송량 제한.
- `VehicleFocus`: 차량의 오디오·화면 사용 허가와 회수 처리. 출력은 포커스 허가를 받은 뒤에만 전달합니다.
- `MicrophoneChannel`: 마이크 형식 협상, 요청 시에만 PCM 전달, 수신 확인, 요청 종료 후 늦게 도착한 데이터 폐기. 마이크 응답 형식은 차량 프로필에서 명시적으로 선택해야 합니다.
- `VehicleGnssChannel`: 차량이 광고한 GPS 센서 구독, 좌표와 품질 필드 수신, 수신 장애 통지. 단위와 측정 시간은 차량 프로필을 확인한 뒤 Android 위치로 변환합니다.
- `ProjectionSender`: 제어 메시지 우선 처리, 제한된 대기열, 5초 쓰기 제한 및 연결 해제.

## Android 연결 작업본 (2026-10-06)

`integration/kr/goodman/carbridge/`를 filegroup으로 노출해 앱의 Java 소스로 컴파일하도록 연결했습니다. 아래 코드는 후속 증분 빌드 대상입니다.

- `ProjectionCoordinator`: 서비스가 영상·입력·오디오·마이크 채널과 `VehicleRuntime` 토큰을 함께 관리합니다. 실제 형식 협상과 포커스 허가 후에만 지원 채널을 Android 오디오 정책에 노출합니다. 연결 해제 시 입력 취소, 캡처 중지, 마이크 종료, 오디오/위치 복귀 순서로 정리합니다.
- `UsbProjectionController`: 선택한 USB 액세서리의 사용자 권한 확인, 분리 감지, 인증서 등록, 재연결을 담당합니다. 처음 보는 인증서는 지문을 수집한 뒤 TLS 연결을 거절합니다. 사용자가 선택한 차량을 등록한 이후의 연결만 해당 인증서로 진행합니다. 인증서가 바뀌면 자동으로 신뢰하지 않습니다.

- `ProjectionInputBridge`: 인증된 입력 채널의 콜백을 메인 스레드로 전달합니다. 프로토콜 프로필에서 지정한 통화·음성 버튼만 `VehicleRuntime`에 연결하며, 미확인 버튼은 기록만 합니다. 대기열은 32개, 입력 유효 시간은 500ms로 제한하고 누락된 버튼 해제·연결 종료 시 예약된 동작을 취소합니다.
- `VehicleTouchInjector`: 협상된 영상 크기와 기본 화면 사이의 중앙 정렬 비율로 좌표를 변환합니다. 검은 여백의 첫 터치는 버리고 다중 터치의 포인터 번호를 유지합니다. 포커스 회수·잠금·화면 꺼짐·회전·연결 종료 시 CANCEL을 보냅니다. Android 입력 주입에는 앱 Manifest의 `android.permission.INJECT_EVENTS` 선언이 필요합니다.
- `ProjectionVideoBridge`: `ScreenEncoder`와 협상된 AVC `AvOutput`을 연결합니다. 차량이 영상 포커스를 허용했을 때 캡처를 시작하고 회수하면 중단합니다. 전송량 제한으로 프레임을 놓치면 다음 키프레임까지 예측 프레임을 버려 깨진 영상을 이어 보내지 않습니다. 추가 영상 대기열은 만들지 않습니다.

입력 브리지에는 영상에 대응하는 실제 터치스크린 채널만 연결해야 합니다. 터치패드 입력이나 영상과 다른 좌표계는 지원한다고 가정하지 않습니다. 차량 버튼의 원시 값도 Android 키코드와 같다고 가정하지 않습니다. 세션 소유자는 `VehicleRuntime` 토큰 교체 전에 두 브리지를 닫아야 합니다.

앱 서비스·USB 선택/등록 화면·Manifest 권한을 포함한 전체 Java 소스의 Android 16 API 컴파일은 통과했습니다. 화면 전송, 입력 주입, ccNC에서의 동작은 아직 실행해 확인하지 않았습니다.

## 이어서 연결할 부분

최신 ccNC용 버전 협상, WPP 메시지 의미 처리·Wi-Fi 참가, ccNC의 마이크 응답 형식, GPS 단위/시간 해석이 남아 있습니다. 현재 세션은 legacy GAL 1.1 경로만 사용하며, 확인되지 않은 GPS 데이터는 공급하지 않아 폰 GPS가 유지됩니다. 이 단계가 완료되기 전까지 실제 미러링·마이크·GPS 지원 완료로 표시하지 않습니다.

legacy 프로필은 SPEECH=1, SYSTEM=2, MEDIA=3만 기존 설정에 연결합니다. ALARM=4를 통화로 취급하지 않습니다. 전용 통화 출력과 HFP 양방향 음성은 아직 지원 완료가 아니며 실제 HAL/차량 경로 작업이 필요합니다.

## 참고 자료

- [aasdk 원본 프레임 구현](https://github.com/f1xpl/aasdk/tree/046b3b3/src/Messenger)
- [aasdk 제어 채널](https://github.com/f1xpl/aasdk/blob/046b3b3/src/Channel/Control/ControlServiceChannel.cpp)
- [Open Android Auto 프로토콜 자료](https://github.com/mrmees/open-android-auto/tree/61eab61c5f9968154ff1a80faa8c0a427b208479)

참고 저장소의 소스는 `upstream/`에 별도로 보관하며 제품 모듈에 그대로 복사하지 않습니다. 문서 사이에 제어 비트, 인증 완료의 암호화 여부, 서비스 탐색 방향, FPS 값의 불일치가 있어 legacy 경로에서는 aasdk의 실제 송수신 구현을 기준으로 했습니다. 실제 ccNC 호환 여부는 아직 확인되지 않았습니다.

## 빌드 확인 범위

JDK 21 및 Android 16 API 아카이브로 Java 컴파일을 확인했습니다. 이번 작업에서는 반복 시나리오 검사나 기기 조작을 수행하지 않았습니다. TLS 왕복, USB·Wi-Fi 연결 및 실제 영상/음성 전송 성공을 확인한 것은 아닙니다.
