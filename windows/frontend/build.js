const fs = require('node:fs');
const path = require('node:path');

const source = path.join(__dirname, 'index.html');
const output = path.join(__dirname, 'dist');
fs.mkdirSync(output, { recursive: true });
fs.copyFileSync(source, path.join(output, 'index.html'));
