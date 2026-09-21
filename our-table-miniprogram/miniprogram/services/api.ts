import { API_BASE } from './config';
export interface Profile { id: number; nickname: string; avatarUrl: string | null; version: number }
export interface Me { user: Profile; chefRestaurantId: number | null; dailyAllowed: boolean; dailyExpiresAt: string | null }
interface Envelope<T> { code: string; message: string; data: T; requestId: string }
export class ApiError extends Error {
  constructor(public code: string, message: string) { super(message); }
}
export function request<T>(path: string, method: 'GET' | 'POST' | 'PUT' = 'GET', data?: object, headers: Record<string, string> = {}): Promise<T> {
  const token = wx.getStorageSync<string>('sessionToken');
  return new Promise((resolve, reject) => {
    wx.request<Envelope<T>>({
      url: API_BASE + path, method, data, timeout: 8000,
      header: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...headers },
      success(res) {
        if (res.statusCode >= 200 && res.statusCode < 300 && res.data.code === 'OK') return resolve(res.data.data);
        if (res.statusCode === 401) wx.removeStorageSync('sessionToken');
        reject(new ApiError(res.data.code || 'REQUEST_FAILED', res.data.message || '暂时无法完成，请稍后重试'));
      },
      fail() { reject(new ApiError('NETWORK_ERROR', '连接失败，输入已保留，请稍后重试')); }
    });
  });
}
export async function login(): Promise<Me> {
  const code = await new Promise<string>((resolve, reject) => wx.login({ success: r => r.code ? resolve(r.code) : reject(new Error('微信登录未成功')), fail: () => reject(new Error('微信登录未成功')) }));
  const result = await request<{ token: string }>('/auth/wechat/login', 'POST', { code });
  wx.setStorageSync('sessionToken', result.token);
  return request<Me>('/users/me');
}
export function message(error: unknown): string { return error instanceof Error ? error.message : '暂时无法完成，请重试'; }
export async function dailyGuard(): Promise<Me | null> {
  if (!wx.getStorageSync('sessionToken')) { wx.navigateTo({ url: '/pages/access/index' }); return null; }
  try {
    const me = await request<Me>('/users/me');
    getApp<{ globalData: { me: Me | null } }>().globalData.me = me;
    if (!me.dailyAllowed) { wx.navigateTo({ url: '/pages/access/index' }); return null; }
    return me;
  } catch (error) {
    if (error instanceof ApiError && error.code === 'AUTH_REQUIRED') wx.navigateTo({ url: '/pages/access/index' });
    else wx.showToast({ title: message(error), icon: 'none' });
    return null;
  }
}
