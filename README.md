# Goodman Car GSI

LineageOS 23.2 ARM64 GAPPS EXT4 development GSI for vehicle mirroring.

Targets: SM-G991N / SM-N976N mirror phones, S25+ main phone, K5 2024 ccNC.

## Status

Two development image builds succeeded before the original HDD partition became inaccessible. Their phone boot and vehicle behavior were not verified. A fresh SSD build is running with four parallel compile jobs, followed by a dependent build that adds signed ROM download support.

HFP call-control, legacy USB projection, audio/GPS routing, app pairs, media notices, autoplay and assistant launchers have source implementations. Latest ccNC compatibility, wireless setup, bidirectional HFP audio and real-device tests remain incomplete.

## Updates

See [updates/README.md](updates/README.md). The phone downloads signed split Release assets, joins them into a system image, and checks every part plus the entire image. Installation remains a user operation in TWRP. Automatic OTA flashing is not implemented. No image Release has been published yet.

## Build

Use the pinned manifest in `gsi/source-lock.xml`, scripts under `gsi/`, and the custom source installers under `tools/`. External Android/LineageOS/TrebleDroid sources and Google components are fetched separately. Building requires Linux, substantial storage, RAM and swap.

This repository contains custom source modules and build tools. It excludes nMirror2 binaries, private signing keys, phone certificates, tokens, personal logs and built images. Source license notices and third-party licenses remain applicable.
