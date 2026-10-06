// An agent's-eye test: the XUL-J MCP server (from the base repo) operating the Swing demo
// through java-bridge. Needs the bridge running and ../xul-j (XULJ_BASE).
// Run: node test/mcp-e2e.js [http://127.0.0.1:8093]
'use strict';
const assert = require('assert');
const path = require('path');
const readline = require('readline');
const { spawn } = require('child_process');
const BASE = path.resolve(process.env.XULJ_BASE || path.join(__dirname, '../../xul-j'));
const url = process.argv[2] || 'http://127.0.0.1:8093';

const child = spawn(process.execPath, [path.join(BASE, 'mcp/server.js'), '--url', url], { stdio: ['pipe', 'pipe', 'ignore'] });
const pending = new Map();
let next = 1;
readline.createInterface({ input: child.stdout }).on('line', (l) => { const m = JSON.parse(l); pending.get(m.id)?.(m); pending.delete(m.id); });
const rpc = (method, params) => new Promise((r) => { const id = next++; pending.set(id, r); child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`); });
const call = async (name, args = {}) => {
  const r = (await rpc('tools/call', { name, arguments: args })).result;
  if (r.isError) throw new Error(`${name} failed: ${r.content[0].text}`);
  return r.content[0].text;
};
const find = (text, re, what) => { const m = re.exec(text); assert(m, `${what} not found in:\n${text}`); return m; };

let passed = 0;
async function step(name, fn) { await fn(); passed++; console.log(`  ok  ${name}`); }

(async () => {
  await rpc('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'agent-test', version: '1' } });
  let ui;

  await step('the agent reads the Swing app as an outline', async () => {
    ui = await call('wait_for', { text: 'Legacy Inventory' });
    find(ui, /window "Legacy Inventory" \[InventoryFrame\]/, 'window');
    find(ui, /item "Import CSV…" \[importCsv\] → cmd_importCsv \(ctrl\+o\)/, 'menu item');
    find(ui, /table \[itemsTable\] 0 rows; columns: SKU, Name, Category, Qty, Location; multiple selection; right-click menu \[[\w.-]+\]/, 'table');
  });

  await step('an error dialog, then a correctly filled form', async () => {
    let r = await call('do_command', { command: 'cmd_addButton' });
    find(r, /A modal dialog is open: "Cannot add item"/, 'error dialog');
    ui = await call('get_ui');
    find(ui, /window "Cannot add item" \[[\w.-]+\] MODAL DIALOG \(error\)/, 'error icon');
    await call('do_command', { command: find(ui, /button "OK" \[[\w.-]+\] → (cmd_[\w.-]+)/, 'OK')[1] });
    await call('set_value', { id: 'nameField', value: 'Wing nuts' });
    await call('set_value', { id: 'categoryCombo', value: 'Tools' });
    await call('set_value', { id: 'qtySpinner', value: 12 });
    r = await call('do_command', { command: 'cmd_addButton' });
    find(r, /~ table \[itemsTable\] 1 rows/, 'row added');
    const t = JSON.parse(await call('read_table', { id: 'itemsTable' }));
    assert.deepStrictEqual([t.rows[0].Name, t.rows[0].Category, t.rows[0].Qty], ['Wing nuts', 'Tools', '12']);
  });

  await step('right-click menu via the app\'s PopupMenuListener, then an input dialog', async () => {
    await call('select_rows', { id: 'itemsTable', rows: [0] });
    const menu = await call('open_context_menu', { id: 'itemsTable' });
    find(menu, /item "Duplicate item" \[duplicateItem\] → cmd_duplicateItem$/m, 'duplicate enabled');
    let r = await call('do_command', { command: 'cmd_duplicateItem' });
    find(r, /~ table \[itemsTable\] 2 rows/, 'duplicated');
    r = await call('do_command', { command: 'cmd_change' });
    ui = await call('get_ui');
    const box = find(ui, /MODAL DIALOG \(question\)[\s\S]*?textbox \[([\w.-]+)\] = "Aisle 1"/, 'input box')[1];
    await call('set_value', { id: box, value: 'Dock 7' });
    r = await call('do_command', { command: find(ui, /button "OK" \[[\w.-]+\] → (cmd_[\w.-]+)/, 'OK')[1] });
    find(r, /~ label "Location: Dock 7"/, 'answer reached the app');
  });

  console.log(`\n${passed} checks passed.`);
  child.kill();
  process.exit(0);
})().catch((e) => { console.error('FAIL', e.message); child.kill(); process.exit(1); });
