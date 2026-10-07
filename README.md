# Goodman Car GSI

Source-built LineageOS 23.2 ARM64 GAPPS EXT4 development GSI for vehicle mirroring.

Targets: Samsung SM-G991N / SM-N976N; main phone S25+; K5 2024 ccNC.

This repository contains custom modules and reproducible build tools. It does not contain nMirror2 binaries, signing private keys, device credentials, or a completed production ROM.

Previously built development images passed file-system/package checks. Phone boot, ccNC mirroring, wireless connection and HFP bidirectional audio remain unverified. Current SSD build includes app pairs, media controls and assistant launcher sources. A dependent build adds signed ROM download support.

ROM updates: signed release manifest, split image downloads, per-part and whole-image SHA256 verification, then user installation in TWRP. Automatic OTA flashing is not implemented.
