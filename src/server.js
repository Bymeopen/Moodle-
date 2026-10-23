import { createApp } from './app.js';
import { openDb } from './db.js';

const port = Number(process.env.PORT) || 3000;
createApp(openDb()).listen(port, () => console.log(`Mini LMS berjalan di http://localhost:${port}`));
