# dayvoice · 데이보이스

React 모바일 웹에서 음성 또는 텍스트로 일정을 관리하고 Google Calendar와 양방향 동기화합니다.

## 현재 구성

- 프론트엔드: React 19, TypeScript, Vite
- 백엔드: **Java 21 LTS**, Spring Boot 4.1.1, Gradle Wrapper
- 데이터: PostgreSQL 17, Flyway 마이그레이션, 트랜잭션 기반 전송 큐
- AI: Gemini API (`GEMINI_MODEL`로 모델 변경)
- 음성: MediaRecorder → FFmpeg WAV 변환 → Gemini 구조화 출력
- 실행: Windows Docker Desktop **Linux 컨테이너**, Docker Compose
- 기본 URL: **http://localhost:8085**

## 1. 환경변수

최초 한 번 실행합니다. 이미 `.env`가 있으면 덮어쓰지 않습니다.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/setup.ps1
```

**루트 `.env` 파일에 직접 입력하세요.** Gemini 키를 채팅이나 프론트엔드 코드에 넣지 마세요.

```dotenv
GEMINI_API_KEY=여기에_본인의_키
GEMINI_MODEL=gemini-3.5-flash-lite
```

API 키는 [Google AI Studio](https://aistudio.google.com/api-keys)에서 발급합니다. 계정에서 지원하는 모델과 결제·할당량을 확인하세요. 기본 모델은 [Google 모델 문서](https://ai.google.dev/gemini-api/docs/models)를 기준으로 선택했습니다. `GEMINI_MODEL`을 다른 음성 입력·JSON 구조화 출력 지원 모델로 바꿀 수 있습니다.

무료 한도 사용을 위해 기본값은 `gemini-3.5-flash-lite`입니다. 이 모델은 녹음 이해와 일정 명령 추출을 한 번에 처리하며, 전사 전용 API나 Live API를 추가로 호출하지 않습니다. 무료 티어의 실제 RPM·TPM·일일 한도는 프로젝트별 [AI Studio 한도 화면](https://aistudio.google.com/usage?timeRange=last-28-days&tab=rate-limit)에서 확인하세요. 429 응답 시 안내를 표시하며 다른 모델로 자동 전환하지 않습니다. 무료 티어 데이터 처리 조건은 [공식 가격표](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.5-flash-lite)에 안내되어 있습니다.

`DB_PASSWORD`, `TOKEN_ENCRYPTION_KEY`는 setup 스크립트가 무작위로 생성합니다. 기존 DB를 사용 중일 때 암호화 키를 바꾸면 저장된 Google 토큰을 복호화할 수 없습니다. DB 백업과 함께 안전하게 보관하세요. `.env`는 Git과 Docker 빌드 컨텍스트에서 제외됩니다.

## 2. Docker 실행

Docker Desktop에서 Linux 컨테이너 엔진을 켠 뒤 현재 폴더에서 실행합니다.

```powershell
docker compose up --build -d
docker compose ps
```

브라우저에서 **http://localhost:8085**에 접속합니다. 기본 `DEMO_MODE=true`에서는 ‘샘플로 시작하기’를 누르세요. 샘플 계정은 브라우저 세션마다 분리되고 실제 Google 계정에는 접근하지 않습니다. Gemini 키가 있으면 샘플 일정에 대한 음성·자연어 조작도 가능합니다. 키가 없으면 직접 입력 폼으로 CRUD를 확인할 수 있습니다.

설정 변경 적용:

```powershell
docker compose up -d --force-recreate backend
```

상태 확인과 종료:

```powershell
docker compose logs --tail 100 backend
docker compose down
```

`docker compose down`은 DB 볼륨을 보존합니다. `down -v`는 일정을 삭제하므로 사용하지 마세요.

### Windows WSL 설치가 필요한 경우

Docker Desktop의 Linux 컨테이너 엔진을 사용할 수 없고 WSL 설치가 필요한 PC에서만 아래 절차를 진행합니다.

**관리자 PowerShell**에서:

```powershell
# 현재 PC의 프로젝트 폴더에서 실행합니다.
powershell -ExecutionPolicy Bypass -File scripts/finish-docker-setup.ps1
```

Windows가 재시작을 요구하면 직접 재시작하고 Docker Desktop을 여세요. 초기 약관 화면이 나타나면 본인이 확인해야 합니다. `docker` 명령을 못 찾으면 새 터미널을 여세요. 설치 위치는 `%LOCALAPPDATA%\Programs\DockerDesktop`입니다.

## 3. 실제 Google Calendar 연결

Gemini API 키와 Google OAuth 자격 증명은 별개입니다.

1. [Google Cloud Console](https://console.cloud.google.com/)에서 프로젝트를 만들거나 선택합니다.
2. **Google Calendar API**를 활성화합니다.
3. Google Auth Platform에서 앱 이름·연락처·동의 화면을 설정합니다. 개발 중에는 External / Testing으로 두고 사용할 Google 계정을 테스트 사용자로 등록합니다.
4. OAuth 클라이언트 유형 **웹 애플리케이션**을 생성합니다.
5. 승인된 리디렉션 URI에 아래 주소를 정확히 등록합니다.

```text
http://localhost:8085/api/auth/google/callback
```

승인된 JavaScript 원본을 입력한다면 `http://localhost:8085`를 사용합니다. 원본에는 `/api/...` 경로를 붙이지 않습니다.

6. `.env`를 설정합니다.

```dotenv
DEMO_MODE=false
GOOGLE_CLIENT_ID=발급받은_client_id
GOOGLE_CLIENT_SECRET=발급받은_client_secret
ALLOWED_EMAILS=본인@gmail.com,초대한사람@gmail.com
APP_BASE_URL=http://localhost:8085
APP_PORT=8085
```

7. backend를 재생성한 뒤 **PC localhost에서** Google로 로그인하고 쓸 캘린더를 선택합니다. 연결 후 캘린더 변경은 첫 버전에서 제공하지 않습니다.

OAuth는 state·PKCE를 사용하고, Google에서 검증된 이메일을 정확한 허용 목록과 대조합니다. 필요 권한은 `openid`, `email`, `profile`, `calendar.events`, `calendar.calendarlist.readonly`입니다. 갱신 토큰은 AES-256-GCM으로 암호화해 DB에 저장하며 브라우저에는 전달하지 않습니다.

테스트 상태의 외부 OAuth 앱은 캘린더용 갱신 토큰이 7일 후 만료될 수 있습니다. ‘재연동 필요’ 상태가 나타나면 설정에서 다시 연결하세요. 장기 운영 시 Google의 게시·검증 요건을 별도로 확인해야 합니다. [Google OAuth 정책](https://developers.google.com/identity/protocols/oauth2)

## Cloudflare Tunnel로 ctfmon.shop 연결

### 아이디 로그인과 자동 로그인

PC와 휴대폰 모두 **https://ctfmon.shop**에서 같은 개인 계정으로 로그인합니다. `자동 로그인 (30일)`을 선택하면 브라우저를 다시 열거나 서버가 재시작돼도 로그인 상태를 복구합니다. 공용 기기에서는 체크를 해제하고 사용 후 로그아웃합니다.

```dotenv
LOCAL_LOGIN_USERNAME=사용할_아이디
LOCAL_LOGIN_EMAIL=기존_Google_계정_이메일
LOCAL_LOGIN_PASSWORD_HASH_BASE64=BCrypt_cost12_해시의_Base64_값
```

비밀번호 원문은 `.env`와 브라우저 저장소에 저장하지 않습니다. 서버는 BCrypt 해시로 확인하고, 자동 로그인은 HttpOnly·Secure·SameSite=Lax 쿠키와 DB의 토큰 해시를 사용합니다. 로그아웃한 기기의 토큰은 즉시 폐기합니다. 아이디·이메일·비밀번호 해시가 바뀌면 서버 재시작 시 기존 자동 로그인 토큰도 무효화합니다. `LOCAL_LOGIN_EMAIL`은 `ALLOWED_EMAILS`에 포함해야 합니다.

기존 Google 계정과 같은 이메일을 지정하면 이미 동기화한 일정과 기록을 그대로 사용합니다. 처음 만든 계정은 로그인 후 `연결 및 설정 → Google Calendar 연결`에서 Google 계정과 사용할 캘린더를 선택합니다. 공개 회원가입은 제공하지 않습니다.

### 터널 환경변수

기본 공개 주소는 **https://ctfmon.shop**입니다. 루트 `.env`에 다음 항목을 준비했습니다. 기존 Gemini·Google 키는 유지합니다.

```dotenv
CLOUDFLARE_TUNNEL_TOKEN=여기에_터널_연결_토큰만_입력
CLOUDFLARE_PUBLIC_URL=https://ctfmon.shop
```

이 값은 Cloudflare의 **Tunnel 연결 토큰**이며 Global API Key 또는 계정 API Token과는 다릅니다. 생성 화면의 설치 명령에 `--token`이 있으면 그 뒤의 토큰 값만 복사합니다. Windows 설치 명령이라면 `cloudflared.exe service install` 뒤에 표시된 긴 토큰 값입니다. 전체 설치 명령을 `.env`에 붙여 넣지 않습니다. 토큰은 `cloudflared` 프로세스의 `TUNNEL_TOKEN` 환경변수로 전달하고 명령행 인자에는 넣지 않습니다. [공식 실행 옵션](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/run-parameters/)

### Cloudflare에서 입력할 값

1. [Cloudflare 대시보드](https://dash.cloudflare.com/)에 `ctfmon.shop`을 추가하고, 도메인 등록 업체에서 지정된 Cloudflare 네임서버를 적용해 도메인이 활성 상태인지 확인합니다. 이미 활성 상태면 이 단계는 건너뜁니다.
2. Cloudflare 계정의 **Networking → Tunnels**에서 `cloudflared` 유형의 터널을 만듭니다. 예시 이름: `dayvoice`. 화면 버전에 따라 Cloudflare One / Zero Trust의 **Networks → Connectors → Cloudflare Tunnels**에 표시될 수도 있습니다.
3. 기존 터널이면 터널을 선택하고 **Add a replica**를 눌러 설치 명령을 확인합니다. 명령의 `eyJ...`로 시작하는 토큰만 위 `.env` 항목에 입력합니다. 이 프로젝트 스크립트를 사용한다면 대시보드의 서비스 설치 명령을 별도로 실행할 필요는 없습니다. [기존 터널 토큰 확인](https://developers.cloudflare.com/tunnel/reference/tunnel-tokens/)
4. 해당 터널의 **Routes → Add route → Published application**에서 다음을 설정합니다. 화면 버전에 따라 `Public Hostname`으로 표시될 수도 있습니다.

| 항목 | 입력 값 |
| --- | --- |
| Subdomain | **비워 두기** (루트 도메인 사용) |
| Domain | `ctfmon.shop` |
| Path | 비워 둠 |
| Service type | `HTTP` |
| Service URL — 현재 Windows 로컬 실행 | `localhost:8085` |
| Service URL — Docker Compose 실행 | `web:80` |
| HTTP Host Header 덮어쓰기 | 비워 둠 |

**현재 실행 방식을 기준으로 Service URL 하나를 선택합니다.** Docker 컨테이너의 `localhost`는 Windows PC가 아니므로 Docker에서는 `web:80`을 사용합니다. Windows와 Docker 커넥터를 동시에 같은 터널에 연결하지 마세요. 전환할 때 기존 커넥터를 종료하고 서비스 URL도 변경합니다. 브라우저는 Cloudflare의 HTTPS로 접속하며 PC·컨테이너 내부 서비스는 HTTP로 연결합니다. 로컬 CA 설치와 `compose.https.yaml`은 이 방식에 필요하지 않습니다. [공식 터널 생성 안내](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/create-remote-tunnel/)

현재 한국어 Cloudflare 대시보드에서는 `네트워크 → 터널 → ctfmon-server → 경로 → ctfmon.shop 행의 작업 → 경로 편집`으로 들어갑니다. 유형 선택 없이 **서비스 URL** 입력창 하나가 보이면 `http://localhost:8085` 전체를 입력하고 **변경 사항 저장**을 누릅니다. Windows 실행에서 `http://nginx:80`으로 설정하면 해당 서버 이름을 찾을 수 없어 502 Host Error가 발생합니다. 1033 오류는 터널 연결 프로그램이 실행되지 않았을 때 확인합니다.

### Google OAuth에 추가할 주소

[Google Auth Platform의 클라이언트](https://console.cloud.google.com/auth/clients)에서 기존 웹 클라이언트에 추가합니다. 기존 localhost 항목은 유지해도 됩니다.

| 항목 | 입력 값 |
| --- | --- |
| 승인된 JavaScript 원본 | `https://ctfmon.shop` |
| 승인된 리디렉션 URI | `https://ctfmon.shop/api/auth/google/callback` |

OAuth 동의 화면에서 승인된 도메인을 요구하면 최상위 등록 도메인 `ctfmon.shop`을 입력합니다. 테스트 사용자와 `.env`의 `ALLOWED_EMAILS`에는 로그인할 계정이 계속 포함되어 있어야 합니다. [Google OAuth 웹 서버 설정](https://developers.google.com/identity/protocols/oauth2/web-server)

### 현재 Windows 로컬 서버로 연결

WSL·Docker 엔진이 준비되기 전 사용하는 임시 개발 실행입니다. 설정만 검사하는 명령은 터널을 열지 않습니다.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start-cloudflare.ps1 -CheckConfiguration
```

Cloudflare 경로·토큰과 Google 콜백 등록을 마친 뒤 현재 폴더에서 실행합니다.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/stop-local.ps1
powershell -ExecutionPolicy Bypass -File scripts/start-local.ps1 -Mode Google -Cloudflare
powershell -ExecutionPolicy Bypass -File scripts/start-cloudflare.ps1
```

실행 시 필요한 `cloudflared.exe`는 공식 배포본을 내려받습니다. 앱의 공개 주소와 보안 쿠키를 Cloudflare에 맞추되 `.env`의 기존 `APP_BASE_URL`은 덮어쓰지 않습니다. 이 모드에서는 Vite가 설정된 도메인만 추가 허용하고, 개발용 HMR/WebSocket은 비활성화합니다. 수정 사항은 페이지를 새로고침해서 확인합니다. 원본 포트는 계속 8085입니다.

연결 후 PC·휴대폰 모두 **https://ctfmon.shop**에 접속해 로그인하고 마이크 권한을 허용합니다. 공개 HTTPS 도메인에서는 PC 연결 코드가 필요하지 않습니다. 기존 HTTP/IP 주소를 열면 HTTPS 주소로 이동하는 버튼이 표시됩니다. PC와 앱·터널 프로세스가 실행 중이어야 접속됩니다.

터널 종료 및 기존 로컬 모드로 돌아가기:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/stop-cloudflare.ps1
powershell -ExecutionPolicy Bypass -File scripts/stop-local.ps1
powershell -ExecutionPolicy Bypass -File scripts/start-local.ps1 -Mode Google
```

### Docker Compose로 연결

Docker Linux 엔진이 준비된 후 Windows 로컬 실행과 터널을 종료하고, Cloudflare의 Service URL을 `web:80`으로 바꿉니다.

```powershell
docker compose -f compose.yaml -f compose.cloudflare.yaml config --quiet
docker compose -f compose.yaml -f compose.cloudflare.yaml up --build -d
docker compose -f compose.yaml -f compose.cloudflare.yaml ps
```

Cloudflare 구성은 실제 Google 모드와 `Secure` 세션 쿠키를 적용합니다. 토큰이 비어 있으면 시작 전에 오류로 안내합니다. `.env` 수정 후 위 `up` 명령을 다시 실행하면 변경된 설정이 반영됩니다. `config --quiet`는 토큰을 출력하지 않지만, `--quiet` 없는 `config`는 비밀 환경변수까지 출력할 수 있으므로 공유하지 마세요. 기존 로컬 H2와 Docker PostgreSQL 데이터는 별도이며 계정 재연결과 필요한 데이터 이전을 고려해야 합니다.

## 4. 휴대폰과 음성 사용

- 마이크 버튼을 누르고 말한 뒤 다시 눌러 종료합니다. 최대 60초, 12MB입니다.
- “내일 오후 3시 치과 예약해 줘”처럼 명확한 등록은 바로 저장합니다.
- “치과 예약 한 시간 늦춰 줘”, “그 일정 취소해 줘”는 미리보기 확인 뒤 실행합니다.
- 확인은 버튼 또는 다시 녹음한 음성으로 할 수 있습니다. 명령 문맥은 서버에서 관리하고 10분 후 만료됩니다.
- 모호한 날짜·시간이나 대상은 추가 질문으로 확인합니다. 종료 시간이 없으면 1시간, 시간대는 서울입니다.
- 조회 결과를 화면과 브라우저 음성 합성으로 안내합니다. 자동 재생이 제한되면 듣기 버튼을 누르세요.
- 화면이 숨겨지면 녹음을 종료합니다. 항상 듣기·잠금 화면 조작은 제공하지 않습니다.

PC의 `http://localhost:8085`에서는 마이크를 사용할 수 있습니다. 휴대폰의 `http://PC-IP:8085`에서는 일정·기록 조회와 텍스트 입력을 사용하고, **휴대폰 마이크는 신뢰된 HTTPS로 접속한 뒤 사용**합니다. [마이크 보안 컨텍스트](https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia)

### PC 계정을 휴대폰 IP 접속에 연결

1. 서버 PC에서 `http://localhost:8085`에 접속해 Google 로그인과 캘린더 선택을 완료합니다.
2. **연결 및 설정 → 연결 코드 생성**을 누릅니다. 코드는 **5분 동안 한 번** 사용할 수 있습니다.
3. 휴대폰에서 `http://PC-IP:8085`에 접속하고 코드를 입력한 뒤 **이 기기 연결**을 누릅니다.
4. PC와 같은 계정의 일정·시간별 기록이 표시됩니다. 코드가 만료되거나 이미 사용됐다면 PC에서 새 코드를 발급합니다.

`localhost`와 IP 주소의 로그인 쿠키는 서로 다르므로 PC에서 로그인해도 IP 주소 화면이 자동 로그인되지는 않습니다. `.env`의 `APP_BASE_URL=http://localhost:8085`는 유지합니다. Google 웹 OAuth는 localhost 예외를 제외한 숫자 IP의 리디렉션 URI를 허용하지 않으므로, 휴대폰은 PC에서 발급한 코드로 연결합니다. 휴대폰에서 `localhost`는 휴대폰 자신을 가리킵니다. [Google 리디렉션 URI 규칙](https://developers.google.com/identity/protocols/oauth2/web-server#uri-validation)

### IP 접속 진단

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-network.ps1
```

진단은 `.env`의 포트, IP별 URL, 수신 포트, HTTP `/api/health`, Windows 네트워크·방화벽 상태를 표시합니다. 키를 출력하거나 설정을 변경하지 않습니다. **이 스크립트의 상태 요청은 HTTP 전용**이므로 HTTPS로 전환한 뒤 HTTP 요청 실패를 서비스 장애로 판단하지 마세요.

2026-09-25 확인된 PC 주소는 `119.204.72.227`, 네트워크 분류는 `Public`입니다. 이 주소는 사설 IP 범위가 아니며, 주소만으로 휴대폰과 같은 Wi-Fi·서브넷이라고 판단할 수 없습니다. PC에서 IP 주소 접속에 성공해도 휴대폰 접근까지 검증되지는 않습니다. 휴대폰에서 실제 URL을 열어 확인하고, 안 열리면 라우팅·VPN·기기 격리·방화벽을 확인합니다.

휴대폰이 같은 서브넷에 있고 Windows 방화벽의 허용 규칙이 필요한 경우에만, **관리자 PowerShell**에서 아래 규칙을 검토 후 직접 추가합니다. TCP 8085를 로컬 서브넷에만 허용하며 방화벽을 끄지 않습니다. 다른 서브넷에서의 접속은 이 규칙만으로 허용되지 않습니다. [Microsoft 방화벽 규칙](https://learn.microsoft.com/en-us/powershell/module/netsecurity/new-netfirewallrule)

```powershell
New-NetFirewallRule -Name 'Dayvoice-8085-LocalSubnet' -DisplayName 'Dayvoice 8085 Local subnet' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8085 -RemoteAddress LocalSubnet -Profile Private,Public
# 나중에 이 규칙만 제거할 때:
# Remove-NetFirewallRule -Name 'Dayvoice-8085-LocalSubnet'
```

### 선택: 휴대폰 마이크용 로컬 HTTPS

현재 HTTP 실행은 그대로 두고 인증서부터 준비할 수 있습니다. 아래 인증서는 개발용이며 각 기기에 공개 CA 인증서를 직접 설치해야 합니다.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/setup-local-https.ps1
# 특정 IP를 지정하려면 자동 탐지 대신 다음을 사용합니다.
# powershell -ExecutionPolicy Bypass -File scripts/setup-local-https.ps1 -IpAddress 119.204.72.227
```

공식 mkcert를 다운로드해 `.tools/certs`에 인증서를 만듭니다. `localhost`, `127.0.0.1`, `::1`, 현재 인터페이스 IPv4가 포함됩니다. **인증서 신뢰 등록·방화벽 변경·서비스 재시작은 자동으로 하지 않습니다.** PC IP가 바뀌면 다시 생성하고 HTTPS 서비스를 재시작합니다.

Windows에서 이 프로젝트의 공개 CA를 확인한 뒤, 현재 사용자만 신뢰하도록 직접 등록합니다.

```powershell
Import-Certificate -FilePath '.tools/certs/dayvoice-local-ca.crt' -CertStoreLocation Cert:\CurrentUser\Root
```

휴대폰에는 **`.tools/certs/dayvoice-local-ca.crt`만** 전달합니다. `server-key.pem`, `ca/rootCA-key.pem` 또는 `.tools/certs` 폴더 전체를 복사하거나 웹으로 공개하지 마세요.

- **iPhone:** 인증서 파일을 열고 설정에서 프로파일을 설치한 뒤, **설정 → 일반 → 정보 → 인증서 신뢰 설정**에서 해당 루트 인증서의 완전 신뢰를 켭니다. 프로파일 설치만으로 TLS 신뢰가 활성화되지는 않습니다. [Apple 안내](https://support.apple.com/en-us/102390)
- **Android:** 설정의 보안·인증서 설치 메뉴에서 **CA 인증서**로 공개 CA를 설치하고 Chrome을 다시 열어 확인합니다. 메뉴 이름은 제조사·버전에 따라 다릅니다. 인증서 관리 위치는 [Google 안내](https://support.google.com/pixelphone/answer/2844832?hl=ko), 브라우저의 로컬 CA 처리는 [Chromium 안내](https://chromium.googlesource.com/chromium/src/+/main/net/data/ssl/chrome_root_store/faq.md)를 참고하세요.

Google Cloud의 기존 웹 OAuth 클라이언트에 다음 주소를 **추가**합니다. HTTP 주소도 유지하면 다시 HTTP 모드로 돌아갈 수 있습니다.

| 항목 | HTTPS 추가 값 |
| --- | --- |
| 승인된 JavaScript 원본 | `https://localhost:8085` |
| 승인된 리디렉션 URI | `https://localhost:8085/api/auth/google/callback` |

로컬 실행을 HTTPS로 전환할 준비가 되면:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/stop-local.ps1
powershell -ExecutionPolicy Bypass -File scripts/start-local.ps1 -Mode Google -Https
```

PC에서 `https://localhost:8085`에 다시 로그인해 **새 연결 코드**를 만들고, 휴대폰에서 `https://PC-IP:8085`를 열어 연결합니다. 인증서 오류가 없는 상태에서 마이크 권한을 허용합니다. `.env`의 기존 HTTP `APP_BASE_URL`은 그대로 두며, `-Https` 실행에만 HTTPS 주소와 보안 쿠키가 적용됩니다. 별도 HTTPS 도메인을 선택할 때만 `HTTPS_BASE_URL`을 설정하고 인증서·Google 콜백 주소도 맞춥니다.

Docker 엔진이 준비된 환경에서는 로컬 실행을 종료하고 아래 **선택 구성**으로 전환합니다. 호스트 포트는 계속 8085이고, 웹 컨테이너의 80번 포트가 TLS를 처리합니다. 서버 인증서·키만 개별 읽기 전용으로 연결하며 CA 개인키는 컨테이너에 연결하지 않습니다.

```powershell
docker compose -f compose.yaml -f compose.https.yaml config --quiet
docker compose -f compose.yaml -f compose.https.yaml up --build -d
```

HTTPS Compose 구성 검사와 생성 인증서의 TLS 통신은 검증했습니다. 실제 휴대폰의 인증서 신뢰·네트워크 접근·마이크 허용은 해당 기기에서 확인해야 합니다. 로컬 H2와 Compose PostgreSQL은 별도 저장소이므로 Docker로 옮기면 계정을 다시 연결해야 합니다.

## 5. 동기화와 데이터 처리

### 시간별 활동 기록

일정과 별도로 실제 수행한 내용을 누적합니다. 예를 들어 “오늘 12시부터 13시까지 고객 미팅했고, 견적 수정 요청받았다고 활동 기록해 줘”라고 말하면 시간 구간·제목·내용을 저장합니다.

- **시간별 기록** 탭에서 날짜별 기록을 조회하고 직접 작성·수정·삭제할 수 있습니다.
- “오늘 활동 기록 보여 줘”, “그 미팅 기록 내용을 수정해 줘”처럼 음성으로도 조회·변경합니다. 수정·삭제는 확인 후 실행합니다.
- 기록은 일정 없이 독립적으로 남길 수 있고, 선택적으로 기존 일정에 연결합니다. 시작·종료 시간은 모두 필요하며 실제 활동 시간을 임의로 1시간으로 채우지 않습니다.
- 연결한 일정을 삭제해도 기록은 남습니다. 반복 일정의 개별 회차에도 기록을 연결할 수 있습니다.
- 현재 기록은 **내부 저장 전용**입니다. Google 일정이나 설명을 자동 변경하지 않습니다.
- `activity_record` 테이블은 Flyway V2 마이그레이션으로 추가됩니다. 기존 일정은 보존됩니다.

### 캘린더 동기화

- 내부 저장과 Google 반영 상태를 분리합니다. 내부 저장 완료가 Google 동기화 완료를 뜻하지 않습니다.
- 내부 변경의 전송 큐는 5초 주기로 처리합니다. Google에서 직접 바꾼 내용은 사용자별 한 캘린더를 60초 주기로 증분 동기화하며 수동 동기화도 제공합니다.
- 트랜잭션으로 일정과 outbox를 함께 기록합니다. 일관된 Google 이벤트 ID, 요청 식별자, ETag를 사용합니다.
- 실패한 전송은 지수 간격으로 재시도합니다. Google 변경과 로컬 변경이 충돌하면 둘 중 하나를 선택할 수 있습니다.
- 반복 일정은 **지난 30일부터 앞으로 365일까지 조회만** 제공합니다. 매 동기화 시 해당 범위의 발생 일정을 갱신합니다.
- 단일 일정과 종일 일정은 생성·수정·삭제할 수 있습니다. 참석자 초대, 반복 규칙 편집, 자체 푸시 알림은 미지원입니다.
- 음성 원본과 변환 파일은 처리 후 삭제합니다. 인식문·명령·응답은 재시도와 문맥 유지를 위해 DB에 잠시 저장되며, 만료 후 24시간이 지나면 정리합니다. 일정 데이터는 계속 보관됩니다.
- Gemini로 음성 및 해석에 필요한 사용자의 일정 문맥이 전달됩니다. 대규모 캘린더의 문맥이 한도를 넘으면 직접 입력을 안내합니다.
- 키·토큰·음성 원본은 애플리케이션 로그에 기록하지 않습니다. Gemini가 반환한 명령은 서버 검증·소유권 확인을 거칩니다.

## 6. 개발과 테스트

Docker가 준비되기 전, 다운로드한 Java 21과 샘플용 H2 DB로 로컬 화면을 실행할 수 있습니다. 이 모드에서는 실제 Google 로그인을 차단합니다. Compose의 PostgreSQL 데이터와 섞이지 않습니다.

```powershell
npm.cmd ci --prefix frontend
# JAVA_HOME에 Java 21을 설정한 뒤:
.\backend\gradlew.bat -p backend test bootJar
powershell -ExecutionPolicy Bypass -File scripts/start-local.ps1
# http://localhost:8085
powershell -ExecutionPolicy Bypass -File scripts/stop-local.ps1
```

`.tools/java`의 JDK가 있으면 시작 스크립트가 자동으로 사용합니다. `.tools/ffmpeg`의 ffmpeg.exe도 자동으로 감지합니다. 시작할 때 백엔드를 증분 빌드하므로 복사해 온 이전 JAR 대신 현재 소스가 반영됩니다. 프론트엔드 Vite가 없으면 `npm ci`로 의존성을 설치합니다. 소스 변경 후에는 재시작하세요. 로컬 샘플 DB는 `backend/build/local-db.mv.db`에 저장됩니다.

다른 PC로 옮겼다면 Node.js와 Java 21을 확인하고 위 빌드·테스트를 실행하세요. Gradle 및 의존성 캐시는 소스 폴더 밖에 있으므로 첫 빌드에는 인터넷 연결이 필요합니다. 복사한 `node_modules`에서 오류가 나면 `npm.cmd ci --prefix frontend`로 재설치하세요. `.env`와 기존 DB는 보존해야 하며, `gradlew clean`은 `backend/build`의 로컬 DB까지 삭제하므로 사용하지 마세요. Docker의 PostgreSQL 볼륨은 소스 폴더에 포함되지 않으므로 기존 실데이터가 필요하면 아래 백업·복구 절차로 별도 이전해야 합니다. 로컬 종료 스크립트는 실행 경로와 시작 시각을 확인하며, 이전 형식이나 다른 폴더에서 복사된 PID 기록은 프로세스를 종료하지 않고 제거합니다.

Docker 준비 전 **실제 Google 계정 연동 검증**이 필요하면 OAuth 설정을 채우고 다음과 같이 실행합니다.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/stop-local.ps1
powershell -ExecutionPolicy Bypass -File scripts/start-local.ps1 -Mode Google
```

이 임시 검증 모드는 별도 H2 파일 `backend/build/account-check-db.mv.db`를 사용하며 Google 갱신 토큰은 동일하게 암호화합니다. 샘플 모드 및 Docker PostgreSQL과 데이터가 분리됩니다. 브라우저에서 Google 로그인·동의를 완료하고 캘린더를 선택하세요. Docker로 전환할 때 Google 계정을 다시 연결해야 하며, 임시 검증 DB의 내부 기록은 자동 이전되지 않습니다.

```powershell
.\backend\gradlew.bat -p backend test
npm.cmd run build --prefix frontend
npm.cmd run test --prefix frontend
# 로컬 서비스 실행 상태에서:
cd frontend
npx.cmd playwright install chromium
npm.cmd run test:e2e
```

자동 검증은 등록 중복 방지, 확인·취소·만료, stale version, 사용자 분리, 암호화, 종일 종료 경계, 시간대, 충돌 해결, CSRF 및 실제 브라우저 CRUD를 포함합니다. AI와 Google의 외부 호출은 테스트에서 대체하며 실제 자격 증명 연결을 통과했다는 의미는 아닙니다. 모바일 크기 테스트는 Chromium 에뮬레이션이며 실제 아이폰 Safari 검증을 대체하지 않습니다.

`FFMPEG_PATH` 환경변수에 FFmpeg 실행 파일 경로가 있으면 서버의 실제 WAV 변환 테스트도 실행합니다. 브라우저 음성 테스트는 합성 마이크로 녹음·업로드를 검증하고 Gemini 응답은 대체하므로 과금하지 않습니다.

## 7. 백업·복구

PowerShell의 바이너리 리디렉션 차이를 피하도록 컨테이너 안에서 덤프한 뒤 복사합니다.

```powershell
New-Item -ItemType Directory -Force backups
docker compose exec -T db pg_dump -U dayvoice -d dayvoice -Fc -f /tmp/dayvoice.dump
docker compose cp db:/tmp/dayvoice.dump backups/dayvoice.dump
```

복구는 비어 있는 `dayvoice` DB를 대상으로 수행합니다. 기존 데이터가 있는 DB에 실행하면 충돌할 수 있습니다.

```powershell
docker compose stop backend
docker compose cp backups/dayvoice.dump db:/tmp/dayvoice.dump
docker compose exec -T db pg_restore -U dayvoice -d dayvoice --no-owner --exit-on-error /tmp/dayvoice.dump
docker compose start backend
```

`.env`의 암호화 키를 DB 백업과 별도로 안전하게 보관하세요.

## 주요 API

| 경로 | 역할 |
| --- | --- |
| `GET /api/session` | 사용자·설정 여부·CSRF 토큰 |
| `GET /api/auth/google` | Google 인증 시작 |
| `POST /api/auth/demo`, `/api/auth/logout` | 샘플 세션·로그아웃 |
| `POST /api/auth/device-code`, `/api/auth/device-link` | 5분 일회용 연결 코드 발급·다른 기기 로그인 |
| `GET /api/calendars`, `POST /api/calendars/select` | 캘린더 선택 |
| `GET /api/events?from=YYYY-MM-DD&to=YYYY-MM-DD` | 종료일 제외 기간 조회 |
| `POST /api/events`, `PUT/DELETE /api/events/{id}` | 직접 등록·변경 미리보기 |
| `GET /api/records?from=YYYY-MM-DD&to=YYYY-MM-DD` | 기간별 활동 기록 조회 |
| `POST /api/records`, `PUT/DELETE /api/records/{id}` | 시간별 기록 작성·변경 미리보기 |
| `POST /api/commands/text`, `/api/commands/audio` | 텍스트·녹음 해석 |
| `POST /api/commands/{id}/confirm` | 저장된 변경 승인·취소 |
| `POST /api/events/{id}/resolve` | 동시 수정 충돌 해결 |
| `GET/POST /api/sync` | 상태·재시도 |

변경 요청은 `/api/session` 응답의 `csrfHeader` 헤더에 `csrfToken`을 포함해야 합니다. 일정 변경에는 `requestId`와 기존 `version`이 필요하며, 확인은 서버에 저장된 변경 내용만 실행합니다.
