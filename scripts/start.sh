#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
npm --prefix frontend ci
npm --prefix frontend run build
exec ./mvnw spring-boot:run
