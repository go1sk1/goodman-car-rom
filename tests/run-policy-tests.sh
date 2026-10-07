#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
output="$(mktemp -d)"
trap 'rm -rf -- "$output"' EXIT
javac -d "$output" "$root/packages/apps/GoodmanCarBridge/core/kr/goodman/carbridge/core/VehiclePolicy.java" "$root/tests/VehiclePolicyTest.java"
java -cp "$output" VehiclePolicyTest
