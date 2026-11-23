'use strict';
const { createRelay } = require('./relay');

const port = Number(process.env.PORT || 8080);
// Behind a tunnel or reverse proxy on the same machine, set HOST=127.0.0.1 so
// nothing else on the network can reach the relay directly.
const host = process.env.HOST || '0.0.0.0';
const relay = createRelay({
  trustProxy: process.env.TRUST_PROXY === '1',
});

relay.server.listen(port, host, () => {
  // No packet contents and no addresses are ever logged.
  console.log(`Rider Intercom relay listening on ${host}:${port}`);
});

function stop() {
  relay.close().then(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
