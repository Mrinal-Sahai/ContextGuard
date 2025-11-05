document.addEventListener('DOMContentLoaded', () => {
  const backendUrlInput = document.getElementById('backend-url');
  const authTokenInput = document.getElementById('auth-token');
  const saveBtn = document.getElementById('save-btn');
  const statusMessage = document.getElementById('status-message');

  // Load saved settings
  chrome.storage.sync.get(['backendUrl', 'authToken'], (result) => {
    backendUrlInput.value = result.backendUrl || 'http://localhost:8080';
    authTokenInput.value = result.authToken || '';
  });

  // Save settings
 saveBtn.addEventListener('click', () => {
    const backendUrl = backendUrlInput.value.trim();
    const authToken = authTokenInput.value.trim();

    if (!backendUrl) {
      showStatus('Backend URL is required', 'error');
      return;
    }

    chrome.storage.sync.set({
      backendUrl: backendUrl,
      authToken: authToken
    }, () => {
      showStatus('Settings saved successfully! Reload PR pages to apply.', 'success');
    });
  });

  function showStatus(message, type) {
    statusMessage.textContent = message;
    statusMessage.className = `status-message ${type}`;
    statusMessage.style.display = 'block';

    setTimeout(() => {
      statusMessage.style.display = 'none';
    }, 5000);
  }
});

