import {request,message} from '../../services/api';
interface Secret {configured:boolean;passcode:string|null;version:number;grantGeneration:number}
Page({data:{secret:null as Secret|null,value:'',revealed:false,busy:false,error:''},
 async onLoad(){await this.load();},onHide(){if(this.data.secret)this.setData({secret:{...this.data.secret,passcode:null}});this.setData({revealed:false,value:''});},
 async load(){try{this.setData({secret:await request<Secret>('/chef/access/passcode'),error:''});}catch(e){this.setData({error:message(e)});}},
 async reveal(){try{if(this.data.revealed){this.setData({revealed:false,secret:this.data.secret ? {...this.data.secret,passcode:null} : null});return;}const secret=await request<Secret>('/chef/access/passcode?reveal=true');this.setData({secret,revealed:true});}catch(e){this.setData({error:message(e)});}},
 async copy(){try{const secret=await request<Secret>('/chef/access/passcode?reveal=true');if(secret.passcode)wx.setClipboardData({data:secret.passcode});}catch(e){this.setData({error:message(e)});}},
 async generate(){try{const result=await request<{passcode:string}>('/chef/access/passcode/suggestion');this.setData({value:result.passcode});}catch(e){this.setData({error:message(e)});}},
 input(e:WechatMiniprogram.CustomEvent<{value:string}>){this.setData({value:e.detail.value});},
 async save(){if(!this.data.secret||this.data.busy)return;this.setData({busy:true,error:''});try{await request('/chef/access/passcode','PUT',{passcode:this.data.value,version:this.data.secret.version});this.setData({value:'',revealed:false});await this.load();wx.showToast({title:'密令已更新',icon:'success'});}catch(e){this.setData({error:message(e)});}finally{this.setData({busy:false});}},
 async revoke(){if(!this.data.secret||this.data.busy)return;const confirmed=await new Promise<boolean>(resolve=>wx.showModal({title:'撤销全部亲友日常资格？',content:'所有亲友需重新输入密令。主厨和已有聚会资格不受影响。',confirmText:'确认撤销',confirmColor:'#C94A39',success:r=>resolve(r.confirm),fail:()=>resolve(false)}));if(!confirmed)return;
 this.setData({busy:true,error:''});try{const key='revoke-'+Date.now()+'-'+Math.random().toString(36).slice(2);await request('/chef/access/grants/revoke-all','POST',{version:this.data.secret.version,confirmed:true},{'Idempotency-Key':key});await this.load();wx.showToast({title:'已撤销',icon:'success'});}catch(e){this.setData({error:message(e)});}finally{this.setData({busy:false});}}
});
