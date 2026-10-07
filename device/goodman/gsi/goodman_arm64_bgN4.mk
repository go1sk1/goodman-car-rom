# LineageOS 23.2 / TrebleDroid ARM64, system-as-root, GAPPS, EXT4.
# Inherited product is supplied by the pinned, reviewed GSI patch set.
$(call inherit-product, device/phh/treble/lineage_arm64_bgN4.mk)
$(call inherit-product, vendor/goodman/config/car.mk)

PRODUCT_NAME := goodman_arm64_bgN4
PRODUCT_MODEL := Goodman Car GSI
PRODUCT_SYSTEM_PROPERTIES += ro.goodman.car.stage=development
