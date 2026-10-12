const mysql = require('/home/z/my-project/workflow_lowcode/backend-node/node_modules/mysql2/promise');
(async () => {
  const conn = await mysql.createConnection({ host: '127.0.0.1', port: 3306, user: 'root', password: '740130', socketPath: '/home/z/my-project/mariadb-user/mysql.sock' });
  const [db] = await conn.query("SHOW DATABASES");
  console.log('DBs:', db.map(r => r.Database).join(','));
  for (const dbn of ['workflow', 'workflow_v6']) {
    try {
      const [t] = await conn.query('SHOW TABLES FROM ??', [dbn]);
      console.log(`\n[${dbn}] 表数: ${t.length}`);
      const [u] = await conn.query('SELECT COUNT(*) c FROM ?? . ??', [dbn, 'sys_user']).catch(e => [[{ c: 'ERR:' + e.code }]]);
      console.log(`${dbn}.sys_user 行数:`, u[0].c);
    } catch (e) { console.log(`[${dbn}] ERR:`, e.message.slice(0, 80)); }
  }
  const [users] = await conn.query("SELECT COUNT(*) c FROM workflow.`SYS_USER`").catch(e => [[{ c: 'ERR ' + e.message.slice(0, 60) }]]);
  console.log('workflow.SYS_USER 行数:', users[0].c);
  await conn.end();
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
