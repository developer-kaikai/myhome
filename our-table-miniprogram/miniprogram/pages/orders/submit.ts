import { subscriptions,authorize,Subscription,notificationNames } from '../../services/notifications';
import { ApiError, dailyGuard, message } from '../../services/api';
import { orderingRequest, Mutation, mealNames } from '../../services/cart';
import { key } from '../../services/menu';
import { Preview, selections, changes } from '../../services/orders';
interface Attempt { body: object; key: string }
Page({
 data: { subscription:null as Subscription|null,subscribing:false,subscriptionNote:'', id:0, ready:false, loading:true, busy:false, error:'', notice:'', mealName:'', restaurantName:'', date:'', version:0, append:false,
   dinerCount:'2', remark:'', editableMeta:true, rows:[] as {id:number;dishName:string;quantity:number;contributorName:string;specText:string;invalid:string}[], participants:'', formalSummary:'', canSubmit:false, changed:false, retryPending:false },
 _visible:false,_timer:0,_baseline:null as Preview|null,_latest:null as Preview|null,_attempt:null as Attempt|null,_polling:false,_refreshSeq:0,
 onLoad(options:Record<string,string>){this.setData({id:Number(options.id)});},
 async onShow(){this._visible=true;if(!await dailyGuard()||!this._visible)return;this.setData({ready:true});void this.loadSubscription();await this.refresh(true);if(this._visible)this._timer=setInterval(()=>{void this.refresh(false);},2000);},
 async loadSubscription(){try{const rows=await subscriptions();if(this._visible)this.setData({subscription:rows.find(r=>r.event==='REVIEW_INVITED')||null});}catch{if(this._visible)this.setData({subscription:null});}},
 async subscribe(){if(this.data.subscribing||!this.data.subscription)return;this.setData({subscribing:true,subscriptionNote:''});try{const result=await authorize(this.data.subscription);if(this._visible)this.setData({subscriptionNote:result});}catch(e){if(this._visible)this.setData({subscriptionNote:message(e)});}finally{this.setData({subscribing:false});}},
 onHide(){this._visible=false;if(this._timer)clearInterval(this._timer);this._timer=0;}, onUnload(){this.onHide();},
 async refresh(force=false){if((this._polling&&!force)||this.data.busy)return;const serial=++this._refreshSeq;this._polling=true;try{const p=await orderingRequest<Preview>(`/meal-orders/${this.data.id}/submission-preview`);if(!this._visible||serial!==this._refreshSeq)return;this._latest=p;
   if(!this._baseline)this.apply(p,true);
   else if(p.order.version!==this._baseline.order.version||p.menuFingerprint!==this._baseline.menuFingerprint){this.setData({changed:true,notice:changes(this._baseline,p),canSubmit:false});}
   else this.setData({canSubmit:p.canSubmit&&!this.data.changed});
   this.setData({loading:false,error:''});
 }catch(e){if(this._visible&&serial===this._refreshSeq)this.setData({loading:false,error:message(e),canSubmit:false});}finally{if(serial===this._refreshSeq)this._polling=false;}},
 apply(p:Preview,reset=false){this._baseline=p;this._latest=p;const order=p.order,invalid=new Map(p.invalidItems.map(i=>[i.itemId,i.reason]));this.setData({version:order.version,append:order.status==='IN_PROGRESS',mealName:mealNames[order.meal],restaurantName:order.restaurantName,date:order.date,editableMeta:order.status==='DRAFT'||order.canMeta,rows:selections(order.pendingItems).map(i=>({...i,invalid:invalid.get(i.id)||''})),participants:[...new Set([...order.participants.map(i=>i.name),...order.pendingItems.map(i=>i.contributorName)])].join('、'),formalSummary:order.items.map(i=>i.dishName+' '+i.quantity+'份').join('、'),canSubmit:p.canSubmit,changed:false,notice:''});if(reset||!this.data.editableMeta)this.setData({dinerCount:String(order.dinerCount),remark:order.remark||''});},
 acknowledge(){if(this._latest&&!this.data.busy&&!this._attempt)this.apply(this._latest);},
 count(e:WechatMiniprogram.Input){if(!this.data.busy&&!this._attempt)this.setData({dinerCount:e.detail.value});},remark(e:WechatMiniprogram.Input){if(!this.data.busy&&!this._attempt)this.setData({remark:e.detail.value});},
 tag(e:WechatMiniprogram.TouchEvent){if(this.data.editableMeta&&!this.data.busy&&!this._attempt)this.setData({remark:[this.data.remark,e.currentTarget.dataset.value].filter(Boolean).join('，')});},
 async submit(){if(this.data.busy)return;if(this._attempt){if(!await dailyGuard())return;await this.sendAttempt();return;}
   if(!this._baseline||!this.data.canSubmit||this.data.changed)return;
   const acknowledged=this._baseline;await this.refresh(true);if(this.data.changed||!this.data.canSubmit)return;
   const count=Number(this.data.dinerCount);if(!Number.isInteger(count)||count<1||count>20){this.setData({error:'用餐人数为1—20'});return;}
   this._attempt={body:{version:acknowledged.order.version,dinerCount:count,remark:this.data.remark,menuFingerprint:acknowledged.menuFingerprint},key:key()};await this.sendAttempt();
 },
 async sendAttempt(){if(!this._attempt)return;this.setData({busy:true,error:''});const attempt=this._attempt;try{
   // 结果不明时先核对服务端，但仍复用同一操作键与原请求，避免重复合并。
   if(this.data.retryPending)await orderingRequest(`/meal-orders/${this.data.id}`);
   const result=await orderingRequest<Mutation>(`/meal-orders/${this.data.id}/submit`,'POST',attempt.body,{'Idempotency-Key':attempt.key});this._attempt=null;this.setData({retryPending:false});wx.redirectTo({url:`/pages/orders/detail?id=${result.orderId}`});
 }catch(e){const uncertain=e instanceof ApiError&&['NETWORK_ERROR','SERVICE_UNAVAILABLE','ACCESS_REQUIRED','AUTH_REQUIRED'].includes(e.code);this.setData({retryPending:uncertain,error:message(e)});if(!uncertain){this._attempt=null;this.setData({busy:false});await this.refresh();}}
 finally{this.setData({busy:false});}},
 back(){wx.navigateBack();}
});
