const mysql = require('/home/z/my-project/workflow_lowcode/backend-node/node_modules/mysql2/promise');
(async () => {
  const conn = await mysql.createConnection({ host: '127.0.0.1', port: 3306, user: 'root', password: '740130', socketPath: '/home/z/my-project/mariadb-user/mysql.sock' });
  // 关键业务表清单
  const tables = ['wf_form_def', 'wf_form_data', 'wf_form_release', 'wf_process_def', 'wf_model', 'wf_category', 'wf_data_source', 'sys_user', 'sys_role', 'sys_menu', 'wf_task', 'wf_instance'];
  for (const dbn of ['workflow', 'workflow_v6']) {
    console.log(`\n===== ${dbn} =====`);
    for (const t of tables) {
      try {
        const [r] = await conn.query('SELECT COUNT(*) c FROM ??.??', [dbn, t]);
        if (r[0].c > 0) console.log(`  ${t}: ${r[0].c} 行`);
      } catch (e) { /* 表不存在跳过 */ }
    }
    // 所有 wf_biz_* 业务数据表
    try {
      const [biz] = await conn.query("SELECT table_name, table_rows FROM information_schema.tables WHERE table_schema=? AND table_name LIKE 'wf_biz%'", [dbn]);
      let total = 0;
      for (const b of biz) { total += (b.table_rows || 0); }
      console.log(`  wf_biz_* 业务数据表: ${biz.length} 张,约 ${total} 行`);
      if (biz.length && biz.length <= 12) for (const b of biz) {
        const [c] = await conn.query(`SELECT COUNT(*) c FROM \`${dbn}\`.\`${b.table_name}\``);
        console.log(`    ${b.table_name}: ${c[0].c} 行`);
      }
    } catch (e) { console.log('  biz ERR', e.message.slice(0, 60)); }
  }
  await conn.end();
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
