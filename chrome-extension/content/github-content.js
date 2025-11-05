console.log('ContextGuard: GitHub content script loaded');

class GitHubContextGuard {
  constructor() {
    console.log('ContextGuard: GitHubContextGuard constructor');
    this.config = null;
    this.sidebar = null;
    this.reviewData = null;
  }

  async init() {
    console.log('ContextGuard: GitHubContextGuard init');
    this.config = await this.getConfig();
    const prInfo = this.extractPRInfo();
    
    if (!prInfo) {
      console.log('ContextGuard: Not a PR page');
      return;
    }

    console.log('ContextGuard: Detected PR', prInfo);
    await this.fetchReviewData(prInfo);
    this.injectSidebar();
  }

  async getConfig() {
    return new Promise((resolve) => {
      chrome.runtime.sendMessage(
        { action: 'getConfig' },
        (response) => resolve(response)
      );
    });
  }

  extractPRInfo() {
    const url = window.location.pathname;
    const match = url.match(/^\/([^\/]+)\/([^\/]+)\/pull\/(\d+)/);

    if (!match) return null;

    return {
      platform: 'github',
      owner: match[1],
      repo: match[2],
      externalId: match[3]
    };
  }

  async fetchReviewData(prInfo) {
    const { platform, owner, repo, externalId } = prInfo;
    const apiUrl = `${this.config.backendUrl}/v1/reviews/${platform}/${owner}/${repo}/${externalId}`;

    try {
      const headers = {};
      if (this.config.token) {
        headers['Authorization'] = `Bearer ${this.config.token}`;
      }

      const response = await fetch(apiUrl, { headers });

      if (response.ok) {
        this.reviewData = await response.json();
        console.log('ContextGuard: Fetched review data', this.reviewData);
      } else if (response.status === 404) {
        console.log('ContextGuard: No context artifact found');
        this.reviewData = null;
      } else {
        console.error('ContextGuard: API error', response.status);
      }
    } catch (error) {
      console.error('ContextGuard: Failed to fetch review data', error);
    }
  }

  escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  injectSidebar() {
    this.sidebar = document.createElement('div');
    this.sidebar.id = 'contextguard-sidebar';
    this.sidebar.className = 'contextguard-sidebar';

    if (this.reviewData && this.reviewData.latestSnapshot) {
      this.sidebar.innerHTML = this.buildSidebarWithContext();
    } else {
      this.sidebar.innerHTML = this.buildSidebarNoContext();
    }

    const mainContent = document.querySelector('.Layout-main') ||
                       document.querySelector('main') ||
                       document.body;
    mainContent.appendChild(this.sidebar);

    this.attachEventListeners();
    setTimeout(() => this.sidebar.classList.add('expanded'), 100);
  }

  buildSidebarWithContext() {
    const snapshot = this.reviewData.latestSnapshot;
    const score = snapshot.contextScore || 0;
    const scoreClass = score >= 70 ? 'high' : score >= 40 ? 'medium' : 'low';

    return `
      <div class="cg-header">
        <div class="cg-logo">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M12 2L2 7v10l10 5 10-5V7L12 2z" stroke="currentColor" stroke-width="2"/>
          </svg>
          <span>ContextGuard</span>
        </div>
        <button class="cg-toggle" id="cg-toggle">▼</button>
      </div>

      <div class="cg-content" id="cg-content">
        <div class="cg-section">
          <div class="cg-score ${scoreClass}">
            <div class="cg-score-value">${score}</div>
            <div class="cg-score-label">Context Score</div>
          </div>
        </div>

        <div class="cg-section">
          <h3>Summary</h3>
          <p class="cg-summary">${this.escapeHtml(snapshot.summary || 'No summary available')}</p>
        </div>

        <div class="cg-section">
          <h3>Why</h3>
          <p class="cg-why">${this.escapeHtml(snapshot.why || 'Not specified')}</p>
        </div>

        <div class="cg-section">
          <h3>Review Checklist</h3>
          <ul class="cg-checklist">
            ${(snapshot.reviewChecklist || []).map(item =>
              `<li><input type="checkbox"> ${this.escapeHtml(item)}</li>`
            ).join('')}
          </ul>
        </div>

        ${snapshot.risks && snapshot.risks.length > 0 ? `
          <div class="cg-section">
            <h3>⚠️ Risks</h3>
            <ul class="cg-risks">
              ${snapshot.risks.map(risk =>
                `<li>${this.escapeHtml(risk)}</li>`
              ).join('')}
            </ul>
          </div>
        ` : ''}

        <div class="cg-section">
          <div class="cg-meta">
            <small>Created: ${new Date(snapshot.createdAt).toLocaleDateString()}</small>
            <small>By: ${snapshot.createdBy}</small>
          </div>
        </div>
      </div>
    `;
  }

  buildSidebarNoContext() {
    const prInfo = this.extractPRInfo();
    const generateUrl = prInfo ?
      `${this.config.backendUrl}/v1/reviews/${prInfo.platform}/${prInfo.owner}/${prInfo.repo}/${prInfo.externalId}/generate` :
      null;

    return `
      <div class="cg-header">
        <div class="cg-logo">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M12 2L2 7v10l10 5 10-5V7L12 2z" stroke="currentColor" stroke-width="2"/>
          </svg>
          <span>ContextGuard</span>
        </div>
        <button class="cg-toggle" id="cg-toggle">▼</button>
      </div>

      <div class="cg-content" id="cg-content">
        <div class="cg-section cg-empty">
          <div class="cg-empty-icon">📋</div>
          <h3>No Context Available</h3>
          <p>This PR doesn't have a context artifact yet.</p>
          ${generateUrl ? `
            <button class="cg-generate-btn" id="cg-generate">
              Generate Context
            </button>
          ` : ''}
        </div>
      </div>
    `;
  }

  attachEventListeners() {
    const toggleBtn = this.sidebar.querySelector('#cg-toggle');
    const content = this.sidebar.querySelector('#cg-content');

    if (toggleBtn) {
      toggleBtn.addEventListener('click', () => {
        content.classList.toggle('collapsed');
        toggleBtn.textContent = content.classList.contains('collapsed') ? '▲' : '▼';
      });
    }

    const generateBtn = this.sidebar.querySelector('#cg-generate');
    if (generateBtn) {
      generateBtn.addEventListener('click', () => this.generateContext());
    }
  }

  async generateContext() {
    const prInfo = this.extractPRInfo();
    const apiUrl = `${this.config.backendUrl}/v1/reviews/${prInfo.platform}/${prInfo.owner}/${prInfo.repo}/${prInfo.externalId}/generate`;

    const generateBtn = this.sidebar.querySelector('#cg-generate');
    generateBtn.textContent = 'Generating...';
    generateBtn.disabled = true;

    try {
      const headers = {};
      if (this.config.token) {
        headers['Authorization'] = `Bearer ${this.config.token}`;
      }

      const response = await fetch(apiUrl, {
        method: 'POST',
        headers
      });

      if (response.ok) {
        window.location.reload();
      } else {
        alert('Failed to generate context. Please try again.');
        generateBtn.textContent = 'Generate Context';
        generateBtn.disabled = false;
      }
    } catch (error) {
      console.error('ContextGuard: Generate failed', error);
      alert('Network error. Please check your backend connection.');
      generateBtn.textContent = 'Generate Context';
      generateBtn.disabled = false;
    }
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => {
    new GitHubContextGuard().init();
  });
} else {
  new GitHubContextGuard().init();
}