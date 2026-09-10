// 合成 OpenAI 兼容服务：只监听回环地址，不调用外部模型、不消耗真实额度。
// 原生 Agent 必须完成一次标准工具调用往返；旧版基线仍可返回旧决策格式，便于同条件比较。
import http from 'node:http';
import crypto from 'node:crypto';

const stats={calls:0,active:0,peak:0,toolCalls:0,toolResults:0,streams:0,finishedStreams:0,cancelledStreams:0};
const answer='【模型通用知识】'+('这是一段用于验证并发、连接池和流式返回的合成正文。'.repeat(32));
const server=http.createServer(async(req,res)=>{
  if(req.url==='/stats'){res.setHeader('Content-Type','application/json');res.end(JSON.stringify(stats));return;}
  if(req.method!=='POST'){res.writeHead(405);res.end();return;}
  let body='';
  for await(const part of req){body+=part;if(body.length>2_000_000){res.writeHead(413);res.end();return;}}
  let input;
  try{input=JSON.parse(body);}catch{res.writeHead(400);res.end();return;}
  stats.calls++;stats.active++;stats.peak=Math.max(stats.peak,stats.active);
  res.on('close',()=>{stats.active--;});
  const completion={id:'chatcmpl-'+crypto.randomUUID(),object:'chat.completion',created:Math.floor(Date.now()/1000),model:input.model};
  if(!input.stream){
    let message={role:'assistant',content:'{"action":"FINISH"}'},finish='stop';
    const tools=input.tools?.filter(t=>t.type==='function')||[];
    const observations=input.messages?.filter(m=>m.role==='tool')||[];
    if(tools.length && !observations.length){
      const tool=tools.find(t=>/article|community/i.test(t.function.name))||tools[0];
      const properties=tool.function.parameters?.properties||{query:{type:'string'}};
      const args=Object.fromEntries(Object.keys(properties).map(k=>[k,'合成压测：有界队列与并发保护']));
      message={role:'assistant',content:null,tool_calls:[{id:'call_'+crypto.randomUUID().replaceAll('-',''),type:'function',function:{name:tool.function.name,arguments:JSON.stringify(args)}}]};
      finish='tool_calls';stats.toolCalls++;
    }else if(observations.length){message={role:'assistant',content:'已读取工具结果，可以结合现有资料回答。'};stats.toolResults++;}
    res.setHeader('Content-Type','application/json');
    res.end(JSON.stringify({...completion,choices:[{index:0,message,finish_reason:finish}],usage:{prompt_tokens:100,completion_tokens:10,total_tokens:110}}));return;
  }
  stats.streams++;
  res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache'});
  const content=JSON.stringify({answer,citations:[]});
  let position=0,finished=false;
  const send=(delta,finish_reason=null,usage)=>res.write('data: '+JSON.stringify({...completion,object:'chat.completion.chunk',choices:[{index:0,delta,finish_reason}],...(usage?{usage}:{})})+'\n\n');
  const timer=setInterval(()=>{
    if(res.destroyed){clearInterval(timer);return;}
    if(position<content.length){
      const chunk=content.slice(position,position+Math.ceil(content.length/40));position+=chunk.length;
      send({content:chunk});
    }else{
      clearInterval(timer);send({},'stop',{prompt_tokens:100,completion_tokens:300,total_tokens:400});
      finished=true;stats.finishedStreams++;res.end('data: [DONE]\n\n');
    }
  },50);
  res.on('close',()=>{clearInterval(timer);if(!finished)stats.cancelledStreams++;});
});
server.listen(18090,'127.0.0.1',()=>console.log('合成模型已就绪，端口 18090'));
for(const signal of ['SIGTERM','SIGINT'])process.on(signal,()=>{server.close();server.closeAllConnections();});
