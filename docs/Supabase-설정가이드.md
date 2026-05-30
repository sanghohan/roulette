# 메뉴 룰렛 — Supabase 연결 가이드

실시간 공유를 켜려면 한 번만 아래를 따라 하면 됩니다. 소요 시간 약 5분, 비용 무료.

## 1. 가입 & 프로젝트 생성
1. https://supabase.com 접속 → **Start your project** → GitHub 또는 이메일로 가입
2. **New project** 클릭 → 이름(예: `menu-roulette`), DB 비밀번호 입력 → 지역은 `Northeast Asia (Seoul)` 권장 → 생성 (1~2분 소요)

## 2. 테이블 만들기
좌측 메뉴 **SQL Editor** → **New query** → 아래를 붙여넣고 **Run**:

```sql
create table public.picks (
  id bigint generated always as identity primary key,
  menu text not null,
  who  text not null,
  team text,
  created_at timestamptz not null default now()
);

-- 행 단위 보안 켜기
alter table public.picks enable row level security;

-- 누구나 읽기/쓰기 허용 (사내 가벼운 용도 기준)
create policy "read_all"   on public.picks for select using (true);
create policy "insert_all" on public.picks for insert with check (true);

-- 실시간 전송 켜기
alter publication supabase_realtime add table public.picks;
```

## 3. 접속 정보 복사
좌측 **Settings(톱니) → API** 에서 두 값을 복사:
- **Project URL** : `https://xxxx.supabase.co`
- **anon public** 키 : `eyJ...` 로 시작하는 긴 문자열

> anon 키는 공개되어도 되는 키입니다(위 정책상 읽기/쓰기만 허용). 화면에 보이는 `service_role` 키는 절대 앱에 넣지 마세요.

## 4. 앱에 입력
1. `menu-roulette-supabase.html` 을 브라우저(휴대폰 가능)에서 열기
2. 우측 **⚙️ 연결** 탭으로 이동
3. Project URL, anon key, **팀 코드**(예: `dev-team-2026`), 내 이름 입력
4. **저장 & 연결** → 상단 점이 초록색이면 성공

같은 **팀 코드**를 입력한 사람끼리 선택이 실시간으로 공유됩니다. 코드를 다르게 쓰면 그룹이 분리돼요.

## 휴대폰에서 쓰기 (앱처럼)
- 파일을 사내 정적 호스팅(예: GitHub Pages, Netlify, Vercel 무료)에 올리면 URL로 어디서나 접속 가능
- 모바일 브라우저에서 **홈 화면에 추가**하면 앱 아이콘처럼 사용됩니다

## 자주 묻는 것
- **새로고침해야 보이나요?** 아니요. 동료가 고르면 자동으로 화면에 뜹니다(실시간 구독).
- **메뉴 목록도 공유되나요?** 메뉴 편집은 개인 설정(각자 기기)입니다. 팀 공통 메뉴가 필요하면 알려주세요 — 메뉴도 서버 공유로 바꿔드릴게요.
- **기록이 계속 쌓이나요?** 화면엔 오늘 것만 보이지만 DB엔 누적됩니다. 정리가 필요하면 SQL Editor에서 오래된 행을 지우면 됩니다.
