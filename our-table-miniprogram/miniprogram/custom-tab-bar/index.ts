Component({
  data: { selected: 'mine', daily: false, overlayHidden: false, tabs: [{ key:'home',label:'首页' },{ key:'ordering',label:'点餐' },{ key:'orders',label:'餐单' },{ key:'party',label:'聚会' },{ key:'mine',label:'我的' }] },
  methods: {
    change(e: WechatMiniprogram.CustomEvent) { const key = e.currentTarget.dataset.key as string; if (!this.data.daily && key !== 'mine') return; wx.switchTab({ url: '/pages/' + key + '/index' }); }
  }
});
