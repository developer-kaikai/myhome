import { request, Me } from './api';
export interface Restaurant { id: number; name: string; version: number }
export interface Category { id: number; restaurantId: number; type: 'NORMAL' | 'SEASONAL'; name: string; version: number }
export interface Option { id: number | null; name: string; isDefault: boolean }
export interface Dimension { id: number | null; name: string; options: Option[] }
export interface Dish { id: number; categoryId: number; name: string; introduction: string | null; onShelf: boolean; version: number; supplyMonths: number[]; dimensions: Dimension[]; average?: number|null; reviewCount?: number; hot?: boolean; scoreText?:string }
export interface Menu { restaurant: Restaurant; categories: Category[]; dishes: Dish[]; date: string }
export interface ChefDish { dish: Dish; recipe: string | null }
export async function chefGuard(): Promise<number> {
  const me = await request<Me>('/users/me');
  if (!me.chefRestaurantId) throw new Error('仅主厨可进入');
  return me.chefRestaurantId;
}
export function key(): string { return 'menu_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2); }
export async function confirm(content: string): Promise<boolean> {
  return new Promise(resolve => wx.showModal({ title: '请确认', content, success: r => resolve(r.confirm), fail: () => resolve(false) }));
}
