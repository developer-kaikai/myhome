import {dailyGuard,request,message,ApiError} from '../../services/api';
import {Summary,chart,score} from '../../services/home';
Page({
  data:{ready:false,loading:false,error:'',period:'week',scope:'family',scopeIndex:0,
    scopes:[{key:'family',name:'全家'}],scopeNames:['全家'],periods:[{key:'week',name:'本周'},{key:'month',name:'本月'},{key:'year',name:'今年'}],
    summary:null as Summary|null,points:[] as ReturnType<typeof chart>,popular:[] as (Summary['popular'][number]&{scoreText:string;displayName:string})[],recommendation:null as (NonNullable<Summary['recommendation']>&{scoreText:string})|null,
    pointText:''},
  _visible:false,_generation:0,
  async onShow(){this._visible=true;const generation=++this._generation;const me=await dailyGuard();if(!this._visible||generation!==this._generation)return;this.setData({ready:!!me});this.getTabBar()?.setData({selected:'home',daily:!!me});if(me)await this.load();},
  onHide(){this._visible=false;this._generation++;this.setData({loading:false});},onUnload(){this.onHide();},
  async onPullDownRefresh(){try{await this.load();}finally{wx.stopPullDownRefresh();}},
  async load(){if(!this.data.ready||!this._visible)return;const generation=++this._generation,period=this.data.period,scope=this.data.scope;this.setData({loading:true,error:'',summary:null,points:[],popular:[],recommendation:null,pointText:''});try{
    const summary=await request<Summary>(`/home/summary?period=${period}&scope=${encodeURIComponent(scope)}`);
    if(!this._visible||generation!==this._generation)return;
    const scopes=[{key:'family',name:'全家'},...summary.restaurants.map(r=>({key:`restaurant:${r.id}`,name:r.name}))];
    this.setData({summary,scopes,scopeNames:scopes.map(s=>s.name),scopeIndex:Math.max(0,scopes.findIndex(s=>s.key===scope)),points:chart(summary.trend,period),
      popular:summary.popular.map(p=>({...p,scoreText:score(p.average),displayName:scope==='family'?`${p.dishName} · ${p.restaurantName}`:p.dishName})),
      recommendation:summary.recommendation?{...summary.recommendation,scoreText:score(summary.recommendation.average)}:null});
  }catch(e){if(this._visible&&generation===this._generation){this.setData({error:message(e)});if(e instanceof ApiError&&['AUTH_REQUIRED','ACCESS_REQUIRED'].includes(e.code)){this.setData({ready:false});this.getTabBar()?.setData({daily:false});wx.navigateTo({url:'/pages/access/index'});}}}finally{if(generation===this._generation)this.setData({loading:false});}},
  period(e:WechatMiniprogram.TouchEvent){const period=e.currentTarget.dataset.key;if(!this.data.periods.some(p=>p.key===period)||period===this.data.period)return;this.setData({period});void this.load();},
  scope(e:WechatMiniprogram.PickerChange){const choice=this.data.scopes[Number(e.detail.value)];if(!choice||choice.key===this.data.scope)return;this.setData({scope:choice.key,scopeIndex:Number(e.detail.value)});void this.load();},
  point(e:WechatMiniprogram.TouchEvent){const p=this.data.points[Number(e.currentTarget.dataset.index)];if(p)this.setData({pointText:`${this.data.period==='year'?p.date.slice(0,7):p.date} · ${p.text}`});},
  recommend(){if(!this.data.recommendation||this.data.loading)return;wx.setStorageSync('orderingTarget',{restaurantId:this.data.recommendation.restaurantId,dishId:this.data.recommendation.dishId});wx.switchTab({url:'/pages/ordering/index'});},
  start(){wx.switchTab({url:'/pages/ordering/index'});}
});
