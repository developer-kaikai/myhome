import {request,ApiError} from './api';
import {PARTY_API_BASE} from './config';
export interface Member {userId:number;name:string;status:string;canRemove?:boolean}
export interface Party {id:number;theme:string;location:string;startAt:string;plannedEndAt:string;status:string;reopened:boolean;firstEndedAt:string|null;lastEndedAt:string|null;version:number;memberCount:number|null;creator:boolean;membership:string|null;canJoin:boolean;canShare:boolean;dailyAllowed:boolean;members:Member[];userId:number;canEdit:boolean;serverTime:string;coverPreset?:string}
export interface Item {id:number;theme:string;location:string;startAt:string;status:string;reopened:boolean;memberCount:number;membership:string;coverPreset?:string}
export interface Listing {parties:Item[];page:number;hasMore:boolean}
export interface Create {theme:string;location:string;startAt:string;plannedEndAt:string;longDurationConfirmed:boolean;coverPreset?:string}
export interface Attempt {path:string;method:'POST'|'PUT';body:object;key:string}
export function partyRequest<T>(path:string,method:'GET'|'POST'|'PUT'='GET',data?:object,headers:Record<string,string>={}):Promise<T>{return request<T>(path,method,data,headers,PARTY_API_BASE);}
export function uncertain(e:unknown){return e instanceof ApiError&&['NETWORK_ERROR','SERVICE_UNAVAILABLE','AUTH_REQUIRED'].includes(e.code);}
export function time(date:string,hour:string){if(!/^\d{4}-\d{2}-\d{2}$/.test(date)||!/^\d{2}:\d{2}$/.test(hour))throw new Error('请选择日期和时间');const value=date+'T'+hour+':00+08:00';if(!Number.isFinite(Date.parse(value)))throw new Error('日期格式不正确');return value;}

export interface Purchase {id:number;name:string;quantity:string|null;creatorId:number;assigneeId:number|null;assigneeName:string|null;status:string;payerId:number|null;payerName:string|null;amount:string|null;zeroNote:string|null;version:number;canEdit:boolean;canClaim:boolean;canRelease:boolean;canPurchase:boolean}
export interface Advance {userId:number;name:string;status:string;amount:string}
export interface PurchaseSummary {total:string;perPerson:string|null;memberCount:number;itemCount:number;purchasedCount:number;advances:Advance[]}
export interface Purchases {items:Purchase[];page:number;hasMore:boolean;summary:PurchaseSummary;canAdd:boolean;creator:boolean;userId:number;candidates:Member[]}

export const partyCovers = [
 {code:'DEFAULT',label:'默认',image:'/assets/party-share.png'},
 {code:'TABLE',label:'家常一桌',image:'/assets/party/table-v1.png'},
 {code:'HOT_POT',label:'围炉火锅',image:'/assets/party/hot-pot-v1.png'},
 {code:'TEA',label:'午后茶点',image:'/assets/party/tea-v1.png'}
];
export function coverImage(code?:string|null){return partyCovers.find(p=>p.code===code)?.image||partyCovers[0].image;}
export function coverLabel(code?:string|null){return partyCovers.find(p=>p.code===code)?.label||partyCovers[0].label;}
