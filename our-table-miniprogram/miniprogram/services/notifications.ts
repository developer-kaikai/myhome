import { ApiError } from './api';
import { orderingRequest } from './cart';
import { key } from './menu';
export interface Subscription { userId:number; event:string; templateId:string; enabled:boolean; response:string; available:number }
interface Consent { userId:number; event:string; templateId:string; response:string }
interface Pending { body:Consent; key:string; createdAt:number }
let busy=false;
export function subscriptions():Promise<Subscription[]> { return orderingRequest('/notifications/subscriptions'); }
export function normalizeConsent(value:string):string {
  if(value==='accept'||value==='acceptWithAudio')return 'ACCEPT';
  if(value==='reject')return 'REJECT';
  if(value==='ban')return 'BAN';
  return 'UNKNOWN';
}
/** 必须直接从点击处理函数调用；SDK 之前不得等待网络请求。 */
export function authorize(subscription:Subscription):Promise<string> {
  if(busy)return Promise.reject(new Error('正在处理订阅，请稍候'));
  if(!subscription.enabled||!subscription.templateId)return Promise.reject(new Error('提醒暂不可用，请稍后刷新'));
  busy=true;
  const storage=`subscriptionPending:${subscription.userId}:${subscription.event}`;
  let pending:Pending|undefined=wx.getStorageSync<Pending>(storage)||undefined;
  if(pending && (!pending.createdAt || Date.now()-pending.createdAt>=23*3600000)){wx.removeStorageSync(storage);pending=undefined;}
  const save=async(attempt:Pending):Promise<string>=>{
    try {
      await orderingRequest('/notifications/subscriptions','POST',attempt.body,{'Idempotency-Key':attempt.key});
      wx.removeStorageSync(storage);
      return attempt.body.response==='ACCEPT'?'已订阅一次提醒':attempt.body.response==='UNKNOWN'?'本次未获得授权，可重新订阅':'未订阅，仍可正常使用';
    }catch(error){
      if(error instanceof ApiError && error.code==='TEMPLATE_UNAVAILABLE')wx.removeStorageSync(storage);
      throw error;
    }
  };
  if(pending)return save(pending).finally(()=>{busy=false;});
  // Promise executor 同步执行，确保保留用户点击手势。
  return new Promise<Pending>((resolve,reject)=>{
    wx.requestSubscribeMessage({tmplIds:[subscription.templateId],success(result){
      const attempt={body:{userId:subscription.userId,event:subscription.event,templateId:subscription.templateId,response:normalizeConsent(String(result[subscription.templateId]||''))},key:key(),createdAt:Date.now()};
      try{wx.setStorageSync(storage,attempt);resolve(attempt);}catch{reject(new Error('订阅记录保存失败，请稍后重新订阅'));}
    },fail(){reject(new Error('未完成微信订阅，可稍后重试'));}});
  }).then(save).finally(()=>{busy=false;});
}
export const notificationNames:Record<string,string>={NOT_REQUESTED:'餐单已生效，最新进展可在这里查看。',PENDING:'提醒等待处理，最新进展可在这里查看。',SENDING:'正在提交微信提醒。',SENT:'微信已受理提醒，最新进展可在这里查看。',FAILED_RETRYABLE:'提醒稍后重试，餐单已生效。',FAILED_FINAL:'提醒未确认成功，最新进展可在这里查看。',UNKNOWN:'提醒结果尚不确定，最新进展可在这里查看。',SKIPPED:'本次未发送微信提醒，最新进展可在这里查看。',CANCELLED:'对应提醒已停止。'};
