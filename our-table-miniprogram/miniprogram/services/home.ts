import {Restaurant} from './menu';
export interface Point {label:string;date:string;count:number|null;future:boolean}
export interface Popular {dishId:number;restaurantId:number;dishName:string;restaurantName:string;orderCount:number;lastMealDate:string;average:number|null;reviewCount:number}
export interface Recommendation {dishId:number;restaurantId:number;dishName:string;restaurantName:string;introduction:string|null;average:number|null;reviewCount:number}
export interface Summary {today:string;greeting:string;period:string;scope:string;rangeStart:string;rangeEnd:string;restaurants:Restaurant[];cookCount:number;trend:Point[];popular:Popular[];recommendation:Recommendation|null}
export function chart(points:Point[],period:string) {
  const max=Math.max(1,...points.filter(p=>!p.future).map(p=>Math.max(0,p.count||0)));
  return points.map((p,index)=>({...p,height:p.future?0:Math.max(0,p.count||0)/max*140,
    tick:period!=='month'||index===0||index===points.length-1||(index+1)%5===0,
    text:p.future?'未到':`${p.count||0} 次`}));
}
export function score(value:number|null){return value==null?'暂无评分':`${value.toFixed(1)} 分`;}
