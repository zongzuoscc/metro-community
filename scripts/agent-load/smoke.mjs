// 只使用隔离库合成账号；凭据仅在内存中签名，不输出 JWT、API Key 或对话正文。
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
const jwtSecret=process.env.LOAD_JWT_SECRET || 'synthetic-load-only-secret-20260910-at-least-32-bytes';
const base='http://127.0.0.1:18083/api/agent';
const streamBase=process.env.STREAM_BASE || base;
const encode=x=>Buffer.from(JSON.stringify(x)).toString('base64url');
function headers(id) {
  const raw=encode({alg:'HS256',typ:'JWT'})+'.'+encode({sub:String(id),iat:Math.floor(Date.now()/1000),exp:Math.floor(Date.now()/1000)+3600});
  return {'Content-Type':'application/json',Authorization:'Bearer '+raw+'.'+crypto.createHmac('sha256',jwtSecret).update(raw).digest('base64url')};
}
async function api(id,path,method='GET',body) {
  const response=await fetch(base+path,{method,headers:headers(id),body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(20000)});
  const text=await response.text(); return {status:response.status,body:text?JSON.parse(text):null};
}
async function create(id,temporary=false) {
  const session=temporary?(await api(id,'/temporary-sessions','POST')).body.sessionId:undefined;
  await api(id,'/turns/web-search-setting','PUT',{enabled:false});
  const result=await api(id,'/turns','POST',{clientRequestId:crypto.randomUUID(),message:'隔离环境并发边界测试',temporary,temporarySessionId:session,context:{}});
  assert.equal(result.status,202); return result.body.turnId;
}
async function stream(id,turnId,after) {
  const started=Date.now();
  const response=await fetch(streamBase+`/turns/${turnId}/events`+(after?'?after='+after:''),{headers:headers(id),signal:AbortSignal.timeout(15000)});
  assert.equal(response.status,200);
  let text='',firstDeltaMs=null;
  const decoder=new TextDecoder();
  for await (const bytes of response.body) {
    text+=decoder.decode(bytes,{stream:true});
    if (firstDeltaMs===null && text.includes('event: delta')) {
      firstDeltaMs=Date.now()-started;
      if(!after && turnId===globalThis.firstTurnForStreamingProof){
        const stats=await (await fetch('http://127.0.0.1:18090/stats')).json();
        assert.equal(stats.finishedStreams,statsBefore.finishedStreams,'首段正文必须在上游结束之前到达');
        verifiedBeforeUpstreamEnd=true;
      }
    }
  }
  if (process.env.STREAM_BASE && firstDeltaMs!==null) assert.ok(firstDeltaMs<2000,'跨进程应由通知唤醒，不应等五秒补查');
  return text.split('\n\n').map(x=>x.split('\n').find(l=>l.startsWith('data:'))).filter(Boolean).map(x=>JSON.parse(x.slice(5)));
}
const statsBefore=await (await fetch('http://127.0.0.1:18090/stats')).json();
let verifiedBeforeUpstreamEnd=false;
const turnId=await create(900999);
globalThis.firstTurnForStreamingProof=turnId;
const [one,two]=await Promise.all([stream(900999,turnId),stream(900999,turnId)]);
assert.deepEqual(one.map(x=>x.eventId),two.map(x=>x.eventId));
assert.equal(one.at(-1).type,'done');
assert.equal((await api(900998,`/turns/${turnId}/events`)).status,404);
const cursor=one.find(x=>x.type==='delta').eventId;
assert.deepEqual((await stream(900999,turnId,cursor)).map(x=>x.eventId),one.slice(one.findIndex(x=>x.eventId===cursor)+1).map(x=>x.eventId));
const statsBeforeCancel=await (await fetch('http://127.0.0.1:18090/stats')).json();
const cancelled=await create(900998);
const watching=stream(900998,cancelled);
// 先证明本轮真实上游已启动且仍活跃，不能把排队期间取消误当成关闭了收费连接。
let startedCancelStream=false;
for(let attempt=0;attempt<200;attempt++){
  const stats=await (await fetch('http://127.0.0.1:18090/stats')).json();
  if(stats.streams>statsBeforeCancel.streams && stats.active>0){startedCancelStream=true;break;}
  await new Promise(resolve=>setTimeout(resolve,50));
}
assert.ok(startedCancelStream,'取消验证必须等待本轮真实上游开始流式响应');
assert.equal((await api(900998,`/turns/${cancelled}/cancel`,'POST')).body.state,'CANCELLED');
assert.equal((await watching).at(-1).type,'cancelled');
// 终态不能掩盖仍在运行的模型连接；等待有限的网络关闭传播时间后核对上游。
let activeAfterCancel,cancelledStreamsAfterCancel;
for(let attempt=0;attempt<20;attempt++){
  const stats=await (await fetch('http://127.0.0.1:18090/stats')).json();
  activeAfterCancel=stats.active;
  cancelledStreamsAfterCancel=stats.cancelledStreams;
  if(activeAfterCancel===0 && cancelledStreamsAfterCancel>statsBeforeCancel.cancelledStreams)break;
  await new Promise(resolve=>setTimeout(resolve,50));
}
assert.equal(activeAfterCancel,0,'取消后不能遗留活跃上游请求');
assert.ok(cancelledStreamsAfterCancel>statsBeforeCancel.cancelledStreams,'必须观察到真实上游连接因取消而关闭');
const temporary=await create(900997,true);
assert.equal((await stream(900997,temporary)).at(-1).type,'done');
assert.equal((await api(900997,'/temporary-sessions','DELETE')).status,204);
assert.equal((await api(900997,`/turns/${temporary}/events`)).status,404);
assert.ok(verifiedBeforeUpstreamEnd,'必须真正观察到首段正文');
const statsAfter=await (await fetch('http://127.0.0.1:18090/stats')).json();
const nativeToolCalls=statsAfter.toolCalls-statsBefore.toolCalls;
const nativeToolResults=statsAfter.toolResults-statsBefore.toolResults;
if(process.env.EXPECT_NATIVE_TOOLS==='true'){
  assert.ok(nativeToolCalls>0,'新版必须通过标准工具协议实际发起调用');
  assert.ok(nativeToolResults>0,'工具执行结果必须作为标准工具消息返回模型');
}
console.log(JSON.stringify({firstDeltaBeforeUpstreamEnd:true,multiReader:true,unauthorizedReaderBlocked:true,lastEventIdReplay:true,cancellation:true,upstreamClosedAfterCancel:true,deletedTemporarySessionBlocked:true,nativeToolCalls,nativeToolResults}));
