import { dailyGuard } from '../../services/api';
Page({data:{ready:false},async onShow(){const me=await dailyGuard();this.setData({ready:!!me});this.getTabBar()?.setData({selected:'home',daily:!!me});},start(){wx.switchTab({url:'/pages/ordering/index'});}});
