console.log('ContextGuard: Beanstalk content script loaded');

class BeanstalkContextGuard {
  constructor() {
    this.config = null;
    this.sidebar = null;
    this.reviewData = null;
  }

  async init() {
    this.config = await this.getConfig();

    const reviewInfo = this.extractReviewInfo();
    if (!reviewInfo) {
      console.log('ContextGuard: Not a review page');
      return;
 }

    console.log('ContextGuard: Detected review', reviewInfo);

    await this.fetchReviewData(reviewInfo);
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

  extractReviewInfo() {
    const url = window.location.pathname;
    const match = url.match(/\/([^\/]+)\/changesets\/(\d+)/);

    if (!match) return null;

    return {
      platform: 'beanstalk',
      owner: match[1],
      repo: match[1], // Simplified for demo
      externalId: match[2]
    };
  }

async fetchReviewData(prInfo) {
  const { platform, owner, repo, externalId } = prInfo;

  try {
    const response = await new Promise((resolve, reject) => {
      chrome.runtime.sendMessage(
        {
          action: 'fetchApi',
          method: 'GET',
          url: `/v1/reviews/${platform}/${owner}/${repo}/${externalId}`,
          token: this.config.token
        },
        (res) => {
          if (res.error) reject(res.error);
          else resolve(res);
        }
      );
    });

    this.reviewData = response;
    console.log('ContextGuard: Fetched review data', this.reviewData);
  } catch (error) {
    console.error('ContextGuard: Failed to fetch review data', error);
    this.reviewData = null;
  }
}

  injectSidebar() {
    // Similar implementation to GitHub
    // Reuse the same sidebar structure
    this.sidebar = document.createElement('div');
    this.sidebar.id = 'contextguard-sidebar';
    this.sidebar.className = 'contextguard-sidebar';

    if (this.reviewData && this.reviewData.latestSnapshot) {
      this.sidebar.innerHTML = this.buildSidebarWithContext();
    } else {
      this.sidebar.innerHTML = this.buildSidebarNoContext();
    }

    document.body.appendChild(this.sidebar);
    setTimeout(() => this.sidebar.classList.add('expanded'), 100);
  }

  buildSidebarWithContext() {
    // Same as GitHub implementation
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
          <p class="cg-summary">${snapshot.summary || 'No summary'}</p>
        </div>

        <div class="cg-section">
          <h3>Why</h3>
          <p class="cg-why">${snapshot.why || 'Not specified'}</p>
        </div>

        <div class="cg-section">
          <h3>Review Checklist</h3>
          <ul class="cg-checklist">
            ${(snapshot.reviewChecklist || []).map(item =>
              `<li><input type="checkbox"> ${item}</li>`
            ).join('')}
          </ul>
        </div>
      </div>
    `;
  }

  buildSidebarNoContext() {
    return `
      <div class="cg-header">
        <div class="cg-logo">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M12 2L2 7v10l10 5 10-5V7L12 2z" stroke="currentColor" stroke-width="2"/>
          </svg>
          <span>ContextGuard</span>
        </div>
      </div>

      <div class="cg-content">
        <div class="cg-section cg-empty">
          <p>No context available for this review.</p>
        </div>
      </div>
    `;
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => {
    new BeanstalkContextGuard().init();
  });
} else {
  new BeanstalkContextGuard().init();
}
