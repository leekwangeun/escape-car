<p align="center">
  <img src="car/app/src/main/assets/img/splash_logo.webp" alt="차근차근 탐험단 로고" width="460">
</p>

# 차근차근 탐험단

> 주차나 충전으로 기다리는 동안 버려지던 아이의 지루한 시간을, 차 안에서만 할 수 있는 색다른 놀이로 채워 주는 인카 체험형 퍼즐 콘텐츠 앱

주차 중인 차 안에서 아이와 보호자가 함께 푸는 방탈출입니다. 아이가 고른 친구가 "나"로 이야기를 들려주며 매 판 새로운 문제를 내고, 아이는 화면을 누르지 않고 차의 진짜 버튼으로 정답을 넣습니다. 계산, 영어, 규칙 찾기는 답만큼 장치(풍량, 온도, 독서등, 시트 열선)를 바꾸고, 추론은 "나 추운데 더울 때 켜는 시원한 바람이 켜져 있어" 같은 상황을 듣고 알맞은 버튼(에어컨, 내기 순환, 앞 유리 성에 제거, 온도, 바람 세기, 운전석 열선, 독서등)을 조작합니다. 앱은 차량 값을 읽어 정답을 판정하고, 차가 이미 정답인 상태면 그 문제는 내지 않습니다.

문제는 Claude로 미리 만들고 코드로 검산해 둔 문제은행(301개: 계산 125, 추론 99, 영어 42, 규칙 찾기 35)에서 아이 나이, 장치, 정답에 맞게 무작위로 뽑습니다. 이야기 서버는 그 문제로 이어지는 이야기를 Claude로 쓰고, 그림과 목소리는 Gemini로 만듭니다. 서버가 없으면 앱에 든 은행 사본과 기본 이야기로 진행합니다. (개발 중 이름: 차량탈출, 앱 목록에도 "차량탈출"로 표시됩니다.)

<p align="center">
  <img src="car/app/src/main/assets/img/splash_friends.webp" alt="차근차근 탐험단 친구들" width="640">
</p>

## 구조

![시스템 구조도](docs/architecture.png)

## 주요 기능

- **차 버튼으로 푸는 퍼즐**: 정답은 화면이 아니라 차량 값(기어, 문, 운전석, 공조, 시트 열선, 독서등)으로 판정합니다. 끝나면 차 설정을 처음 상태로 되돌립니다.
- **판마다 새 이야기**: 보호자가 고른 친구, 아이 나이, 기다릴 시간, 오늘의 소원(장소와 이야기 종류)에 맞춰 대본, 그림, 목소리를 만듭니다. 교훈은 판마다 무작위로 고릅니다.
- **이야기 창고**: 만든 이야기는 서버 볼륨에 저장해 두고, 친구, 나이, 시간, 소원, 계절이 같으면 새로 만들지 않고 바로 꺼내 줍니다. 이 차에서 이미 들은 이야기는 다시 꺼내지 않습니다. 그림이 하나라도 실패한 이야기는 저장하지 않습니다.
- **표지 먼저**: 서버는 대본과 표지가 나오면 바로 응답하고 나머지 그림은 뒤에서 그립니다. 새 이야기 준비가 약 2분에서 1분 남짓으로 줄었습니다. 그림이 늦으면 친구가 기다리는 화면을 보여 주고, 끝내 실패하면 앱 기본 그림으로 진행합니다.
- **이어 하기**: 앱을 껐다 켜도 하던 퍼즐부터 이어 하고, 덜 받은 그림은 다시 받습니다.
- **안전**: 기어 P, 문 닫힘, 운전석에 보호자, 공조 전원이 맞아야 시작하고, 주행이 시작되면 멈춥니다. AI가 쓴 글은 검사기를 통과한 것만 아이에게 갑니다.

## 공모전 제출 정보

| 항목 | 내용 |
|---|---|
| 앱 이름 | 차근차근 탐험단 (팀 컴퓨터와경영) |
| APK | [`apk/컴퓨터와경영_차근차근탐험단_release.apk`](apk/컴퓨터와경영_차근차근탐험단_release.apk), release 빌드(누구나 설치할 수 있게 Android debug 키로 서명), 패키지 `kr.escape.car` |
| 개발, 테스트 에뮬레이터 | Pleos Connect 에뮬레이터, 시스템 이미지 **Pleos Connect v2.1.0 APIs** (Android 14, API 34, Android Automotive) |
| 화면 규격 | **2560 × 1440 (가로), 160 dpi** |
| 사용 SDK | Pleos Vehicle SDK 2.3.2 (`getGear`, `getDoor`, `getSeat`, `getHVAC`, `getLight`), Android Car API (`CarPropertyManager` 차 속도, `CarUxRestrictionsManager` 주행 중 제한) |
| AI | 대본: Claude (`claude-opus-5-5`), 그림과 목소리: Google Gemini. 서버가 없으면 앱 기본 대사와 자막 |

이 APK는 2026-11-15 제출본입니다. 이야기 창고, 표지 먼저 그리기 같은 그 뒤의 기능을 보려면 아래 [2. 차 앱](#2-차-앱)대로 직접 빌드합니다.

### APK로 바로 실행하기

1. Pleos 에뮬레이터를 켭니다. [Pleos 문서의 에뮬레이터 설정](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)대로 만들고 화면은 2560 × 1440으로 둡니다.
2. APK를 설치합니다. Pleos 차량 권한은 설치할 때 부여되고, 차량 속도 권한(`android.car.permission.CAR_SPEED`)은 런타임 권한이라 `-g`로 함께 부여합니다.
   ```bash
   adb install -r -g apk/컴퓨터와경영_차근차근탐험단_release.apk
   ```
3. 앱 목록에서 **차량탈출**을 엽니다.
4. 시작 조건 세 가지를 맞춥니다. 시작 버튼은 이 세 가지가 맞아야 눌립니다.
   - 기어 P (에뮬레이터 기본값)
   - 운전석에 보호자: Virtual Console의 Seat Occupancy → Driver → Occupied, 또는 `node tools/vc.mjs seat driver on`
   - 공조 전원 켜기: Virtual Console의 CLIMATE → Power, 또는 `node tools/vc.mjs power on`
5. 퍼즐은 차량 값을 바꿔서 풉니다. 에뮬레이터 화면 아래 공조 바는 눌러도 차량 값이 바뀌지 않으므로 Virtual Console(`adb forward tcp:8088 tcp:8088` 뒤 `http://127.0.0.1:8088`)이나 `tools/vc.mjs`를 씁니다. 부모 화면의 "정답 보기"를 길게 누르면 이번 판의 정답이 나옵니다. 퍼즐별 정답 넣는 법은 [에뮬레이터_테스트_방법.md](에뮬레이터_테스트_방법.md)에 있습니다.

### 테스트 환경과 필요한 설정

- **AI 이야기 서버 없이도 됩니다.** 이 APK는 `http://127.0.0.1:8787`의 이야기 서버에 접속하고, 서버가 없으면 앱 안의 기본 이야기(별빛 기차)로 퍼즐, 친구 도감, 보물 상자, 처음 설정 복원까지 전체 탐험이 그대로 진행됩니다.
- AI 이야기와 그림까지 보려면 아래 [3. 이야기 서버](#3-이야기-서버)대로 서버를 켜고(Claude, Gemini API 키 필요) `adb reverse tcp:8787 tcp:8787`로 에뮬레이터를 PC의 서버에 연결합니다. 배포한 서버를 쓰려면 그 주소로 앱을 빌드합니다(아래 [5. 서버 배포](#5-서버-배포)).
- 차량 상태는 Pleos 공식 도구인 Virtual Console(웹 페이지)로 바꿉니다. 한 줄 명령 `tools/vc.mjs`를 쓸 때만 Node.js 18 이상이 필요합니다.
- 직접 빌드할 때는 JDK 21을 씁니다(아래 [2. 차 앱](#2-차-앱)).

## 폴더

| 폴더 | 내용 |
|---|---|
| `apk/` | 제출용 release APK |
| `car/` | 차 앱. Kotlin, Jetpack Compose, Pleos Vehicle SDK 2.3.2 |
| `server/` | AI 이야기 서버. Python, FastAPI, Claude, Gemini, Docker. 자세한 설명은 [server/README.md](server/README.md), 배포는 [server/배포_방법.md](server/배포_방법.md) |
| `.github/workflows/` | main에 올리면 서버 테스트 뒤 Lightsail 서버에 자동 배포 |
| `tools/` | Virtual Console 명령(`tools/vc.mjs`) |
| `docs/` | 시스템 구조도, 프로젝트 구조 그림 |
| `에뮬레이터_테스트_방법.md` | 에뮬레이터에서 퍼즐을 직접 풀어 보는 방법 |
| `TTS_대본.md` | 루미 목소리 대본 |

모듈마다 하는 일은 아래 그림에 정리했습니다.

![프로젝트 구조와 모듈](docs/project-structure.png)

## 준비물

- Windows PC
- Git, Node.js 18 이상, Python 3.11 이상
- Android Studio (Android SDK, 에뮬레이터, JDK 21 포함)
- Pleos 에뮬레이터: [Pleos 문서의 에뮬레이터 설정](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)대로 만들고, **AVD 이름을 `pleos`로** 정합니다. [에뮬레이터_테스트_방법.md](에뮬레이터_테스트_방법.md)의 명령이 이 이름으로 에뮬레이터를 켭니다.

## 1. 받기

경로에 한글이 있으면 Android 빌드 도구(aapt2)가 실패하므로 **`C:\dev\escape`에 받습니다.** 빌드 결과는 `C:\dev\build\escape-car`에 생깁니다.

```powershell
git clone https://github.com/leekwangeun/escape-car.git C:\dev\escape
```

서버가 쓰는 검사 라이브러리 [`textgate`](https://github.com/leekwangeun/textgate)는 공개 저장소라서, 서버의 `pip install -r requirements.txt`가 GitHub에서 같이 받아 설치합니다(git이 깔려 있어야 합니다).

## 2. 차 앱

빌드 없이 바로 보려면 [`apk/`](apk/) 폴더의 제출용 release APK를 설치하고 위의 [APK로 바로 실행하기](#apk로-바로-실행하기)를 따르면 됩니다.

```powershell
cd C:\dev\escape\car
.\gradlew.bat assembleDebug          # APK: C:\dev\build\escape-car\app\outputs\apk\debug\app-debug.apk
.\gradlew.bat testDebugUnitTest      # 규칙 엔진, 안전 감시, 이야기 단위 테스트
```

Android Studio에서는 `C:\dev\escape\car` 폴더를 열면 됩니다.

- 앱은 기본으로 `http://127.0.0.1:8787`의 이야기 서버에 붙습니다. 배포한 서버에 붙이려면 `.\gradlew.bat assembleDebug "-PstoryUrl=https://<서버 주소>"`로 빌드합니다.
- 앱 로그: `adb logcat -s EscapeCar`. 이야기를 받을 때 `이야기 창고에서 꺼냄` 또는 `이야기 새로 만듦`이 찍힙니다.
- "들은 이야기" 기록은 앱 안에 있어서, 앱을 지웠다가 다시 깔아야 지워집니다(`adb uninstall kr.escape.car`). `install -r`로 덮어 깔면 남습니다.

## 3. 이야기 서버

```powershell
cd C:\dev\escape\server
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
copy .env.example .env
```

`.env`에 따로 받은 키 세 개를 넣습니다: `ANTHROPIC_API_KEY=`(대본), `GEMINI_API_KEY=`(무료, 대사 읽기), `GEMINI_API_KEY_PAID=`(유료, 그림과 무료 한도를 넘은 대사 읽기). 역할은 [server/README.md의 '키 세 개'](server/README.md#키-세-개)에 있습니다. `.env`는 `.gitignore`에 들어 있어 저장소에 올라가지 않으니, 키를 다른 파일이나 커밋 메시지, 단체방에 붙여 넣지 않도록 주의해 주세요.

- 새 이야기 한 편에 약 400~550원(대본, 그림, 목소리)이 키 주인 계정으로 나갑니다. 창고에서 꺼낸 이야기는 대본과 그림 비용이 들지 않습니다. 여러 편을 한꺼번에 만드는 `textgate sample`은 미리 말하고 돌려 주세요.
- 이야기 창고를 켜려면 `.env`에 `STORY_LIBRARY_DIR=data/library`를 넣습니다. 창고에 든 이야기를 조건별로 보려면 `.\.venv\Scripts\python -m app.shelf_list`.
- 키 없이 앱 연결만 확인할 때는 `.\.venv\Scripts\python -m tools.dev_server`를 씁니다(문장에 [개발용] 표시).
- 시험: `.\.venv\Scripts\python -m pytest -q` (실제 모델을 부르지 않음)

## 4. 실행

에뮬레이터를 켜고 앱을 설치한 뒤, 차량 값은 Pleos 공식 도구인 **Pleos Connect Virtual Console**로 바꿉니다.

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" forward tcp:8088 tcp:8088
```

그다음 브라우저에서 `http://127.0.0.1:8088`을 엽니다. 에뮬레이터 켜는 명령, 시작 조건, 퍼즐별 정답 넣는 법은 [에뮬레이터_테스트_방법.md](에뮬레이터_테스트_방법.md)에 있습니다.

## 5. 서버 배포

이야기 서버는 AWS Lightsail 인스턴스에서 Docker Compose로 돌립니다. Caddy가 HTTPS 인증서를 자동으로 받고, 이야기 서버 컨테이너가 그 뒤에 있습니다.

- main에 push하면 GitHub Actions가 서버 테스트(`pytest`)를 돌리고, 통과하면 SSH로 서버에 들어가 `server/deploy/lightsail_deploy.sh`로 다시 띄운 뒤 `/health`를 확인합니다.
- 이야기 창고, 기록, 목소리 캐시는 Docker 볼륨(`/app/data`)에 있어서 컨테이너를 다시 띄워도 남습니다. `docker compose down -v`는 볼륨까지 지우니 쓰지 않습니다.
- 서버에서 창고 목록 보기: `docker compose exec story python -m app.shelf_list`
- 처음 설정(인스턴스, 주소, `.env`, 배포 키)은 [server/배포_방법.md](server/배포_방법.md)에 있습니다.

## macOS에서

- 아무 폴더에나 받아도 됩니다(경로에 한글만 없으면 됨). 빌드 결과는 `car/app/build.nosync`에 생깁니다. iCloud의 '데스크탑 및 문서' 동기화가 빌드 파일을 `이름 2.dex`처럼 복제해 빌드를 깨뜨리는데, `.nosync`로 끝나는 폴더는 동기화하지 않기 때문입니다.
- JDK는 21을 씁니다. Android Studio에 들어 있는 Java 25로는 이 프로젝트의 Gradle 8.10이 돌지 않으니, Android Studio의 Gradle JDK도 21로 맞춰 주세요.
- 앱: `cd car && ./gradlew assembleDebug`
- 서버: `python3 -m venv .venv && .venv/bin/pip install -r requirements.txt`, 실행은 `.venv/bin/python -m uvicorn app.main:app --env-file .env --host 127.0.0.1 --port 8787`
- Apple Silicon(arm64) 맥에서는 Pleos 에뮬레이터의 arm64-v8a 이미지(Pleos Connect v2.1.0 APIs)로 실행해 시험했습니다.
