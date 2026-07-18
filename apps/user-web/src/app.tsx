import { AppShell } from './components/app-shell';

export function App() {
  return (
    <AppShell
      sidebar={<span className="aw-sr-only">Autumn Wind Ai 侧栏</span>}
      header={<span className="aw-sr-only">当前会话</span>}
      messages={<span />}
      composer={<span className="aw-sr-only">消息输入区</span>}
      messagesTestId="user-web-root"
    />
  );
}
