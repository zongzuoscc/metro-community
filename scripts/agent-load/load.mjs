// 仅访问隔离压测端口；凭据只在进程内使用，不输出或写入结果文件。
import fs from 'node:fs';
import crypto from 'node:crypto';
const jwtSecret=process.env.LOAD_JWT_SECRET || 'synthetic-load-only-secret-20260910-at-least-32-bytes';
const output=process.env.LOAD_OUTPUT;
if (!output) throw Error('请用 LOAD_OUTPUT 指定逐请求结果文件');
const base='http://127.0.0.1:18083/api/agent';
const enc=x=>Buffer.from(JSON.stringify(x)).toString('base64url');
function headers(id){const raw=enc({alg:'HS256',typ:'JWT'})+'.'+enc({sub:String(id),iat:Math.floor(Date.now()/1000),exp:Math.floor(Date.now()/1000)+3600});return {'Content-Type':'application/json',Authorization:'Bearer '+raw+'.'+crypto.createHmac('sha256',jwtSecret).update(raw).digest('base64url')};}
async function api(id,path,method='GET',body){const response=await fetch(base+path,{method,headers:headers(id),body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(120000)});const text=await response.text();return {status:response.status,body:text?JSON.parse(text):null};}
const percentile=(a,p)=>a.length?[...a].sort((x,y)=>x-y)[Math.min(a.length-1,Math.floor(a.length*p))]:null;
const setup=async id=>{const pref=await api(id,'/turns/web-search-setting','PUT',{enabled:false});const session=await api(id,'/temporary-sessions','POST');if(pref.status!==200||session.status!==201)throw Error('Setup failed '+JSON.stringify([pref,session]));return {id,session:session.body.sessionId};};
async function turn(user,mode) {
 const start=performance.now();
 try {
  const accepted=await api(user.id,'/turns','POST',{clientRequestId:crypto.randomUUID(),message:'合成压测问题：请说明有界队列。',temporary:mode!=='persistent',temporarySessionId:mode!=='persistent'?user.session:undefined,context:{}});
  const admissionMs=Math.round(performance.now()-start);
  if(accepted.status!==202)return {status:accepted.status,code:accepted.body?.code,admissionMs};
  let firstMs=null,lastDeltaMs=null,delta=0,terminal=null,code=null,cursor=null,reconnects=0,snapshot,streamed='';
  do {
    const response=await fetch(base+'/turns/'+accepted.body.turnId+'/events'+(cursor?'?after='+encodeURIComponent(cursor):''),{headers:headers(user.id),signal:AbortSignal.timeout(100000)});
    if(!response.ok)throw Error('SSE HTTP '+response.status);
    let pending='';const decoder=new TextDecoder();
    for await(const bytes of response.body) {
      pending+=decoder.decode(bytes,{stream:true});let boundary;
      while((boundary=pending.indexOf('\n\n'))>=0){const frame=pending.slice(0,boundary);pending=pending.slice(boundary+2);const line=frame.split('\n').find(x=>x.startsWith('data:'));if(!line)continue;const e=JSON.parse(line.slice(5));cursor=e.eventId;if(e.type==='answer_start')streamed='';if(e.type==='delta'){delta++;streamed+=e.payload?.textAppend||'';firstMs??=Math.round(performance.now()-start);lastDeltaMs=Math.round(performance.now()-start);}if(['done','error','cancelled'].includes(e.type)){terminal=e.type;code=e.payload?.code;}}
    }
    snapshot=await api(user.id,'/turns/'+accepted.body.turnId);
    if(!terminal&&snapshot.body?.state==='SUCCEEDED')terminal='done';
    if(!terminal&&snapshot.body?.state==='RUNNING')reconnects++;
  } while(!terminal&&snapshot.body?.state==='RUNNING'&&performance.now()-start<120000);
  return {status:202,admissionMs,firstMs,streamSpanMs:lastDeltaMs===null?null:lastDeltaMs-firstMs,elapsedMs:Math.round(performance.now()-start),delta,terminal,code,snapshot:snapshot.body?.state,reconnects,contentMatches:streamed.length>0&&streamed===snapshot.body?.finalMessage};
 }catch(error){return {status:'client-error',code:error.name,cause:error.cause?.code,message:error.message,elapsedMs:Math.round(performance.now()-start)};}
}
if (!base.startsWith('http://127.0.0.1:18083/')) throw Error('压测只允许隔离端口');
const scenarios=process.env.LOAD_SCENARIOS?JSON.parse(process.env.LOAD_SCENARIOS):[[2,'temporary'],[10,'temporary'],[50,'temporary'],[100,'temporary'],[200,'temporary'],[10,'persistent']];
const maxUsers=Math.max(...scenarios.map(x=>x[0]));
const users=[];for(let start=1;start<=maxUsers;start+=10)users.push(...await Promise.all(Array.from({length:Math.min(10,maxUsers-start+1)},(_,i)=>setup(900000+start+i))));
for(const [n,mode] of scenarios){
 const start=Date.now();const result=await Promise.all(users.slice(0,n).map(u=>turn(u,mode)));const seconds=(Date.now()-start)/1000;
 const success=result.filter(x=>x.terminal==='done'&&x.snapshot==='SUCCEEDED'&&x.delta>0&&x.contentMatches);const codes={};result.filter(x=>!success.includes(x)).forEach(x=>{const key=x.status+':'+(x.code||(x.terminal==='done'&&!x.contentMatches?'CONTENT_MISMATCH':x.terminal)||x.snapshot);codes[key]=(codes[key]||0)+1;});
 // 保存成功和流式体验分开统计：末尾一次收到全部增量不能被平均吞吐量掩盖。
 const summary={startAt:start,endAt:Date.now(),concurrent:n,mode,success:success.length,other:codes,seconds,completedPerSecond:success.length/seconds,admissionP95:percentile(result.map(x=>x.admissionMs).filter(Number.isFinite),.95),firstDeltaP50:percentile(success.map(x=>x.firstMs),.5),firstDeltaP95:percentile(success.map(x=>x.firstMs),.95),completionP95:percentile(success.map(x=>x.elapsedMs),.95),streamSpanP50:percentile(success.map(x=>x.streamSpanMs),.5),zeroSpanCount:success.filter(x=>x.streamSpanMs===0).length};
 console.log(JSON.stringify(summary));fs.appendFileSync(output,JSON.stringify({...summary,requests:result})+'\n');
 // 仅给资源回收留出间隔，不自动重试被拒绝请求，避免用重试掩盖过载。
 await new Promise(resolve=>setTimeout(resolve,1000));
}
