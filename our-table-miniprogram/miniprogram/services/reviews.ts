import { Meal } from './cart';
export interface Review {id:number;orderId:number;dishId:number;dishName:string;reviewerId:number;reviewerName:string;reviewerAvatar:string|null;rating:number;comment:string|null;modifyCount:number;version:number;firstSubmittedAt:string;modifiedAt:string|null;canModify:boolean}
export interface Food {dishId:number;dishName:string;quantity:number;specifications:string[];average:number|null;reviewCount:number;mine:Review|null}
export interface Sheet {orderId:number;restaurantName:string;date:string;meal:Meal;serverTime:string;deadline:string;canSubmit:boolean;reviewedCount:number;foods:Food[]}
export interface Share {token:string;expiresAt:string}
export interface Invitation {orderId:number;restaurantName:string;date:string;meal:Meal;deadline:string;total:number;reviewed:number}
export function beijing(value:string){return new Date(Date.parse(value)+8*3600000).toISOString().slice(0,16).replace('T',' ');}
