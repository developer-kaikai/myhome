import { request, login, Me, Profile, message } from '../../services/api';
Page({
  data: { me: null as Me | null, nickname: '', busy: false, error: '' },
  async onShow() {
    this.getTabBar()?.setData({ selected:'mine', daily:false });
    if (!wx.getStorageSync('sessionToken')) { this.setData({ me:null }); return; }
    try { this.setMe(await request<Me>('/users/me')); } catch (e) { this.setData({ error:message(e), me:null }); }
  },
  setMe(me: Me) { this.setData({ me, nickname:me.user.nickname, error:'' }); getApp<{globalData:{me:Me|null}}>().globalData.me=me; this.getTabBar()?.setData({ selected:'mine', daily:me.dailyAllowed }); },
  async signIn() { this.setData({ busy:true, error:'' }); try { this.setMe(await login());if(this.data.me?.dailyAllowed)wx.switchTab({url:'/pages/home/index'}); } catch (e) { this.setData({ error:message(e) }); } finally { this.setData({ busy:false }); } },
  input(e: WechatMiniprogram.CustomEvent<{value:string}>) { this.setData({ nickname:e.detail.value }); },
  async save() { if (!this.data.me || this.data.busy) return; this.setData({ busy:true,error:'' }); try { const user=await request<Profile>('/users/me/profile','PUT',{nickname:this.data.nickname,version:this.data.me.user.version}); this.setMe({...this.data.me,user}); wx.showToast({title:'昵称已保存',icon:'success'}); } catch(e){ this.setData({error:message(e)}); } finally {this.setData({busy:false});} },
  parties() { wx.navigateTo({url:'/subpackages/party/index'}); },
  reviews() { wx.navigateTo({url:'/subpackages/reviews/index'}); },
  access() { wx.navigateTo({url:'/pages/access/index'}); },
  chef() { wx.navigateTo({url:'/subpackages/chef/index'}); },
  async logout() { try { await request('/auth/logout','POST'); } finally {wx.removeStorageSync('sessionToken');getApp<{globalData:{me:Me|null}}>().globalData.me=null;this.setData({me:null});this.getTabBar()?.setData({daily:false});} }
});
