const fs = require('fs');
const path = require('path');

const source = path.resolve(__dirname, '../../private-app');
const target = path.resolve(__dirname, '../www');

fs.rmSync(target, { recursive: true, force: true });
fs.mkdirSync(target, { recursive: true });
for (const file of fs.readdirSync(source)) {
  fs.cpSync(path.join(source, file), path.join(target, file), { recursive: true });
}
console.log(`Web app copied to ${target}`);
