import { login,request,Me,message } from '../../services/api';
Page({
 data:{ signedIn:false,passcode:'',busy:false,error:'',allowed:false,expires:'' },
 async onShow(){this.setData({signedIn:!!wx.getStorageSync('sessionToken')});if(this.data.signedIn){try{const me=await request<Me>('/users/me');this.setData({allowed:me.dailyAllowed,expires:me.dailyExpiresAt ? new Date(me.dailyExpiresAt).toLocaleString() : ''});}catch(e){this.setData({error:message(e)});}}},
 input(e:WechatMiniprogram.CustomEvent<{value:string}>){this.setData({passcode:e.detail.value});},
 async submit(){if(this.data.busy)return;this.setData({busy:true,error:''});try{if(!this.data.signedIn){await login();this.setData({signedIn:true});}else{await request('/access/passcode/verify','POST',{passcode:this.data.passcode});this.setData({passcode:''});wx.switchTab({url:'/pages/home/index'});}}catch(e){this.setData({error:message(e)});}finally{this.setData({busy:false});}},
 mine(){wx.switchTab({url:'/pages/mine/index'});},
 home(){wx.switchTab({url:'/pages/home/index'});}
});
