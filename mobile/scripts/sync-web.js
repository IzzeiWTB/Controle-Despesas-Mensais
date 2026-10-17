// Use the same explicit public-file list as the hosted app. Never copy secrets.
import('../../scripts/sync-app.mjs').catch(error => {
  console.error(error);
  process.exitCode = 1;
});
