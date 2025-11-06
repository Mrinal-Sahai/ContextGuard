// background.js (service worker)

console.log('ContextGuard ServiceWorker loaded');

// Ensure default backend URL is set on install
chrome.runtime.onInstalled.addListener(() => {
  console.log('ContextGuard extension installed');

  // Use storage.sync if available, fallback to local
  const storage = chrome.storage?.sync || chrome.storage.local;

  storage.get(['backendUrl'], (result) => {
    if (!result.backendUrl) {
      storage.set({ backendUrl: 'http://localhost:8080' }, () => {
        console.log('Default backendUrl set to http://localhost:8080');
      });
    }
  });
});

// Handle messages from content scripts
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  const storage = chrome.storage?.sync || chrome.storage.local;

  switch (request.action) {
    case 'getConfig':
      console.log('ServiceWorker: getConfig request received');
      storage.get(['authToken', 'backendUrl'], (result) => {
        sendResponse({
          token: result.authToken || null,
          backendUrl: result.backendUrl || 'http://localhost:8080'
        });
      });
      return true; // Keep channel open for async response

    case 'saveConfig':
      storage.set(
        {
          authToken: request.token,
          backendUrl: request.backendUrl
        },
        () => {
          console.log('ServiceWorker: Configuration saved', request);
          sendResponse({ success: true });
        }
      );
      return true; // Keep channel open for async response

    default:
      console.warn('ServiceWorker: Unknown action', request.action);
      break;
  }
});


chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.action === 'fetchApi') {
    chrome.storage.sync.get(['backendUrl'], async (result) => {
      const url = result.backendUrl + request.url;
      const headers = {};
      if (request.token) headers['Authorization'] = `Bearer ${request.token}`;

      try {
        const res = await fetch(url, { method: request.method, headers });
        const data = await res.json();
        sendResponse(data);
      } catch (err) {
        sendResponse({ error: err.message });
      }
    });
    return true; // keep the message channel open for async response
  }
});

