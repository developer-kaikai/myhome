import { request, message } from '../../services/api';
import { Menu, Category, Restaurant, chefGuard, confirm, key } from '../../services/menu';
Page({
  data: { ready: false, busy: false, error: '', restaurant: null as Restaurant | null, categories: [] as Category[], dishes: [] as Menu['dishes'], name: '', categoryName: '', editingCategory: null as Category | null },
  pending: { payload: '', key: '' },
  async onShow() { await this.load(); },
  async load() {
    this.setData({ ready: false, error: '' });
    try { const id = await chefGuard(); const menu = await request<Menu>(`/chef/restaurants/${id}/menu`); this.setData({ ...menu, name: menu.restaurant.name, ready: true }); }
    catch (e) { this.setData({ error: message(e) }); }
  },
  inputName(e: WechatMiniprogram.Input) { this.setData({ name: e.detail.value }); },
  inputCategory(e: WechatMiniprogram.Input) { this.setData({ categoryName: e.detail.value }); },
  async rename() {
    const r = this.data.restaurant; if (!r || this.data.busy) return;
    this.setData({ busy: true, error: '' });
    try { const result = await request<Restaurant>(`/chef/restaurants/${r.id}`, 'PUT', { name: this.data.name, version: r.version }); this.setData({ restaurant: result }); wx.showToast({ title: '餐厅名称已保存', icon: 'none' }); }
    catch (e) { this.setData({ error: message(e) }); } finally { this.setData({ busy: false }); }
  },
  editCategory(e: WechatMiniprogram.TouchEvent) { const c = this.data.categories.find(c => c.id === Number(e.currentTarget.dataset.id)); if (c && c.type === 'NORMAL') this.setData({ editingCategory: c, categoryName: c.name }); },
  cancelCategory() { this.setData({ editingCategory: null, categoryName: '' }); },
  async saveCategory() {
    const r = this.data.restaurant; if (!r || this.data.busy) return;
    this.setData({ busy: true, error: '' });
    try {
      const c = this.data.editingCategory;
      if (c) await request(`/chef/categories/${c.id}`, 'PUT', { name: this.data.categoryName, version: c.version });
      else {
        const payload = JSON.stringify({ name: this.data.categoryName, restaurant: r.id });
        if (payload !== this.pending.payload) this.pending = { payload, key: key() };
        await request(`/chef/restaurants/${r.id}/categories`, 'POST', { name: this.data.categoryName }, { 'Idempotency-Key': this.pending.key });
        this.pending = { payload: '', key: '' };
      }
      this.cancelCategory(); await this.load();
    } catch (e) { this.setData({ error: message(e) }); } finally { this.setData({ busy: false }); }
  },
  async removeCategory(e: WechatMiniprogram.TouchEvent) {
    const c = this.data.categories.find(c => c.id === Number(e.currentTarget.dataset.id));
    if (!c || this.data.busy || !await confirm(`删除“${c.name}”？品类中的菜品需先移动或删除。`)) return;
    this.setData({ busy: true, error: '' });
    try { await request(`/chef/categories/${c.id}`, 'DELETE', { version: c.version }); await this.load(); }
    catch (e) { this.setData({ error: message(e) }); } finally { this.setData({ busy: false }); }
  },
  createDish() { wx.navigateTo({ url: '/subpackages/chef/dish' }); },
  editDish(e: WechatMiniprogram.TouchEvent) { wx.navigateTo({ url: '/subpackages/chef/dish?id=' + Number(e.currentTarget.dataset.id) }); }
});
