// End-to-end test of the Swing bridge hosting demo/src/legacy/InventoryFrame (a plain Swing app).
// Needs the bridge running (see README) and the xul-j base repo: XULJ_BASE (default ../xul-j).
// Run: node test/swing-e2e.js [http://127.0.0.1:8093]
'use strict';
const assert = require('assert');
const http = require('http');
const path = require('path');
const BASE = path.resolve(process.env.XULJ_BASE || path.join(__dirname, '../../xul-j'));
const { validate } = require(`${BASE}/protocol/validate`);
const { Model, renderText } = require(`${BASE}/protocol/model`);
const { stream, intent } = require(`${BASE}/clients/sse`);

const base = process.argv[2] || 'http://127.0.0.1:8093';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(pred, what, ms = 8000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (pred()) return; await sleep(25); }
  throw new Error(`timed out waiting for: ${what}`);
}
let passed = 0;
async function step(name, fn) { await fn(); passed++; console.log(`  ok  ${name}`); }

function request(method, url, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(url, { method, headers }, (res) => {
      let data = '';
      res.on('data', (c) => { data += c; });
      res.on('end', () => resolve({ status: res.statusCode, body: data, headers: res.headers }));
    });
    req.on('error', reject);
    req.end(body);
  });
}

function client(session) {
  const model = new Model();
  const ops = [];
  const conn = stream(base, session, { onOp: (op) => { ops.push(op); model.apply(op); } });
  const attr = (id, k) => { const n = model.ids.get(id); return n && model.resolved(n)[k]; };
  const rows = (src) => model.sources.get(src) || [];
  const send = async (msg, expect = 202) => {
    const r = await intent(base, session, msg);
    assert.strictEqual(r.status, expect, `${JSON.stringify(msg)} → ${r.status} ${r.body}`);
  };
  const dialog = () => model.root.children.find((w) => w.attrs.modal && !w.attrs.hidden);
  const inDialog = (pred) => {
    const d = dialog();
    const walk = (n) => [n, ...n.children.flatMap(walk)];
    return d && walk(d).find(pred);
  };
  const dialogButton = (label) => inDialog((n) => n.tag === 'button' && n.attrs.label === label);
  return { model, ops, conn, attr, rows, send, dialog, inDialog, dialogButton };
}

function show(c) {
  const w = c.dialog();
  console.log(renderText({ ...c.model, root: { tag: 'root', children: [w], attrs: {} }, resolved: c.model.resolved.bind(c.model) })
    .split('\n').map((l) => `      ${l}`).join('\n'));
}

(async () => {
  const session = `swing${Date.now().toString(36)}`;
  const a = client(session);

  await step('the Swing frame arrives as XUL-J; every op is schema-valid and seq is gapless', async () => {
    await until(() => a.model.ids.has('itemsTable') && a.attr('clockLabel', 'value'), 'frame rendered');
    a.ops.forEach((op, i) => {
      assert.deepStrictEqual(validate(op), [], JSON.stringify(op));
      assert.strictEqual(op.seq, i + 1);
    });
    assert.strictEqual(a.attr('InventoryFrame', 'label'), 'Legacy Inventory');
  });

  await step('layout managers map to boxes; ids come from the app’s field names', async () => {
    const win = a.model.ids.get('InventoryFrame');
    assert.deepStrictEqual(win.children.map((c) => c.tag), ['toolbar', 'vbox'], 'menu bar, content pane');
    const content = win.children[1].children.map((c) => c.tag);
    assert.deepStrictEqual(content, ['toolbar', 'tabbox', 'vbox'], 'BorderLayout north / center / south');
    assert.strictEqual(a.attr('nameField', 'flex'), 1, 'GridBag weightx + fill → flex');
    assert.strictEqual(a.model.ids.get('addButton').parent.children.map((c) => c.tag).join(), 'checkbox,spacer,button', 'anchor EAST → spacer');
    assert.strictEqual(a.attr('addButton', 'class'), 'primary', 'root pane default button');
    assert.strictEqual(a.model.commands.get('cmd_addButton').key, 'alt+a', 'mnemonic');
    assert.strictEqual(a.model.commands.get('cmd_importCsv').key, 'ctrl+o', 'menu accelerator');
    assert.strictEqual(a.attr('notesArea', 'multiline'), true);
    assert.strictEqual(a.attr('apiKeyField', 'password'), true);
    assert.strictEqual(a.model.ids.get('metricRadio').parent.tag, 'groupbox', 'TitledBorder → groupbox');
  });

  await step("the app's javax.swing.Timer drives live updates", async () => {
    const t0 = a.attr('clockLabel', 'value');
    await until(() => a.attr('clockLabel', 'value') !== t0, 'clock ticks', 3000);
  });

  await step('JOptionPane error → modal window with icon; OK closes it', async () => {
    await a.send({ op: 'do', command: 'cmd_addButton' });
    await until(() => a.dialog(), 'error dialog');
    show(a);
    assert.strictEqual(a.dialog().attrs.icon, 'error');
    assert.strictEqual(a.inDialog((n) => n.tag === 'description').attrs.value, 'Name is required.');
    assert.strictEqual(a.model.commands.get(`cmd_${a.dialog().id}__close`).key, 'escape');
    await a.send({ op: 'do', command: a.dialogButton('OK').attrs.command });
    await until(() => !a.dialog() && a.attr('statusLabel', 'value') === 'Item not added', 'dialog closed');
  });

  await step('typing into real components and clicking Add puts a row in the JTable', async () => {
    await a.send({ op: 'input', id: 'nameField', value: 'Hex bolts' });
    await a.send({ op: 'input', id: 'categoryCombo', value: '2' });
    await a.send({ op: 'input', id: 'qtySpinner', value: '7' });
    await a.send({ op: 'input', id: 'reorderCheck', value: true });
    await a.send({ op: 'do', command: 'cmd_addButton' });
    await until(() => a.rows('itemsTable').length === 1, 'table row');
    assert.deepStrictEqual(Object.values(a.rows('itemsTable')[0]), ['5001', 'Hex bolts', 'Garden', '7', 'Aisle 1']);
    await until(() => a.attr('statusLabel', 'value') === 'Added Hex bolts (reorder on)', 'status');
    assert.strictEqual(a.attr('nameField', 'value'), '', 'app cleared the field');
  });

  await step('radio buttons are clicked (ActionListeners run, ButtonGroup stays exclusive)', async () => {
    await a.send({ op: 'input', id: 'imperialRadio', value: true });
    await until(() => a.attr('statusLabel', 'value') === 'Units: imperial', 'listener ran');
    assert.strictEqual(a.attr('metricRadio', 'value'), false);
  });

  await step('JOptionPane.showInputDialog → prompt with a text box; the answer reaches the app', async () => {
    await a.send({ op: 'do', command: 'cmd_change' }); // a local variable in the app → id from its label
    await until(() => a.dialog() && a.inDialog((n) => n.tag === 'textbox'), 'input dialog');
    assert.strictEqual(a.dialog().attrs.icon, 'question');
    const box = a.inDialog((n) => n.tag === 'textbox');
    assert.strictEqual(box.attrs.value, 'Aisle 1');
    await a.send({ op: 'input', id: box.id, value: 'Dock B' });
    await a.send({ op: 'do', command: a.dialogButton('OK').attrs.command });
    await until(() => !a.dialog() && a.attr('statusLabel', 'value') === 'Location: Dock B', 'location changed');
  });

  await step('confirm dialog: Yes deletes the row', async () => {
    await a.send({ op: 'do', command: 'cmd_deleteButton' });
    await until(() => a.dialog(), 'confirm');
    assert.strictEqual(a.dialog().attrs.icon, 'warning');
    assert.strictEqual(a.inDialog((n) => n.tag === 'description').attrs.value, 'Delete "Hex bolts"?');
    assert.strictEqual(a.model.commands.get('cmd_deleteButton').disabled, false);
    await a.send({ op: 'do', command: a.dialogButton('Yes').attrs.command });
    await until(() => !a.dialog() && a.rows('itemsTable').length === 0, 'row deleted');
  });

  await step('Escape closes a modal dialog through the window manager', async () => {
    await a.send({ op: 'do', command: 'cmd_about' });
    await until(() => a.dialog() && a.dialog().attrs.icon === 'info', 'about');
    await a.send({ op: 'do', command: `cmd_${a.dialog().id}__close` });
    await until(() => !a.dialog(), 'closed');
  });

  await step('timer-driven restock: disabled button gets 409; 20 rows arrive', async () => {
    await a.send({ op: 'do', command: 'cmd_restockButton' });
    await until(() => a.model.commands.get('cmd_restockButton').disabled === true, 'disabled');
    await a.send({ op: 'do', command: 'cmd_restockButton' }, 409);
    await until(() => a.rows('restockLog').length === 20 && a.model.commands.get('cmd_restockButton').disabled === false, 'restock done', 15000);
    assert.strictEqual(a.rows('itemsTable').length, 20);
    assert.strictEqual(a.attr('restockProgress', 'value'), 1);
    assert.strictEqual(a.rows('itemsTable')[0].c4, 'Dock B');
  });

  await step('JFileChooser save → file name prompt, then the browser downloads the file', async () => {
    await a.send({ op: 'do', command: 'cmd_exportCsv' });
    await until(() => a.dialog() && a.inDialog((n) => n.tag === 'textbox'), 'save chooser');
    const name = a.inDialog((n) => n.tag === 'textbox');
    assert.strictEqual(name.attrs.value, 'inventory.csv');
    await a.send({ op: 'input', id: name.id, value: 'october' });
    await a.send({ op: 'do', command: a.dialogButton('Save').attrs.command });
    await until(() => a.model.transient.some((t) => t.op === 'download'), 'download op', 5000);
    const dl = a.model.transient.find((t) => t.op === 'download');
    assert.deepStrictEqual(validate(dl), []);
    assert.strictEqual(dl.name, 'october.csv', 'extension added from the file filter');
    const file = await request('GET', base + dl.url);
    assert.strictEqual(file.status, 200);
    const lines = file.body.trim().split('\n');
    assert.strictEqual(lines[0], 'sku,name,category,qty,location');
    assert.strictEqual(lines.length, 21);
    await until(() => a.attr('statusLabel', 'value') === 'Exported 20 items to october.csv', 'export status');
  });

  await step('JFileChooser open → browser upload; the app reads the file', async () => {
    await a.send({ op: 'do', command: 'cmd_importCsv' });
    await until(() => a.dialog() && a.inDialog((n) => n.tag === 'filepicker'), 'open chooser');
    const picker = a.inDialog((n) => n.tag === 'filepicker');
    assert.strictEqual(picker.attrs.accept, '.csv');
    const open = a.dialogButton('Open');
    assert.strictEqual(a.model.commands.get(open.attrs.command).disabled, true, 'Open waits for a file');
    const csv = 'name,category,qty\nWashers,Hardware,100\nRake,Garden,3\nbroken\n';
    const up = await request('POST', `${base}/upload?session=${session}&id=${picker.id}`, csv, { 'X-Filename': encodeURIComponent('stock take.csv') });
    assert.strictEqual(up.status, 202, up.body);
    await until(() => a.attr(picker.id, 'value') === 'stock take.csv' && a.model.commands.get(open.attrs.command).disabled === false, 'uploaded');
    await a.send({ op: 'do', command: open.attrs.command });
    await until(() => a.rows('itemsTable').length === 22, 'rows imported');
    await until(() => a.dialog() && a.dialog().attrs.icon === 'warning', 'skipped warning');
    await a.send({ op: 'do', command: a.dialogButton('OK').attrs.command });
    await until(() => !a.dialog() && a.attr('statusLabel', 'value') === 'Imported 2 items from stock take.csv', 'import status');
    assert.strictEqual((await request('POST', `${base}/upload?session=${session}&id=nameField`, 'x', { 'X-Filename': 'x' })).status, 404);
  });

  await step('a second browser session gets its own frame instance', async () => {
    const b = client(`${session}b`);
    await until(() => b.model.ids.has('itemsTable') && b.attr('clockLabel', 'value'), 'second frame');
    assert.strictEqual(b.rows('itemsTable').length, 0);
    assert.strictEqual(a.rows('itemsTable').length, 22);
    b.conn.close();
  });

  await step('a reconnecting client resumes from its last seq', async () => {
    a.conn.close();
    const last = a.model.lastSeq;
    await a.send({ op: 'input', id: 'nameField', value: 'typed while offline' });
    const resumed = [];
    a.conn = stream(base, session, { from: last, onOp: (op) => { resumed.push(op); a.model.apply(op); } });
    await until(() => a.attr('nameField', 'value') === 'typed while offline', 'missed update replayed');
    assert(resumed.every((op) => op.seq > last));
  });

  await step('File › Exit calls System.exit: the session ends, the bridge keeps serving', async () => {
    await a.send({ op: 'do', command: 'cmd_exit' });
    await until(() => a.model.ids.has('ended') && !a.model.ids.has('InventoryFrame'), 'ended');
    const c = client(`${session}c`);
    await until(() => c.model.ids.has('itemsTable'), 'bridge still alive');
    c.conn.close();
  });

  console.log(`\n${passed} checks passed.`);
  a.conn.close();
  process.exit(0);
})().catch((e) => { console.error('FAIL', e); process.exit(1); });
