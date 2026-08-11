# 使用与 Spring Data Elasticsearch 5.5.x 支持轴一致的镜像
FROM docker.elastic.co/elasticsearch/elasticsearch:8.18.1

# 将本地 ZIP 文件复制到容器内
COPY elasticsearch-analysis-ik-8.18.1.zip /tmp/

# 安装插件（从本地文件安装）
RUN echo "d4fd45814493961c1018ec4c52a3e32cf6ee6e74c29cf172ad5814db1bb2317b  /tmp/elasticsearch-analysis-ik-8.18.1.zip" \
        | sha256sum -c - \
    && bin/elasticsearch-plugin install -b file:///tmp/elasticsearch-analysis-ik-8.18.1.zip
