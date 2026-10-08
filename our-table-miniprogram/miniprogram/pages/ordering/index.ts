import { request, dailyGuard, message, ApiError } from '../../services/api';
import { Menu, Restaurant, Category, Dish, key, confirm } from '../../services/menu';
import { selections } from '../../services/orders';
import { orderingRequest, Slots, Slot, Meal, Cart, CartItem, Mutation, mealNames } from '../../services/cart';
interface Pending { path: string; method: 'POST' | 'PUT' | 'DELETE'; body: object; key: string | null }
const meals: Meal[] = ['BREAKFAST', 'LUNCH', 'DINNER', 'SUPPER'];
Page({
  data: { ready: false, loading: false, busy: false, error: '', syncError: '', retryPending: false,
    restaurants: [] as Restaurant[], restaurantIndex: 0, date: '', meal: 'DINNER' as Meal,
    dates: [] as string[], dateIndex: 0, mealTabs: [] as { meal: Meal; name: string; open: boolean }[],
    categories: [] as Category[], categoryId: 0, dishes: [] as Dish[], visibleDishes: [] as Dish[],
    cart: null as Cart | null, cartItems: [] as (CartItem & { specText: string })[], cartOpen: false, submittedItems: [] as (CartItem & { specText: string })[],
    dish: null as Dish | null, choices: [] as { id: number; name: string; options: { id: number; name: string; selected: boolean }[] }[],
    editing: false, closed: false, accessRequired: false },
  _targetRestaurant: null as number|null, _targetDish: null as number|null,
  _visible: false, _generation: 0, _polling: false, _syncSeq: 0, _timer: 0,
  _slots: [] as Slot[], _pending: null as Pending | null,
  _editing: null as { item: CartItem; version: number } | null,
  async onShow() {
    this._visible = true;
    if (!await dailyGuard()) { this.setData({ ready: false }); return; }
    if (!this._visible) return;
    this.getTabBar()?.setData({ selected: 'ordering', daily: true, overlayHidden: !!this.data.dish || this.data.cartOpen });
    const target = wx.getStorageSync('orderingTarget') as {restaurantId:number;dishId?:number;date?:string;meal?:Meal}|null;
    if(target&&!this.data.busy&&!this.data.retryPending){this._targetRestaurant=target.restaurantId;this._targetDish=target.dishId||null;this.setData({date:target.date||this.data.date,meal:target.meal||this.data.meal,dish:null,cartOpen:false});wx.removeStorageSync('orderingTarget');}
    this.setData({ ready: true }); if(target&&(this.data.busy||this.data.retryPending))this.setData({syncError:'请先核对上一次选菜操作，再重新进入时令推荐。'}); await this.load();
    if (this._visible) { this.stopTimer(); this._timer = setInterval(() => { void this.sync(); }, 2000); }
  },
  onHide() { this._visible = false; this._generation++; this.stopTimer(); this.getTabBar()?.setData({ overlayHidden: false }); },
  onUnload() { this._visible = false; this._generation++; this.stopTimer(); this.getTabBar()?.setData({ overlayHidden: false }); },
  syncTabVisibility() { if (!this._visible) return; this.getTabBar()?.setData({ overlayHidden: !!this.data.dish || this.data.cartOpen }); },
  stopTimer() { if (this._timer) clearInterval(this._timer); this._timer = 0; },
  slotPath() { const r = this.data.restaurants[this.data.restaurantIndex]; return `/meal-slots/${r.id}/${this.data.date}/${this.data.meal}`; },
  tabs(date: string) { return meals.map(meal => ({ meal, name: mealNames[meal], open: this._slots.some(s => s.date === date && s.meal === meal) })); },
  async load() {
    if (this.data.busy || this.data.retryPending) return;
    const generation = ++this._generation;
    this.setData({ loading: true, error: '', categories: [], dishes: [], visibleDishes: [], cart: null, cartItems: [], submittedItems: [] });
    try {
      const [restaurants, options] = await Promise.all([request<Restaurant[]>('/restaurants'), orderingRequest<Slots>('/meal-slots')]);
      if (!this._visible || generation !== this._generation) return;
      this._slots = options.slots;
      const targetIndex=this._targetRestaurant===null?-1:restaurants.findIndex(r=>r.id===this._targetRestaurant);this._targetRestaurant=null;
      const index = targetIndex>=0?targetIndex:Math.min(this.data.restaurantIndex, Math.max(0, restaurants.length - 1));
      const slot = options.slots.find(s => s.date === this.data.date && s.meal === this.data.meal) || options.defaultSlot;
      const dates = [...new Set(options.slots.map(s => s.date))];
      this.setData({ restaurants, restaurantIndex: index, date: slot.date, meal: slot.meal, dates, dateIndex: dates.indexOf(slot.date), mealTabs: this.tabs(slot.date), closed: false, accessRequired: false });
      if (!restaurants[index]) return;
      const [menu, cart] = await Promise.all([request<Menu>(`/restaurants/${restaurants[index].id}/menu?date=${slot.date}`), orderingRequest<Cart>(this.slotPath()).catch(e=>{if(e instanceof ApiError&&e.code==='CART_NOT_DRAFT')return null;throw e;})]);
      if (!this._visible || generation !== this._generation) return;
      this.rateMenu(menu);
      const categoryId = menu.categories.some(c => c.id === this.data.categoryId) ? this.data.categoryId : menu.categories[0]?.id || 0;
      this.setData({ categories: menu.categories, dishes: menu.dishes, categoryId, visibleDishes: menu.dishes.filter(d => d.categoryId === categoryId) });
      if(cart)this.setCart(cart);else this.setData({cart:null,closed:true,syncError:'这餐已经结束，请切换日期或餐次，再选择这道菜。'});
      if(this._targetDish!==null){const target=this.data.dishes.find(d=>d.id===this._targetDish);this._targetDish=null;if(target)this.openDish(target);else this.setData({syncError:'这道时令菜在所选用餐日期不可选，请选择其他菜品或日期。'});}
    } catch (e) { if (generation === this._generation) this.setData({ error: message(e) }); }
    finally { if (generation === this._generation) this.setData({ loading: false }); }
  },
  rateMenu(menu:Menu){menu.dishes=menu.dishes.map(d=>({...d,scoreText:d.average==null?'暂无评分':d.average.toFixed(1)+' 分'}));},
  setCart(cart: Cart) {
    this.setData({ cart, submittedItems: selections(cart.submittedItems || []), cartItems: cart.items.map(i => ({ ...i, specText: i.selections.map(s => s.dimensionName + '：' + s.optionName).join(' · ') })), syncError: this.data.retryPending ? '上次操作结果待核对，请重试' : '', closed: Date.parse(cart.serverTime) >= Date.parse(cart.cutoff) });
  },
  async sync(force = false) {
    if (!this._visible || this.data.loading || (!force && (this.data.busy || this._polling)) || !this.data.restaurants.length) return;
    const generation = this._generation, serial = ++this._syncSeq, path = this.slotPath(); this._polling = true;
    try {
      const cart = await orderingRequest<Cart>(path);
      if (generation === this._generation && serial === this._syncSeq && this._visible) this.setCart(cart);
    } catch (e) {
      if (generation !== this._generation || serial !== this._syncSeq) return;
      const denied = e instanceof ApiError && (e.code === 'ACCESS_REQUIRED' || e.code === 'AUTH_REQUIRED');
      const closed = e instanceof ApiError && (e.code === 'MEAL_SLOT_CLOSED' || e.code === 'CART_NOT_DRAFT');
      this.setData({ syncError: message(e), closed: denied || closed || this.data.closed, accessRequired: denied });
    } finally { if (serial === this._syncSeq) this._polling = false; }
  },
  canChange() { return !this.data.loading && !this.data.busy && !this.data.retryPending; },
  async restaurant(e: WechatMiniprogram.TouchEvent) { if (!this.canChange()) return; this.setData({ restaurantIndex: Number(e.currentTarget.dataset.index), categoryId: 0, dish: null, cartOpen: false }); await this.load(); },
  async date(e: WechatMiniprogram.PickerChange) {
    if (!this.canChange()) return;
    const date = this.data.dates[Number(e.detail.value)], slot = this._slots.find(s => s.date === date && s.meal === this.data.meal) || this._slots.find(s => s.date === date);
    if (!slot) return; this.setData({ date: slot.date, meal: slot.meal, dish: null, cartOpen: false }); await this.load();
  },
  async meal(e: WechatMiniprogram.TouchEvent) { if (!this.canChange()) return; const meal = e.currentTarget.dataset.meal as Meal; if (!this._slots.some(s => s.date === this.data.date && s.meal === meal)) return; this.setData({ meal, dish: null, cartOpen: false }); await this.load(); },
  category(e: WechatMiniprogram.TouchEvent) { const categoryId = Number(e.currentTarget.dataset.id); this.setData({ categoryId, visibleDishes: this.data.dishes.filter(d => d.categoryId === categoryId) }); },
  openDish(dish: Dish, item: CartItem | null = null) {
    this._editing = item && this.data.cart ? { item, version: this.data.cart.version } : null;
    this.setData({ dish, editing: !!item, choices: dish.dimensions.map(d => ({ id: d.id!, name: d.name, options: d.options.map(o => ({ id: o.id!, name: o.name, selected: item ? item.selections.some(s => s.optionId === o.id) : o.isDefault })) })) });
    this.syncTabVisibility();
  },
  detail(e: WechatMiniprogram.TouchEvent) { if (!this.canChange()) return; const dish = this.data.dishes.find(d => d.id === Number(e.currentTarget.dataset.id)); if (dish) this.openDish(dish); },
  async plus(e: WechatMiniprogram.TouchEvent) { if (!this.canChange() || this.data.closed) return; const dish = this.data.dishes.find(d => d.id === Number(e.currentTarget.dataset.id)); if (!dish) return; if (dish.dimensions.length) this.openDish(dish); else await this.add(dish, []); },
  choose(e: WechatMiniprogram.TouchEvent) { if (this.data.busy) return; const dimension = Number(e.currentTarget.dataset.dimension), option = Number(e.currentTarget.dataset.option); this.setData({ choices: this.data.choices.map(d => d.id === dimension ? { ...d, options: d.options.map(o => ({ ...o, selected: o.id === option })) } : d) }); },
  dishReviews() { if(!this.data.busy&&!this.data.retryPending&&this.data.dish)wx.navigateTo({url:`/subpackages/reviews/index?dishId=${this.data.dish.id}`}); },
  closeDish() { if (!this.data.busy && !this.data.retryPending) { this.setData({ dish: null }); this.syncTabVisibility(); } },
  noop() {},
  async openCart() { if (this.data.loading) return; await this.sync(true); if (!this._visible) return; this.setData({ cartOpen: true }); this.syncTabVisibility(); },
  closeCart() { if (!this.data.busy) { this.setData({ cartOpen: false }); this.syncTabVisibility(); } },
  submitCart() { if(!this.canChange()||this.data.closed||!this.data.cart?.orderId||!this.data.cart.items.length)return;this.closeCart();wx.navigateTo({url:`/pages/orders/submit?id=${this.data.cart.orderId}`}); },
  viewOrder() { if(this.data.cart?.orderId)wx.navigateTo({url:`/pages/orders/detail?id=${this.data.cart.orderId}`}); },
  async saveDish() {
    if (!this.data.dish || !this.canChange() || this.data.closed) return;
    const optionIds = this.data.choices.flatMap(d => d.options.filter(o => o.selected).map(o => o.id));
    if (optionIds.length !== this.data.choices.length) { wx.showToast({ title: '每个规格请选择一项', icon: 'none' }); return; }
    if (this._editing) {
      const edit = this._editing;
      await this.write({ path: `/meal-orders/${this.data.cart!.orderId}/items/${edit.item.id}`, method: 'PUT', key: null, body: { version: edit.version, itemVersion: edit.item.version, quantity: edit.item.quantity, optionIds } });
    } else await this.add(this.data.dish, optionIds);
  },
  async add(dish: Dish, optionIds: number[]) {
    this.setData({ busy: true });
    try {
      const draft = await orderingRequest<Mutation>(this.slotPath() + '/draft', 'PUT');
      await this.write({ path: `/meal-orders/${draft.orderId}/items`, method: 'POST', key: key(), body: { dishId: dish.id, dishVersion: dish.version, optionIds } });
    } catch (e) { this.setData({ syncError: message(e) }); } finally { this.setData({ busy: false }); }
  },
  async write(operation: Pending) {
    this.setData({ busy: true, syncError: '' });
    try {
      await orderingRequest<Mutation>(operation.path, operation.method, operation.body, operation.key ? { 'Idempotency-Key': operation.key } : {});
      this._pending = null; this.setData({ retryPending: false, dish: null });
      await this.sync(true);
    } catch (e) {
      if (e instanceof ApiError && (e.code === 'NETWORK_ERROR' || e.code === 'SERVICE_UNAVAILABLE' || e.code === 'ACCESS_REQUIRED' || e.code === 'AUTH_REQUIRED')) { this._pending = operation; this.setData({ retryPending: true }); }
      else {
        this._pending = null; this.setData({ retryPending: false }); await this.sync(true);
        if (e instanceof ApiError && e.code === 'VERSION_CONFLICT' && this._editing && this.data.cart) {
          const item = [...this.data.cart.items,...this.data.cart.submittedItems].find(i => i.id === this._editing!.item.id);
          if (item && await confirm(`清单已更新。最新为 ${item.quantity} 份，${item.selections.map(s => s.optionName).join(' / ') || '无规格'}。保留你选择的规格，核对后再次保存？`)) this._editing = { item, version: this.data.cart.version }; else this.setData({ dish: null });
        }
        if(e instanceof ApiError && e.code==='CANCEL_REQUIRED') {
          wx.showModal({title:'需要取消整张餐单',content:'请到餐单详情核对，再明确点击取消餐单。',success:r=>{if(r.confirm)wx.navigateTo({url:`/pages/orders/detail?id=${this.data.cart?.orderId}`});}});
        }
        if (e instanceof ApiError && e.code === 'MENU_CHANGED') {
          this.setData({ dish: null });
          const generation = this._generation;
          try {
            const r = this.data.restaurants[this.data.restaurantIndex];
            const menu = await request<Menu>(`/restaurants/${r.id}/menu?date=${this.data.date}`);
            if (generation === this._generation) {
              this.rateMenu(menu);
              const categoryId = menu.categories.some(c => c.id === this.data.categoryId) ? this.data.categoryId : menu.categories[0]?.id || 0;
              this.setData({ categories: menu.categories, dishes: menu.dishes, categoryId, visibleDishes: menu.dishes.filter(d => d.categoryId === categoryId) });
            }
          } catch (refreshError) { this.setData({ error: message(refreshError) }); }
        }
      }
      this.setData({ syncError: message(e) });
    } finally { this.setData({ busy: false }); this.syncTabVisibility(); }
  },
  async retry() { if (!this._pending || this.data.busy || !await dailyGuard()) return; await this.sync(true); await this.write(this._pending); },
  async verifyAccess() { await dailyGuard(); },
  async delta(e: WechatMiniprogram.TouchEvent) { if (!this.canChange() || !this.data.cart?.orderId || this.data.closed) return; await this.write({ path: `/meal-orders/${this.data.cart.orderId}/items/${e.currentTarget.dataset.id}/quantity`, method: 'POST', body: { delta: Number(e.currentTarget.dataset.delta) }, key: key() }); },
  editItem(e: WechatMiniprogram.TouchEvent) {
    if (!this.canChange() || this.data.closed) return; const item = [...(this.data.cart?.items||[]),...(this.data.cart?.submittedItems||[])].find(i => i.id === Number(e.currentTarget.dataset.id)), dish = this.data.dishes.find(d => d.id === item?.dishId);
    if (item && dish) this.openDish(dish, item); else wx.showToast({ title: '该菜已不在当前菜单，可减份或移除', icon: 'none' });
  },
  async removeItem(e: WechatMiniprogram.TouchEvent) {
    if (!this.canChange() || !this.data.cart?.orderId || this.data.closed) return;
    const version = this.data.cart.version, id = this.data.cart.orderId;
    if (!await confirm('移除这行菜品？原添加人的记录会保留。')) return;
    await this.write({ path: `/meal-orders/${id}/items/${e.currentTarget.dataset.id}`, method: 'DELETE', body: { version }, key: null });
  },
  async clear() {
    if (!this.canChange() || !this.data.cart?.orderId || !this.data.cart.items.length || this.data.closed) return;
    const version = this.data.cart.version, id = this.data.cart.orderId;
    if (!await confirm('清空这份共同选择的清单？其他人的选择也会移除。')) return;
    await this.write({ path: `/meal-orders/${id}/pending/clear`, method: 'POST', body: { version }, key: null });
  }
});
