# 🍽️ donjiral 메뉴 룰렛

식사 메뉴를 룰렛으로 고르고, 동료들의 선택을 실시간으로 공유하는 웹 서비스.

- 서비스 주소: https://www.donjiral.net/ (기본 페이지) 및 https://www.donjiral.net/roulette
- 기능: 메뉴 룰렛, 메뉴 편집(추가/삭제/초기화), 카테고리 필터, 최근 메뉴 제외, 오늘의 운세, 실시간 선택 공유·집계
- 백엔드: Supabase (실시간 DB) — 설정은 `docs/Supabase-설정가이드.md` 참고
- 모바일 대응 완료 (반응형 레이아웃)

## 구조
```
index.html            # 루트(기본 페이지) - 앱
roulette/index.html   # /roulette 경로 - 동일 앱
docs/                 # 설정/배포 문서
```
> `index.html` 과 `roulette/index.html` 은 동일 파일입니다. 한쪽을 수정하면 다른 쪽도 맞춰 주세요(또는 빌드 스크립트 추가).

## 로컬 실행
파일을 브라우저로 열기만 하면 됩니다. 실시간 공유를 켜려면 앱의 **⚙️ 연결** 탭에서 Supabase URL·키·팀 코드를 입력하세요.

## 배포
`docs/배포가이드.md` 참고 (GitHub 푸시 → 정적 호스팅 → 카페24 도메인 연결).
