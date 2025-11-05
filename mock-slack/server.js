
const express = require('express');
const app = express();
const PORT = process.env.PORT || 3000;

app.use(express.json());

// Store received notifications
const notifications = [];

app.post('/webhook', (req, res) => {
  console.log('📩 Received Slack notification:');
  console.log(JSON.stringify(req.body, null, 2));

  notifications.push({
    timestamp: new Date().toISOString(),
    payload: req.body
  });

  res.status(200).json({ ok: true });
});

app.get('/notifications', (req, res) => {
  res.json({
    count: notifications.length,
    notifications: notifications
  });
});

app.get('/health', (req, res) => {
  res.json({ status: 'healthy' });
});

app.listen(PORT, () => {
  console.log(`Mock Slack webhook receiver running on port ${PORT}`);
  console.log(`Endpoint: http://localhost:${PORT}/webhook`);
});

