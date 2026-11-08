// 启动本机现有容量测试库对应的 Backend，不创建容器、不迁移数据、不调用模型。
// 凭据从社区项目自己的容器环境读入子进程，不输出到日志或保存到文件。
const { execFileSync, spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const envFor = name => Object.fromEntries(
  JSON.parse(execFileSync('docker', ['inspect', name]))[0].Config.Env.map(value => {
    const offset = value.indexOf('=');
    return [value.slice(0, offset), value.slice(offset + 1)];
  }));
const mysql = envFor('metro-community-mysql-1');
const redis = envFor('metro-community-redis-1');
const env = { ...process.env,
  DB_URL: 'jdbc:mysql://127.0.0.1:13306/community_capacity_20260917?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai',
  DB_USERNAME: 'root', DB_PASSWORD: mysql.MYSQL_ROOT_PASSWORD,
  REDIS_HOST: '127.0.0.1', REDIS_PORT: '16379', REDIS_PASSWORD: redis.REDIS_PASSWORD,
  METRO_AI_ENABLED: 'false', METRO_DOMAIN_EVENT_DISPATCH_ENABLED: 'false',
  METRO_ARTICLE_REVISION_MODE: 'LEGACY',
  // 该身份只对应既有容量库的测试检查点，不修改或绕过正常开发库的启动授权。
  METRO_ARTICLE_ROLLOUT_BUILD_DIGEST: 'a'.repeat(64),
  METRO_ARTICLE_ROLLOUT_BINARY_GENERATION: '1', METRO_ARTICLE_ROLLOUT_SCHEMA_GENERATION: '1'
};
if (!process.env.JAVA_HOME) throw new Error('请先用 SDKMAN 选择 Java 21');
const fd = fs.openSync(path.join(root, 'target/resume-load-backend.log'), 'w');
const child = spawn(path.join(process.env.JAVA_HOME, 'bin/java'), [
  '-Xms512m', '-Xmx1024m', '-jar', 'target/community-0.0.1-SNAPSHOT-backend.jar',
  '--server.port=18083', '--spring.data.redis.database=14',
  '--spring.datasource.hikari.maximum-pool-size=10', '--spring.datasource.hikari.minimum-idle=2',
  '--server.tomcat.max-keep-alive-requests=100000',
  '--spring.rabbitmq.listener.simple.auto-startup=false',
  '--metro.ai.enabled=false', '--metro.article.revision-mode=LEGACY'
], { cwd: root, env, detached: true, stdio: ['ignore', fd, fd] });
child.unref();
console.log(`测试 Backend PID=${child.pid}，端口=18083，日志=target/resume-load-backend.log`);
