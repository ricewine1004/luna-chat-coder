# MyDesk AI Windows 0.3.3

가벼운 Windows용 MyDesk AI 데스크톱 클라이언트입니다.

- Windows 10/11 x64 대상
- .NET 8 self-contained 배포
- Microsoft Edge WebView2 기반
- Cloudflare의 기존 MyDesk AI 웹 앱을 그대로 사용
- PC별 WebView2 프로필을 `%LOCALAPPDATA%\MyDesk AI\WebView2`에 유지하므로 로그인 상태가 업데이트 후에도 유지됨
- 프로그램 설치 위치는 `%LOCALAPPDATA%\Programs\MyDesk AI`
- 시작 메뉴 및 바탕화면 바로가기 생성
- 같은 AppId를 유지하므로 이후 설치 EXE로 덮어쓰기 업데이트 가능
- 외부 링크는 기본 브라우저에서 열고 MyDesk AI 도메인만 앱 내부에서 표시
- 중복 실행 방지

설치 파일 이름: `MyDesk_AI_PC_Setup_0.3.3.exe`
