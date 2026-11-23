'use strict';
const { createRelay } = require('./relay');

const port = Number(process.env.PORT || 8080);
const relay = createRelay({
  trustProxy: process.env.TRUST_PROXY === '1',
});

relay.server.listen(port, '0.0.0.0', () => {
  // No packet contents and no addresses are ever logged.
  console.log(`Rider Intercom relay listening on :${port}`);
});

function stop() {
  relay.close().then(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
