import { request,Me,message } from '../../services/api';
Page({data:{ready:false,error:''},async onShow(){try{const me=await request<Me>('/users/me');if(!me.chefRestaurantId){this.setData({ready:false,error:'仅主厨可进入'});return;}this.setData({ready:true});}catch(e){this.setData({error:message(e)});}},access(){wx.navigateTo({url:'/subpackages/chef/access'});}});
