import { request } from './api';
import { ORDERING_API_BASE } from './config';
export type Meal = 'BREAKFAST' | 'LUNCH' | 'DINNER' | 'SUPPER';
export interface Slot { date: string; meal: Meal; cutoff: string }
export interface Slots { serverTime: string; defaultSlot: Slot; slots: Slot[] }
export interface Selection { dimensionId: number; dimensionName: string; optionId: number; optionName: string }
export interface CartItem { id: number; dishId: number; dishName: string; contributorId: number; contributorName: string; selections: Selection[]; quantity: number; version: number }
export interface Cart { orderId: number | null; restaurantId: number; date: string; meal: Meal; cutoff: string; serverTime: string; status: string; version: number; items: CartItem[]; submittedItems: CartItem[]; dishCount: number; quantity: number; contributorCount: number }
export interface Mutation { orderId: number; version: number }
export function orderingRequest<T>(path: string, method: 'GET' | 'POST' | 'PUT' | 'DELETE' = 'GET', data?: object, headers: Record<string, string> = {}): Promise<T> { return request<T>(path, method, data, headers, ORDERING_API_BASE); }
export const mealNames: Record<Meal, string> = { BREAKFAST: '早餐', LUNCH: '午餐', DINNER: '晚餐', SUPPER: '宵夜' };
