chrome.runtime.onInstalled.addListener(() => {
  console.log('ContextGuard extension installed');

  // Set default backend URL
  chrome.storage.sync.get(['backendUrl'], (result) => {
        console.log('123453688');
    if (!result.backendUrl) {
      chrome.storage.sync.set({
        backendUrl: 'http://localhost:8080'
      });
    }
  });
});

// Handle messages from content scripts
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.action === 'getConfig') {
    console.log('ServiceWorker: getConfig request received');
    chrome.storage.sync.get(['authToken', 'backendUrl'], (result) => {
      sendResponse({
        token: result.authToken || null,
        backendUrl: result.backendUrl || 'http://localhost:8080'
      });
    });
    return true; // Keep channel open for async response
  }

  if (request.action === 'saveConfig') {
    chrome.storage.sync.set({
      authToken: request.token,
      backendUrl: request.backendUrl
    }, () => {
      sendResponse({ success: true });
    });
  return true;
  }
});
