# 폰에서 ROM 업데이트 다운로드

차량 설정 → ROM 업데이트에서 배포 정보를 확인하고 이미지를 다운로드합니다.
GitHub Release의 `update.json`과 `update.sig`를 ROM에 포함된 공개 키로 검증합니다.
각 분할 파일은 1.5 GiB 이하이며, 폰은 다운로드 폴더의 하나의 이미지로 합치면서
각 파일과 전체 이미지의 SHA256·크기·EXT4 표식을 확인합니다.
실패하거나 취소하면 미완성 파일을 삭제합니다. 앱 화면을 닫아도 foreground service가 다운로드를 이어갑니다.

완료 파일: `Download/GoodmanCar/GoodmanCar-<build>-system.img`.
설치는 데이터 백업과 기기별 설치 조건 확인 후 TWRP에서 사용자가 진행합니다.
자동 플래싱, A/B OTA, 다운로드 재개, 현재 설치 버전과의 자동 신구 비교는 아직 지원하지 않습니다.
실제 폰의 다운로드·파일 접근·TWRP 설치 검증은 남아 있습니다.

배포 준비:

1. 첫 ROM에 `assets/rom-update.json`의 저장소 주소와 공개 키를 포함합니다.
2. 검증된 이미지가 생성되면 `tools/package-rom-update.py`로 Release 파일을 만듭니다.
3. 저장소에 동일한 build 이름의 Release를 만들고 모든 분할 파일과 서명된 정보를 업로드합니다.
4. 개발 버전을 최신 Release로 노출할지 명시적으로 결정합니다. 폰은 `releases/latest/download`를 사용합니다.

개인 서명 키는 프로젝트 외부에 보관하고 백업해야 합니다. 키를 잃으면 이미 배포한 ROM의 신뢰 키를
새 이미지로 교체해야 하며, GitHub에 개인 키·폰 인증서·로그인 토큰을 올리면 안 됩니다.
설정 저장소는 `go1sk1/goodman-car-rom`이며, 해당 저장소와 첫 Release를 게시하기 전에는 서버에서 다운로드할 파일이 없습니다.

현재 실행 중인 소스 트리는 수정하지 않았습니다. 이 기능은 다음 빌드 반영 대상입니다.
