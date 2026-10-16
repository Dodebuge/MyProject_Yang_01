// 알림 서비스 워커 (volume.html이 https·localhost에서 등록). 서버가 /sw.js 로 제공합니다.
// 안드로이드 크롬 등은 페이지에서 new Notification()을 막아, 페이지가 이 워커의 showNotification으로 띄웁니다.
// 알림을 누르면 그 종목 차트를 엽니다 (data.url).
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", e => e.waitUntil(self.clients.claim()));
self.addEventListener("notificationclick", e => {
  e.notification.close();
  const url = e.notification.data && e.notification.data.url;
  if (url) e.waitUntil(self.clients.openWindow(url));
});
