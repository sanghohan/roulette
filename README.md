# donjiral.net

여러 무료 웹 도구를 모아둔 정적 사이트.

- 서비스 주소: https://www.donjiral.net/ (루트는 `/aicheck/`로 리다이렉트)

## 페이지 구성
```
index.html            # 루트 → /aicheck/ 리다이렉트
aicheck/index.html    # 🔍 AI 영상 판별
dev/index.html        # 📱 참교육앱
ytsummary/index.html  # 📺 유튜브 영상 요약 (AI, 백엔드 필요)
lotto/index.html      # 🍀 로또 6/45 번호 생성기 (자동·반자동·수동·통계·추천)
pension/index.html    # 💰 연금복권720+ 번호 생성기 (자동·반자동·수동)
pingpong/index.html   # 🏓 탁구 커뮤니티
privacy/index.html    # 개인정보처리방침
backend/              # 탁구 백엔드 (docs/탁구백엔드-배포가이드.md 참고)
docs/                 # 설정/배포 문서
```

## 로또 번호 생성기 (/lotto/)
- 자동·반자동(번호 고정)·수동 생성, 저장된 조합은 최신 회차와 자동 대조해 등수 표시
- 당첨번호 통계: 번호별 출현 빈도, 핫/콜드/장기 미출현, 회차 조회, 최근 10회
- 통계 기반 추천(핫넘버·콜드넘버·빈도 가중·밸런스) — 재미용, 확률 향상 아님
- 데이터: https://smok95.github.io/lotto/results/all.json 을 열 때마다 갱신, localStorage 캐시

## 연금복권720+ 생성기 (/pension/)
- 조(1~5)+6자리 자동·반자동(조/자릿수 고정)·수동 생성, localStorage 저장

## 유튜브 영상 요약 (/ytsummary/)
- 유튜브 URL → 자막(자동생성 포함) 추출 → Claude API로 한국어 요약
- 백엔드 `/api/ytsummary` 사용. 서버 환경변수 `YTSUMMARY_API_KEY`(Anthropic API 키) 필요
- videoId 캐시 7일, IP당 분당 3회·일 20회, 전체 일 300회 제한 (application.yml에서 조정)
- 자막 없는 영상은 미지원 (STT 없음)

## 로컬 실행
파일을 브라우저로 열기만 하면 됩니다.

## 배포
`docs/배포가이드.md` 참고 (GitHub 푸시 → 정적 호스팅 → 카페24 도메인 연결).
