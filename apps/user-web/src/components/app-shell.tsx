import { useRef, useState, type ReactNode } from 'react';
import { Menu, X } from 'lucide-react';
import { Dialog } from 'radix-ui';

import { IconButton } from './icon-button';

export interface AppShellProps {
  sidebar?: ReactNode;
  header?: ReactNode;
  mobileHeader?: {
    title?: ReactNode;
    model?: ReactNode;
    account?: ReactNode;
  };
  messages?: ReactNode;
  composer?: ReactNode;
  messagesTestId?: string;
}

export function AppShell({
  sidebar,
  header,
  mobileHeader,
  messages,
  composer,
  messagesTestId = 'app-shell-messages'
}: AppShellProps) {
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);

  return (
    <Dialog.Root open={mobileSidebarOpen} onOpenChange={setMobileSidebarOpen}>
      <div className="aw-app-shell" data-testid="app-shell">
        <aside className="aw-shell-sidebar" data-testid="app-shell-sidebar">
          {sidebar}
        </aside>
        <div className="aw-shell-main">
          <header className="aw-shell-header" data-testid="app-shell-header">
            <div className="aw-shell-mobile-trigger">
              <Dialog.Trigger asChild>
                <IconButton ref={triggerRef} label="打开侧栏">
                  <Menu aria-hidden="true" size={18} strokeWidth={1.8} />
                </IconButton>
              </Dialog.Trigger>
            </div>
            <div
              className="aw-shell-header-content aw-shell-desktop-header"
              data-testid="app-shell-desktop-header"
            >
              {header}
            </div>
            <div className="aw-shell-mobile-header" data-testid="app-shell-mobile-header">
              <div className="aw-shell-mobile-title-row" data-testid="app-shell-mobile-title-row">
                {mobileHeader?.title}
              </div>
              <div className="aw-shell-mobile-model-row" data-testid="app-shell-mobile-model-row">
                <div className="aw-shell-mobile-model-trigger" data-testid="app-shell-mobile-model-trigger">
                  {mobileHeader?.model}
                </div>
              </div>
            </div>
            <div className="aw-shell-mobile-account" data-testid="app-shell-mobile-account">
              {mobileHeader?.account}
            </div>
          </header>
          <main className="aw-shell-messages" data-testid={messagesTestId}>
            <div className="aw-shell-messages-inner">{messages}</div>
          </main>
          <footer className="aw-shell-composer" data-testid="app-shell-composer">
            <div className="aw-shell-composer-inner">{composer}</div>
          </footer>
        </div>
      </div>
      <Dialog.Portal>
        <Dialog.Overlay className="aw-mobile-sidebar-overlay" />
        <Dialog.Content
          aria-label="会话侧栏"
          className="aw-mobile-sidebar-dialog"
          data-mobile-sidebar="true"
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            triggerRef.current?.focus();
          }}
          onEscapeKeyDown={(event) => {
            event.preventDefault();
            setMobileSidebarOpen(false);
          }}
        >
          <Dialog.Title className="aw-sr-only">会话侧栏</Dialog.Title>
          <Dialog.Description className="aw-sr-only">
            在移动设备上查看和管理会话
          </Dialog.Description>
          <div className="aw-mobile-sidebar-content">{sidebar}</div>
          <Dialog.Close asChild>
            <IconButton className="aw-mobile-sidebar-close" label="关闭侧栏">
              <X aria-hidden="true" size={18} strokeWidth={1.8} />
            </IconButton>
          </Dialog.Close>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
