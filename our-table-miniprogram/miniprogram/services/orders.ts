import { CartItem, Meal } from './cart';
export interface Participant { userId: number; name: string; initiator: boolean; orderer: boolean; collaborator: boolean; chef: boolean }
export interface OrderDetail { id: number; orderNo: string | null; restaurantId: number; restaurantName: string; date: string; meal: Meal; status: string; version: number; dinerCount: number; remark: string | null; cancelReason: string | null; cutoff: string; serverTime: string; submittedAt: string | null; initiatorId: number | null; items: CartItem[]; pendingItems: CartItem[]; actualItems: ActualItem[]; completedAt: string | null; reviewDeadline: string | null; canConfirm: boolean; participants: Participant[]; canModify: boolean; canMeta: boolean; canCancel: boolean; canReopen: boolean; ownChef: boolean; confirmationDue: boolean; notificationState: string }
export interface Preview { order: OrderDetail; invalidItems: { itemId: number; reason: string }[]; canSubmit: boolean; menuFingerprint: string }
export interface OrderPage { orders: OrderDetail[]; page: number; size: number; hasMore: boolean }
export const stateNames: Record<string,string> = { DRAFT: '待提交', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELLED: '已取消' };
export function selections(items: CartItem[]) { return items.map(i => ({...i, specText: i.selections.map(s => s.dimensionName + '：' + s.optionName).join(' · ')})); }
export function changes(before: Preview, after: Preview): string {
  const old = new Map([...before.order.items,...before.order.pendingItems].map(i=>[i.id,i]));
  const result: string[]=[];
  for (const item of [...after.order.items,...after.order.pendingItems]) {
    const previous=old.get(item.id); old.delete(item.id);
    if (!previous) result.push('新增 '+item.dishName+' '+item.quantity+'份');
    else if (previous.quantity!==item.quantity || JSON.stringify(previous.selections)!==JSON.stringify(item.selections)) result.push(item.dishName+' 已改为 '+item.quantity+'份，'+(item.selections.map(s=>s.optionName).join('/')||'无规格'));
  }
  for (const item of old.values()) result.push('移除 '+item.dishName);
  if (before.order.dinerCount!==after.order.dinerCount || before.order.remark!==after.order.remark) result.push('人数或整单备注已更新');
  return result.join('；') || '餐单或菜单已更新，请重新核对';
}

export interface ActualItem { id:number; dishId:number; dishName:string; imageObjectKey:string|null; selections:CartItem['selections']; quantity:number; sourceType:string }
export interface Candidate { id:number; name:string; version:number; dimensions:{id:number;name:string;options:{id:number;name:string;isDefault:boolean}[]}[] }
export interface Confirmation { order:OrderDetail; candidates:Candidate[] }
export interface ActualInput { sourceOrderItemId?:number; dishId?:number; dishVersion?:number; optionIds?:number[]; quantity:number }
