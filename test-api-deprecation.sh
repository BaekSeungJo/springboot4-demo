#!/usb/bin/bash

BASE_URL="http://localhost:8080"
ENDPOINT="/api/products"

echo "===================================="
echo "API Version Deprecation 테스트"
echo "===================================="

echo ""
echo "1. API V1.0 요청 (Deprecated)"
curl -i -X GET "${BASE_URL}${ENDPOINT}" \
     -H "X-API-Version: 1.0" \
     -H "Accept: application/json" 2>/dev/null | head -15

echo ""
echo ""
echo "2. API v2.0 요청 (Current)"
echo "===================================="
curl -i -X GET "${BASE_URL}${ENDPOINT}" \
     -H "X-API-Version: 2.0" \
     -H "Accept: application/json" 2>/dev/null | head -20

echo ""
echo ""
echo "3. 버전 미지정 요청 (기본 버전 적용)"
echo "===================================="
curl -i -X GET "${BASE_URL}${ENDPOINT}" \
     -H "Accept: application/json" 2>/dev/null | head -15

echo ""
echo ""
echo "4. 지원하지 않는 버전 요청"
echo "===================================="
curl -i -X GET "${BASE_URL}${ENDPOINT}" \
     -H "X-API-Version: 9.9" \
     -H "Accept: application/json" 2>/dev/null | head -10