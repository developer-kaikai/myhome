const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const source = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../miniprogram/pages/ordering/index.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
const orderExports = {};
vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '../miniprogram/services/orders.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText, {exports:orderExports});
class ApiError extends Error { constructor(code, message) { super(message); this.code = code; } }
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
function cart(version = 1, quantity = 1) {
  return { orderId: 10, restaurantId: 1, date: '2026-10-03', meal: 'DINNER', cutoff: '2026-10-03T13:00:00Z', serverTime: '2026-10-03T10:00:00Z', status: 'DRAFT', version,
    submittedItems: [], items: [{ id: 20, dishId: 30, dishName: '牛腩', contributorId: 40, contributorName: '朋友', selections: [{ dimensionId: 50, dimensionName: '辣度', optionId: 60, optionName: '微辣' }], quantity, version }], dishCount: 1, quantity, contributorCount: 1 };
}
function page(orderingRequest) {
  let p, cleared = 0;
  const imports = {
    '../../services/api': { request: async () => ({}), dailyGuard: async () => ({}), message: e => e.message, ApiError },
    '../../services/menu': { key: () => 'test_same_operation_key', confirm: async () => true },
    '../../services/orders': orderExports,
    '../../services/cart': { orderingRequest, mealNames: { BREAKFAST: '早餐', LUNCH: '午餐', DINNER: '晚餐', SUPPER: '宵夜' } }
  };
  vm.runInNewContext(source, { exports: {}, require: name => imports[name], Page: definition => { p = definition; }, wx: { showToast() {} }, setInterval: () => 1, clearInterval: () => { cleared++; }, console });
  p.getTabBar = () => ({ setData() {} });
  p.setData = patch => Object.assign(p.data, patch);
  p._visible = true;
  p.setData({ restaurants: [{ id: 1, name: '我的厨房' }, { id: 2, name: '她的餐厅' }], date: '2026-10-03', meal: 'DINNER', cart: cart() });
  return { p, cleared: () => cleared };
}
test('切店后忽略旧餐厅的迟到响应', async () => {
  const d = deferred(); const { p } = page(() => d.promise);
  const sync = p.sync(); p._generation++; p.setData({ restaurantIndex: 1, cart: null });
  d.resolve(cart()); await sync; assert.equal(p.data.cart, null);
});
test('变更后的强制刷新胜过已有轮询的旧快照', async () => {
  const first = deferred(), second = deferred(); let calls = 0;
  const { p } = page(() => ++calls === 1 ? first.promise : second.promise);
  const old = p.sync(), fresh = p.sync(true);
  second.resolve(cart(5, 5)); await fresh; first.resolve(cart(1, 1)); await old;
  assert.equal(p.data.cart.version, 5); assert.equal(p.data.cart.quantity, 5);
});
test('超时后保留操作键，轮询不隐藏重试入口，重试先核对清单', async () => {
  let fail = true; const calls = [];
  const { p } = page(async (url, method = 'GET', body, headers) => {
    calls.push({ url, method, headers });
    if (method === 'GET') return cart(2, 2);
    if (fail) { fail = false; throw new ApiError('NETWORK_ERROR', '网络中断'); }
    return { orderId: 10, version: 2 };
  });
  const operation = { path: '/meal-orders/10/items/20/quantity', method: 'POST', body: { delta: 1 }, key: 'same_operation_key_123' };
  await p.write(operation); assert.equal(p.data.retryPending, true);
  await p.sync(); assert.equal(p.data.retryPending, true); assert.match(p.data.syncError, /待核对/);
  await p.retry(); assert.equal(p.data.retryPending, false);
  const writes = calls.filter(c => c.method === 'POST');
  assert.equal(writes.length, 2); assert.equal(writes[0].headers['Idempotency-Key'], writes[1].headers['Idempotency-Key']);
  assert.equal(calls[2].method, 'GET'); assert.equal(calls[3].method, 'POST');
});
test('规格冲突保留输入，核对后用最新份数和版本再次保存', async () => {
  const { p } = page(async (url, method = 'GET') => {
    if (method === 'GET') return cart(3, 4);
    throw new ApiError('VERSION_CONFLICT', '内容已更新');
  });
  p._editing = { item: cart().items[0], version: 1 };
  p.setData({ choices: [{ id: 50, options: [{ id: 61, selected: true }] }] });
  await p.write({ path: '/meal-orders/10/items/20', method: 'PUT', body: {}, key: null });
  assert.equal(p._editing.version, 3); assert.equal(p._editing.item.quantity, 4);
  assert.equal(p.data.choices[0].options[0].id, 61);
});
test('页面隐藏时停止轮询，后台不继续获取清单', async () => {
  let calls = 0; const { p, cleared } = page(async () => { calls++; return cart(); });
  p._timer = 1; p.onHide(); await p.sync(); assert.equal(calls, 0); assert.equal(cleared(), 1); assert.equal(p._timer, 0);
});
