// 简历阅读链路压测：只允许本机专属测试端口，不发送写接口或调用模型。
// 使用成熟压测器 Autocannon，HTTP 成功之外还检查业务码、文章 ID 和正文。
const autocannon = require('../../target/resume-load/node_modules/autocannon');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const output = path.resolve(process.env.LOAD_OUTPUT || 'target/resume-load/results');
fs.mkdirSync(output, { recursive: true });
const base = 'http://127.0.0.1:18083';

// 密码只在容器内部通过已有环境变量注入，不进入结果或命令行参数。
function mysqlStats() {
  const text = execFileSync('docker', ['exec', 'metro-community-mysql-1', 'sh', '-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SHOW GLOBAL STATUS WHERE Variable_name IN (\'Com_select\',\'Threads_connected\',\'Threads_running\');"'
  ], { encoding: 'utf8' });
  return Object.fromEntries(text.trim().split('\n').map(line => {
    const [key, value] = line.split('\t');
    return [key, Number(value)];
  }));
}

async function run(name, connections, duration, articleCount = 100) {
  let invalidResponses = 0;
  let checkedResponses = 0;
  const requests = Array.from({ length: articleCount }, (_, offset) => {
    const id = 100001 + offset;
    return {
      method: 'GET', path: `/api/article/detail/${id}`,
      onResponse(status, body) {
        checkedResponses++;
        try {
          const result = JSON.parse(body);
          // 全局 JSON 序列化会把 Long 输出为字符串，兼容这一实际接口约定。
          if (status !== 200 || result.code !== 200 || String(result.data?.id) !== String(id)
              || Buffer.byteLength(result.data.content || '', 'utf8') !== 2016) {
            invalidResponses++;
          }
        } catch { invalidResponses++; }
      }
    };
  });
  // 用服务端真实浏览量交叉核对客户端完成数，防止压测器连接统计异常被误当成吞吐。
  const viewTotal = () => Number(execFileSync('docker', ['exec', 'metro-community-redis-1',
    'sh', '-c', `REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning -n 14 --raw EVAL 'local n=0; for id=100001,100000+tonumber(ARGV[1]) do n=n+tonumber(redis.call("GET","article:view:count:"..id) or "0") end; return n' 0 ${articleCount}`
  ], { encoding: 'utf8' }).trim());
  const viewsBefore = viewTotal();
  const before = mysqlStats();
  const startedAt = new Date().toISOString();
  const result = await autocannon({ url: base, connections, duration, pipelining: 1,
    timeout: 10, requests, headers: { 'Accept-Encoding': 'identity' } });
  const after = mysqlStats();
  const viewIncrement = viewTotal() - viewsBefore;
  const summary = { name, startedAt, connections, duration: result.duration, articleCount,
    completed: result.requests.total, checkedResponses, invalidResponses,
    errors: result.errors, timeouts: result.timeouts, non2xx: result.non2xx,
    requestsPerSecond: result.requests.average, latency: result.latency,
    responseBytes: result.throughput.total, viewIncrement, mysqlBefore: before, mysqlAfter: after,
    mysqlSelectDelta: after.Com_select - before.Com_select };
  fs.writeFileSync(path.join(output, `${name}.json`), JSON.stringify({ summary, raw: result }, null, 2));
  console.log(JSON.stringify(summary));
  // 任何业务错误均显式失败，不能把快速返回错误当成吞吐能力。
  if (!checkedResponses || checkedResponses !== result.requests.total
      || invalidResponses || result.errors || result.timeouts || result.non2xx
      || viewIncrement < checkedResponses || viewIncrement > checkedResponses + connections * 2) {
    throw new Error(`压测未通过：${name}`);
  }
}

(async () => {
  const health = await fetch(base + '/actuator/health');
  if (!health.ok) throw new Error('隔离测试服务未就绪');
  if (process.env.LOAD_SCENARIO === 'broad') {
    await run('broad-128', 128, 60, 10000);
  } else {
    await run('warmup-32', 32, 15);
    await run('hot-128-a', 128, 60);
    await run('hot-256', 256, 60);
    await run('hot-128-b', 128, 60);
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
