import { request, message } from '../../services/api';
import { Menu, Category, Dimension, ChefDish, chefGuard, confirm, key } from '../../services/menu';
Page({
  data: { ready: false, busy: false, error: '', id: 0, restaurantId: 0, version: 0, categories: [] as Category[], categoryIndex: 0, name: '', introduction: '', recipe: '', onShelf: true, seasonal: false, supplyMonths: [] as number[], months: Array.from({ length: 12 }, (_, i) => ({ value: i + 1, selected: false })), dimensions: [] as Dimension[] },
  pending: { payload: '', key: '' },
  async onLoad(query: Record<string, string | undefined>) { this.setData({ id: Number(query.id || 0) }); await this.load(); },
  async load() {
    this.setData({ ready: false, error: '' });
    try {
      const restaurantId = await chefGuard();
      const menu = await request<Menu>(`/chef/restaurants/${restaurantId}/menu`);
      this.setData({ restaurantId, categories: menu.categories, categoryIndex: Math.max(0, menu.categories.findIndex(c => c.type === 'NORMAL')) });
      if (this.data.id) {
        const result = await request<ChefDish>(`/chef/dishes/${this.data.id}`), d = result.dish;
        this.setData({ version: d.version, categoryIndex: menu.categories.findIndex(c => c.id === d.categoryId), name: d.name, introduction: d.introduction || '', recipe: result.recipe || '', onShelf: d.onShelf, supplyMonths: d.supplyMonths, dimensions: d.dimensions });
      }
      this.sync(); this.setData({ ready: true });
    } catch (e) { this.setData({ error: message(e) }); }
  },
  sync() {
    const seasonal = this.data.categories[this.data.categoryIndex]?.type === 'SEASONAL';
    const months = this.data.months.map(m => ({ ...m, selected: this.data.supplyMonths.includes(m.value) }));
    this.setData({ seasonal, months });
  },
  input(e: WechatMiniprogram.Input) { const field = e.currentTarget.dataset.field as string; if (['name', 'introduction', 'recipe'].includes(field)) this.setData({ [field]: e.detail.value }); },
  category(e: WechatMiniprogram.PickerChange) { this.setData({ categoryIndex: Number(e.detail.value) }); this.sync(); },
  shelf(e: WechatMiniprogram.SwitchChange) { this.setData({ onShelf: e.detail.value }); },
  month(e: WechatMiniprogram.TouchEvent) { const m = Number(e.currentTarget.dataset.month); const values = this.data.supplyMonths.includes(m) ? this.data.supplyMonths.filter(v => v !== m) : [...this.data.supplyMonths, m]; this.setData({ supplyMonths: values.sort((a,b) => a-b) }); this.sync(); },
  addDimension() { if (this.data.dimensions.length < 3) this.setData({ dimensions: [...this.data.dimensions, { id: null, name: '', options: [{ id: null, name: '', isDefault: true }, { id: null, name: '', isDefault: false }] }] }); },
  removeDimension(e: WechatMiniprogram.TouchEvent) { this.setData({ dimensions: this.data.dimensions.filter((_, i) => i !== Number(e.currentTarget.dataset.dim)) }); },
  dimensionName(e: WechatMiniprogram.Input) { this.setData({ [`dimensions[${Number(e.currentTarget.dataset.dim)}].name`]: e.detail.value }); },
  optionName(e: WechatMiniprogram.Input) { this.setData({ [`dimensions[${Number(e.currentTarget.dataset.dim)}].options[${Number(e.currentTarget.dataset.opt)}].name`]: e.detail.value }); },
  addOption(e: WechatMiniprogram.TouchEvent) { const i = Number(e.currentTarget.dataset.dim), d = this.data.dimensions[i]; if (d && d.options.length < 6) this.setData({ [`dimensions[${i}].options`]: [...d.options, { id: null, name: '', isDefault: false }] }); },
  removeOption(e: WechatMiniprogram.TouchEvent) {
    const i = Number(e.currentTarget.dataset.dim), j = Number(e.currentTarget.dataset.opt), d = this.data.dimensions[i]; if (!d || d.options.length <= 2) return;
    const options = d.options.filter((_, n) => n !== j); if (!options.some(o => o.isDefault)) options[0].isDefault = true;
    this.setData({ [`dimensions[${i}].options`]: options });
  },
  defaultOption(e: WechatMiniprogram.TouchEvent) { const i = Number(e.currentTarget.dataset.dim), j = Number(e.currentTarget.dataset.opt); this.setData({ [`dimensions[${i}].options`]: this.data.dimensions[i].options.map((o,n) => ({ ...o, isDefault: n === j })) }); },
  async save() {
    const d = this.data; if (!d.ready || d.busy) return;
    const category = d.categories[d.categoryIndex]; if (!category) return;
    const body = { ...(d.id ? { version: d.version } : {}), categoryId: category.id, name: d.name, introduction: d.introduction, recipe: d.recipe, onShelf: d.onShelf, supplyMonths: d.seasonal ? d.supplyMonths : [], dimensions: d.dimensions };
    this.setData({ busy: true, error: '' });
    try {
      if (d.id) await request(`/chef/dishes/${d.id}`, 'PUT', body);
      else { const payload = JSON.stringify(body); if (payload !== this.pending.payload) this.pending = { payload, key: key() }; await request(`/chef/restaurants/${d.restaurantId}/dishes`, 'POST', body, { 'Idempotency-Key': this.pending.key }); }
      wx.showToast({ title: '菜品已保存', icon: 'success' }); wx.navigateBack();
    } catch (e) { this.setData({ error: message(e) }); } finally { this.setData({ busy: false }); }
  },
  async remove() {
    if (this.data.busy || !this.data.id || !await confirm(`删除“${this.data.name}”？历史餐单仍会保留。`)) return;
    this.setData({ busy: true, error: '' });
    try { await request(`/chef/dishes/${this.data.id}`, 'DELETE', { version: this.data.version }); wx.navigateBack(); }
    catch (e) { this.setData({ error: message(e) }); } finally { this.setData({ busy: false }); }
  }
});
