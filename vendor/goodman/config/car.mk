# Source-built car feature set, inherited by the final reviewed device product.
PRODUCT_PACKAGES += GoodmanCarBridge

# The mirror phone acts as HF; the S25+ remains the Audio Gateway.
# Mandatory in our GSI; conflicting non-optional assignments must fail the build.
PRODUCT_SYSTEM_PROPERTIES += bluetooth.profile.hfp.hf.enabled=true
# Set only with our reviewed framework GNSS wrapper installed by the source update tool.
PRODUCT_SYSTEM_PROPERTIES += ro.goodman.car.location_bridge=1

PRODUCT_COPY_FILES += \
    vendor/goodman/permissions/default-permissions-goodman-car.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/default-permissions/default-permissions-goodman-car.xml
