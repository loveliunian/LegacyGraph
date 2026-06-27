import{E as C,d as Z,n as ee,r as m,z as te,o as $,c as le,b as e,w as a,j as i,A as ae,y as D,t as p,e as E,g as oe,ae as ne,v as L,k as j,m as N,B as se,_ as re}from"./index-DJDA9pkS.js";function A(u,n,d){const l=u[n.key];return n.formatter?n.formatter(l,u,d):l==null?"":typeof l=="boolean"?l?"是":"否":l}async function ie(u,n,d){const{filename:l,format:s,sheetName:g="Sheet1",title:f,pageSize:b="a4",orientation:_="portrait"}=d;try{switch(s){case"excel":de(u,n,l,g,f);break;case"csv":ue(u,n,l);break;case"pdf":pe(u,n,l,f,b,_);break;case"json":ce(u,l);break;default:throw new Error(`不支持的导出格式: ${s}`)}C.success("导出成功")}catch(r){throw console.error("导出失败:",r),C.error("导出失败，请重试"),r}}function de(u,n,d,l="Sheet1",s){const g=n.map(r=>r.title),f=u.map((r,c)=>n.map(v=>String(A(r,v,c))));let b=`<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel" xmlns="http://www.w3.org/TR/REC-html40">
<head>
  <meta charset="UTF-8">
  <xml>
    <x:ExcelWorkbook>
      <x:ExcelWorksheets>
        <x:ExcelWorksheet>
          <x:Name>${l}</x:Name>
          <x:WorksheetOptions>
            <x:DisplayGridlines/>
          </x:WorksheetOptions>
        </x:ExcelWorksheet>
      </x:ExcelWorksheets>
    </x:ExcelWorkbook>
  </xml>
  <style>
    td { border: 1px solid #ddd; padding: 4px; }
    th { background: #409eff; color: white; font-weight: bold; padding: 6px 4px; }
    .title { font-size: 18px; font-weight: bold; padding: 10px 0; }
  </style>
</head>
<body>`;s&&(b+=`<div class="title">${s}</div>`),b+=`<table>
    <thead>
      <tr>${g.map(r=>`<th>${r}</th>`).join("")}</tr>
    </thead>
    <tbody>`,f.forEach(r=>{b+=`<tr>${r.map(c=>`<td>${c}</td>`).join("")}</tr>`}),b+="</tbody></table></body></html>";const _=new Blob([b],{type:"application/vnd.ms-excel;charset=utf-8;"});B(_,`${d}.xls`)}function ue(u,n,d){const l=n.map(_=>_.title).join(","),s=u.map((_,r)=>n.map(c=>{const v=String(A(_,c,r));return v.includes(",")||v.includes('"')||v.includes(`
`)?`"${v.replace(/"/g,'""')}"`:v}).join(",")),g=[l,...s].join(`
`),f="\uFEFF",b=new Blob([f+g],{type:"text/csv;charset=utf-8;"});B(b,`${d}.csv`)}function pe(u,n,d,l,s="a4",g="portrait"){const f=window.open("","_blank");if(!f){C.error("无法打开打印窗口，请检查浏览器设置");return}const b=n.map(c=>c.title),_=u.map((c,v)=>n.map(z=>String(A(c,z,v))));let r=`
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <title>${l||d}</title>
  <style>
    @page {
      size: ${s} ${g};
      margin: 15mm;
    }
    body {
      font-family: Arial, sans-serif;
      font-size: 12px;
      line-height: 1.4;
      margin: 0;
      padding: 0;
    }
    .title {
      text-align: center;
      font-size: 18px;
      font-weight: bold;
      margin-bottom: 20px;
      color: #333;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      margin-bottom: 20px;
    }
    th {
      background: #409eff;
      color: white;
      font-weight: bold;
      padding: 8px 6px;
      text-align: left;
      border: 1px solid #3a8ee6;
    }
    td {
      padding: 6px;
      border: 1px solid #ddd;
      word-break: break-all;
    }
    tr:nth-child(even) td {
      background: #f5f7fa;
    }
    .page-footer {
      position: fixed;
      bottom: 0;
      left: 0;
      right: 0;
      text-align: center;
      font-size: 10px;
      color: #999;
      padding: 10px;
    }
    @media print {
      body { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
    }
  </style>
</head>
<body>`;l&&(r+=`<div class="title">${l}</div>`),r+=`<table>
    <thead>
      <tr>${b.map(c=>`<th>${c}</th>`).join("")}</tr>
    </thead>
    <tbody>`,_.forEach(c=>{r+=`<tr>${c.map(v=>`<td>${v}</td>`).join("")}</tr>`}),r+=`</tbody></table>
    <div class="page-footer">
      导出时间: ${new Date().toLocaleString("zh-CN")}
    </div>
  </body>
  </html>`,f.document.write(r),f.document.close(),f.focus(),setTimeout(()=>{f.print()},250)}function ce(u,n){const d=JSON.stringify(u,null,2),l=new Blob([d],{type:"application/json;charset=utf-8;"});B(l,`${n}.json`)}function B(u,n){const d=URL.createObjectURL(u),l=document.createElement("a");l.href=d,l.download=n,document.body.appendChild(l),l.click(),document.body.removeChild(l),URL.revokeObjectURL(d)}const me={class:"audit-log-page"},fe={class:"card-header"},be={class:"code-pre"},ge={class:"code-pre"},he={class:"code-pre error"},ye=Z({__name:"LogList",setup(u){const n=j(!1),d=j(!1),l=j(null),s=N({actionType:"",module:"",username:"",dateRange:[]}),g=N({page:1,pageSize:20,total:1234}),f=j([]),b={graph:{label:"代码图谱",type:"primary"},scan:{label:"扫描任务",type:"warning"},report:{label:"报告管理",type:"success"},system:{label:"系统设置",type:"info"},user:{label:"用户管理",type:"danger"},default:{label:"其他",type:"info"}},_={create:{label:"新增",type:"success"},update:{label:"修改",type:"warning"},delete:{label:"删除",type:"danger"},query:{label:"查询",type:"info"},export:{label:"导出",type:"primary"},login:{label:"登录",type:"success"},logout:{label:"登出",type:"info"},default:{label:"其他",type:"info"}};function r(h){var t;return((t=b[h])==null?void 0:t.type)||b.default.type}function c(h){var t;return((t=b[h])==null?void 0:t.label)||b.default.label}function v(h){var t;return((t=_[h])==null?void 0:t.type)||_.default.type}function z(h){var t;return((t=_[h])==null?void 0:t.label)||_.default.label}function O(){const h=["graph","scan","report","system","user"],t=["create","update","delete","query","export","login","logout"],V=["admin","user1","user2","developer"],k=["success","success","success","success","failed"];return Array.from({length:20},(x,T)=>({id:String(g.page*20+T+1).padStart(6,"0"),module:h[Math.floor(Math.random()*h.length)],action:t[Math.floor(Math.random()*t.length)],description:`执行了${z(t[Math.floor(Math.random()*t.length)])}操作`,username:V[Math.floor(Math.random()*V.length)],ip:`192.168.${Math.floor(Math.random()*255)}.${Math.floor(Math.random()*255)}`,status:k[Math.floor(Math.random()*k.length)],duration:Math.floor(Math.random()*1e3),createdAt:new Date(Date.now()-Math.random()*7*24*60*60*1e3).toLocaleString("zh-CN"),userAgent:"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",method:["GET","POST","PUT","DELETE"][Math.floor(Math.random()*4)],url:"/api/v1/graph/query",params:JSON.stringify({page:1,pageSize:20},null,2),result:JSON.stringify({code:0,message:"success"},null,2),errorMessage:Math.random()>.9?"请求超时，请稍后重试":void 0}))}function U(){n.value=!0,setTimeout(()=>{f.value=O(),n.value=!1},500)}function R(){s.actionType="",s.module="",s.username="",s.dateRange=[],g.page=1,U()}function W(h){l.value=h,d.value=!0}async function I(){try{await se.confirm("确定要导出选中的日志吗？","确认导出",{confirmButtonText:"确定",cancelButtonText:"取消",type:"info"});const h=[{key:"id",title:"日志ID"},{key:"module",title:"操作模块",formatter:t=>c(t)},{key:"action",title:"操作类型",formatter:t=>z(t)},{key:"description",title:"操作描述"},{key:"username",title:"操作人"},{key:"ip",title:"IP地址"},{key:"status",title:"操作结果",formatter:t=>t==="success"?"成功":"失败"},{key:"duration",title:"耗时(ms)"},{key:"createdAt",title:"操作时间"}];await ie(f.value,h,{filename:`操作日志_${new Date().toISOString().slice(0,10)}`,format:"excel"})}catch{C.info("已取消导出")}}return ee(()=>{U()}),(h,t)=>{const V=m("el-icon"),k=m("el-button"),x=m("el-option"),T=m("el-select"),S=m("el-form-item"),P=m("el-input"),F=m("el-date-picker"),q=m("el-form"),J=m("el-alert"),w=m("el-table-column"),M=m("el-tag"),G=m("el-table"),K=m("el-pagination"),X=m("el-card"),y=m("el-descriptions-item"),Y=m("el-descriptions"),H=m("el-dialog"),Q=te("loading");return $(),le("div",me,[e(X,{shadow:"never"},{header:a(()=>[E("div",fe,[t[9]||(t[9]=E("span",null,"操作日志",-1)),e(k,{type:"primary",size:"small",onClick:I},{default:a(()=>[e(V,null,{default:a(()=>[e(oe(ne))]),_:1}),t[8]||(t[8]=i(" 导出日志 ",-1))]),_:1})])]),default:a(()=>[e(q,{model:s,inline:"",class:"search-form"},{default:a(()=>[e(S,{label:"操作类型"},{default:a(()=>[e(T,{modelValue:s.actionType,"onUpdate:modelValue":t[0]||(t[0]=o=>s.actionType=o),placeholder:"请选择",clearable:"",style:{width:"150px"}},{default:a(()=>[e(x,{label:"全部",value:""}),e(x,{label:"新增",value:"create"}),e(x,{label:"修改",value:"update"}),e(x,{label:"删除",value:"delete"}),e(x,{label:"查询",value:"query"}),e(x,{label:"导出",value:"export"}),e(x,{label:"登录",value:"login"}),e(x,{label:"登出",value:"logout"})]),_:1},8,["modelValue"])]),_:1}),e(S,{label:"操作模块"},{default:a(()=>[e(T,{modelValue:s.module,"onUpdate:modelValue":t[1]||(t[1]=o=>s.module=o),placeholder:"请选择",clearable:"",style:{width:"150px"}},{default:a(()=>[e(x,{label:"全部",value:""}),e(x,{label:"代码图谱",value:"graph"}),e(x,{label:"扫描任务",value:"scan"}),e(x,{label:"报告管理",value:"report"}),e(x,{label:"系统设置",value:"system"})]),_:1},8,["modelValue"])]),_:1}),e(S,{label:"操作人"},{default:a(()=>[e(P,{modelValue:s.username,"onUpdate:modelValue":t[2]||(t[2]=o=>s.username=o),placeholder:"请输入",clearable:"",style:{width:"150px"}},null,8,["modelValue"])]),_:1}),e(S,{label:"操作时间"},{default:a(()=>[e(F,{modelValue:s.dateRange,"onUpdate:modelValue":t[3]||(t[3]=o=>s.dateRange=o),type:"datetimerange","range-separator":"至","start-placeholder":"开始时间","end-placeholder":"结束时间",style:{width:"300px"}},null,8,["modelValue"])]),_:1}),e(S,null,{default:a(()=>[e(k,{type:"primary",onClick:U},{default:a(()=>[...t[10]||(t[10]=[i("查询",-1)])]),_:1}),e(k,{onClick:R},{default:a(()=>[...t[11]||(t[11]=[i("重置",-1)])]),_:1})]),_:1})]),_:1},8,["model"]),e(J,{title:"本次查询共找到 1,234 条记录",type:"info",closable:!1,"show-icon":"",class:"stat-alert"}),ae(($(),D(G,{data:f.value,border:"",stripe:"",style:{"margin-top":"16px"}},{default:a(()=>[e(w,{type:"selection",width:"55"}),e(w,{prop:"id",label:"日志ID",width:"100"}),e(w,{prop:"module",label:"操作模块",width:"120"},{default:a(({row:o})=>[e(M,{size:"small",type:r(o.module)},{default:a(()=>[i(p(c(o.module)),1)]),_:2},1032,["type"])]),_:1}),e(w,{prop:"action",label:"操作类型",width:"100"},{default:a(({row:o})=>[e(M,{size:"small",type:v(o.action)},{default:a(()=>[i(p(z(o.action)),1)]),_:2},1032,["type"])]),_:1}),e(w,{prop:"description",label:"操作描述","min-width":"200","show-overflow-tooltip":""}),e(w,{prop:"username",label:"操作人",width:"120"}),e(w,{prop:"ip",label:"IP地址",width:"140"}),e(w,{prop:"status",label:"操作结果",width:"100"},{default:a(({row:o})=>[e(M,{size:"small",type:o.status==="success"?"success":"danger"},{default:a(()=>[i(p(o.status==="success"?"成功":"失败"),1)]),_:2},1032,["type"])]),_:1}),e(w,{prop:"duration",label:"耗时(ms)",width:"100",align:"right"}),e(w,{prop:"createdAt",label:"操作时间",width:"180"}),e(w,{label:"操作",width:"100",fixed:"right"},{default:a(({row:o})=>[e(k,{type:"primary",link:"",size:"small",onClick:_e=>W(o)},{default:a(()=>[...t[12]||(t[12]=[i("详情",-1)])]),_:1},8,["onClick"])]),_:1})]),_:1},8,["data"])),[[Q,n.value]]),e(K,{"current-page":g.page,"onUpdate:currentPage":t[4]||(t[4]=o=>g.page=o),"page-size":g.pageSize,"onUpdate:pageSize":t[5]||(t[5]=o=>g.pageSize=o),total:g.total,"page-sizes":[10,20,50,100],layout:"total, sizes, prev, pager, next, jumper",class:"pagination"},null,8,["current-page","page-size","total"])]),_:1}),e(H,{modelValue:d.value,"onUpdate:modelValue":t[7]||(t[7]=o=>d.value=o),title:"日志详情",width:"700px","destroy-on-close":""},{footer:a(()=>[e(k,{onClick:t[6]||(t[6]=o=>d.value=!1)},{default:a(()=>[...t[13]||(t[13]=[i("关闭",-1)])]),_:1})]),default:a(()=>[l.value?($(),D(Y,{key:0,border:"",column:2},{default:a(()=>[e(y,{label:"日志ID",span:2},{default:a(()=>[i(p(l.value.id),1)]),_:1}),e(y,{label:"操作模块"},{default:a(()=>[e(M,{size:"small",type:r(l.value.module)},{default:a(()=>[i(p(c(l.value.module)),1)]),_:1},8,["type"])]),_:1}),e(y,{label:"操作类型"},{default:a(()=>[e(M,{size:"small",type:v(l.value.action)},{default:a(()=>[i(p(z(l.value.action)),1)]),_:1},8,["type"])]),_:1}),e(y,{label:"操作描述",span:2},{default:a(()=>[i(p(l.value.description),1)]),_:1}),e(y,{label:"操作人"},{default:a(()=>[i(p(l.value.username),1)]),_:1}),e(y,{label:"操作结果"},{default:a(()=>[e(M,{size:"small",type:l.value.status==="success"?"success":"danger"},{default:a(()=>[i(p(l.value.status==="success"?"成功":"失败"),1)]),_:1},8,["type"])]),_:1}),e(y,{label:"IP地址"},{default:a(()=>[i(p(l.value.ip),1)]),_:1}),e(y,{label:"User-Agent",span:2},{default:a(()=>[i(p(l.value.userAgent),1)]),_:1}),e(y,{label:"请求方法","label-width":"100px"},{default:a(()=>[e(M,{size:"small",type:"info"},{default:a(()=>[i(p(l.value.method),1)]),_:1})]),_:1}),e(y,{label:"请求URL"},{default:a(()=>[i(p(l.value.url),1)]),_:1}),e(y,{label:"请求参数",span:2},{default:a(()=>[E("pre",be,p(l.value.params),1)]),_:1}),l.value.result?($(),D(y,{key:0,label:"响应结果",span:2},{default:a(()=>[E("pre",ge,p(l.value.result),1)]),_:1})):L("",!0),l.value.errorMessage?($(),D(y,{key:1,label:"错误信息",span:2},{default:a(()=>[E("pre",he,p(l.value.errorMessage),1)]),_:1})):L("",!0),e(y,{label:"耗时"},{default:a(()=>[i(p(l.value.duration)+" ms ",1)]),_:1}),e(y,{label:"操作时间"},{default:a(()=>[i(p(l.value.createdAt),1)]),_:1})]),_:1})):L("",!0)]),_:1},8,["modelValue"])])}}}),xe=re(ye,[["__scopeId","data-v-3ff19641"]]);export{xe as default};
