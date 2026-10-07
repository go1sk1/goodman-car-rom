# 검토한 GSI 패치 조정

기준 patch set: `MisterZtr/LineageOS_gsi`의 `7bf212f449d1aa253ebc97b1ab3c4924be88effa`.
기준 device source: `TrebleDroid/device_phh_treble`의 `91031960bf9f5ccf4e0af412db12138b7e26bd5b`.

`trebledroid-staging/platform_device_phh_treble/0002-guard-goodix-sysfs-write-behind-existence-check.patch`:
upstream 패치는 Goodix 설정이 파일의 마지막 부분일 때 생성됐지만, 고정 device 커밋에는 이후 Samsung Codec2 seccomp 수정이 추가되어 `git apply --check`가 실패했습니다. 원래 패치의 존재 확인 동작을 유지하면서 Samsung 코드 문맥에 맞춘 대체 패치입니다. Samsung 코드를 삭제하지 않습니다.

적용 도구는 동일 상대 경로의 조정 패치를 우선 적용하고, 실제 적용한 내용의 SHA256을 `goodman-patches.json`에 기록합니다. 나머지 충돌은 자동으로 무시하지 않습니다.
