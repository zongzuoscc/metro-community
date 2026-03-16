# 使用官方 ES 8.4.1 镜像
FROM docker.elastic.co/elasticsearch/elasticsearch:8.4.1

# 将本地 ZIP 文件复制到容器内
COPY elasticsearch-analysis-ik-8.4.1.zip /tmp/

# 安装插件（从本地文件安装）
RUN bin/elasticsearch-plugin install -b file:///tmp/elasticsearch-analysis-ik-8.4.1.zip

