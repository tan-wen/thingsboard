# ThingsBoard 工程入门笔记

这份文档面向已经有 Java Web 和计算机基础、但第一次进入 ThingsBoard 这种大体量工程的开发者。目标不是替代官方文档，而是帮你快速建立代码地图：它由哪些模块组成，运行时靠哪些机制拼起来，请求和设备数据大概怎么流动。

## 1. 先建立整体模型

ThingsBoard 不是一个简单的 Spring MVC + MyBatis/JPA 项目。它更像一个 IoT 平台内核，核心机制包括：

- Spring Boot：负责应用启动、配置绑定、依赖装配、REST API、WebSocket、定时任务。
- 多模块 Maven：根 `pom.xml` 聚合后端、前端、传输协议、规则引擎、DAO、微服务镜像等模块。
- 条件 Bean：大量组件通过 `service.type`、`queue.type`、`database.ts.type`、`cache.type` 等配置决定是否启用。
- Queue 抽象：单体模式可用 in-memory 队列；微服务模式主要用 Kafka，把 core、rule-engine、transport 解耦。
- Actor 模型：设备、租户、规则链等高并发处理不是简单同步调用，而是通过 actor 和消息分发。
- Rule Engine：设备数据进入系统后，会被包装成 `TbMsg`，按规则链和规则节点流转。
- 多协议 Transport：HTTP、MQTT、CoAP、LwM2M、SNMP 都有独立模块，也可在单体里启用。
- 多存储后端：实体数据主要 SQL；时序数据可 SQL、TimescaleDB、Cassandra；缓存可 Caffeine 或 Redis/Valkey。
- 前后端分离但可打进单体 jar：`ui-ngx` 构建 Angular 前端资源，`application` 依赖它并一起打包。

## 2. 顶层模块速览

常用模块先看这些：

| 模块 | 作用 |
|---|---|
| `application` | 主后端应用。单体模式和 `tb-core`、`tb-rule-engine` 微服务角色都来自这里。 |
| `ui-ngx` | Angular 前端。构建产物会作为 jar 被 `application` 引入。 |
| `common` | 公共抽象、消息模型、队列、缓存、transport API、脚本执行、版本控制等基础能力。 |
| `dao` | 数据访问和业务 DAO service，包含实体、属性、时序、缓存等。 |
| `rule-engine` | 规则引擎 API 和规则节点实现。 |
| `transport` | 独立 transport 服务入口：MQTT、HTTP、CoAP、LwM2M、SNMP。 |
| `edqs` | Entity Data Query Service，用于实体查询加速，可本地或独立微服务。 |
| `msa` | 微服务和 Docker 镜像打包模块，不是核心业务代码主入口。 |
| `docker` | 微服务 docker-compose 编排示例。 |
| `packaging` | deb/rpm/zip/service 脚本的通用打包模板。 |
| `monitoring` | 独立监控服务。 |
| `rest-client` | ThingsBoard REST 客户端。 |

## 3. 主要启动入口

单体和核心服务入口：

- `application/src/main/java/org/thingsboard/server/ThingsboardServerApplication.java`
- `application/src/main/java/org/thingsboard/server/ThingsboardInstallApplication.java`

独立 transport 入口：

- `transport/mqtt/src/main/java/org/thingsboard/server/mqtt/ThingsboardMqttTransportApplication.java`
- `transport/http/src/main/java/org/thingsboard/server/http/ThingsboardHttpTransportApplication.java`
- `transport/coap/src/main/java/org/thingsboard/server/coap/ThingsboardCoapTransportApplication.java`
- `transport/lwm2m/src/main/java/org/thingsboard/server/lwm2m/ThingsboardLwm2mTransportApplication.java`
- `transport/snmp/src/main/java/org/thingsboard/server/snmp/ThingsboardSnmpTransportApplication.java`

其他服务入口：

- `edqs/src/main/java/org/thingsboard/server/edqs/ThingsboardEdqsApplication.java`
- `monitoring/src/main/java/org/thingsboard/monitoring/ThingsboardMonitoringApplication.java`
- `msa/vc-executor/src/main/java/org/thingsboard/server/vc/ThingsboardVersionControlExecutorApplication.java`

## 4. 单体和微服务怎么切换

关键配置在：

- `application/src/main/resources/thingsboard.yml`

核心开关：

```yaml
service:
  type: "${TB_SERVICE_TYPE:monolith}"
```

含义：

| `service.type` | 角色 |
|---|---|
| `monolith` | 单体模式，core、rule-engine、本地 transport 等一起在一个 JVM 内。默认值。 |
| `tb-core` | 微服务 core，主要处理 REST、WebSocket、设备状态、实体服务、核心队列。 |
| `tb-rule-engine` | 微服务规则引擎，消费规则引擎队列并执行规则链。 |
| `tb-transport` | 独立 transport 服务角色，各协议模块会用这个条件启用。 |
| `tb-vc-executor` | 版本控制执行服务。 |
| `edqs` | 独立 EDQS 服务。 |

大量 Bean 不是靠 profile，而是靠自定义注解和条件表达式启用。例如：

- `@TbCoreComponent`：`monolith` 或 `tb-core` 启用。
- `@TbRuleEngineComponent`：`monolith` 或 `tb-rule-engine` 启用。
- `@TbTransportComponent`：`monolith` 且 transport 开启，或 `tb-transport` 启用。
- `@ConditionalOnProperty`：按 `cache.type`、`queue.type`、`database.ts.type` 等选择实现。

因此，读代码时看到多个同名能力实现不要慌，先看它们的条件注解。

## 5. 配置机制

ThingsBoard 大量使用 Spring 配置占位符：

```yaml
queue:
  type: "${TB_QUEUE_TYPE:in-memory}"
```

意思是：环境变量优先，没有就用默认值。常见配置族：

| 配置 | 作用 |
|---|---|
| `service.*` | 当前进程角色、服务 ID、规则引擎分配策略。 |
| `queue.*` | 队列类型、topic、Kafka 参数、core/rule-engine/transport 通信。 |
| `transport.*` | 总 transport 开关，以及 HTTP/MQTT/CoAP/LwM2M/SNMP 子配置。 |
| `database.*` | 实体库、时序库、latest 时序库类型。 |
| `spring.datasource.*` | SQL 数据源。 |
| `cassandra.*` | Cassandra 时序库。 |
| `cache.*` | Caffeine 或 Redis/Valkey 缓存。 |
| `redis.*` | Redis/Valkey standalone、cluster、sentinel。 |
| `js.*` | JavaScript 执行器，本地 Nashorn 或远程 Node.js。 |
| `zk.*` | ZooKeeper 服务发现和分布式协调。 |
| `edges.*` | Edge 功能。 |
| `queue.edqs.*` | EDQS 同步、查询、local/remote 模式。 |

定位配置默认值时，优先全文搜索 `application/src/main/resources/thingsboard.yml`。

## 6. 前端如何进入单体 jar

`ui-ngx` 是 Angular 前端模块。默认 Maven 构建会：

1. 下载 Node/Yarn。
2. 执行 `yarn install`。
3. 执行 `yarn run build:prod`。
4. 把构建产物打成 `ui-ngx` jar。
5. `application` 依赖 `ui-ngx`，最终 fat jar 内包含前端静态资源。

所以单体部署时通常不单独部署 `ui-ngx`，运行 `application` fat jar 即可。

## 7. 设备数据主链路

一个典型的设备遥测上报，大致是：

```text
Device
  -> MQTT/HTTP/CoAP/LwM2M/SNMP Transport
  -> TransportService / 协议适配层
  -> Queue: Transport API / Core / Rule Engine
  -> Core 处理设备认证、会话、状态、RPC 等
  -> Rule Engine 收到 TbMsg
  -> Rule Chain / Rule Node 执行业务逻辑
  -> DAO 保存 attributes / timeseries / alarms / events
  -> WebSocket/REST 查询给前端展示
```

单体模式下，这些队列可能是 in-memory，进程内通信；微服务模式下，核心链路会跨 Kafka topic。

## 8. REST API 主线

REST Controller 在：

- `application/src/main/java/org/thingsboard/server/controller`

典型调用形态：

```text
Controller
  -> check 权限 / 获取 tenant/customer/user 上下文
  -> xxxService
  -> DAO / Queue / Actor / Rule Engine
  -> 返回 DTO
```

常见服务位置：

- `dao/src/main/java/org/thingsboard/server/dao/**`
- `application/src/main/java/org/thingsboard/server/service/**`

读一个 REST 功能时，建议路线：

1. 找 Controller。
2. 看调用的 Service 接口。
3. 找 ServiceImpl。
4. 看 DAO、缓存、队列、事件发布。
5. 再看权限和租户隔离逻辑。

## 9. Rule Engine 机制

规则引擎围绕几个核心概念：

| 概念 | 说明 |
|---|---|
| `RuleChain` | 规则链，类似一个可配置的数据处理流程图。 |
| `RuleNode` | 规则节点，具体执行过滤、转换、保存、告警、RPC、外部调用等动作。 |
| `TbMsg` | 规则引擎消息，包含 type、originator、metadata、data。 |
| `TbContext` | 规则节点运行上下文，提供发消息、查服务、调 DAO、访问缓存等能力。 |

主要代码：

- `rule-engine/rule-engine-api`
- `rule-engine/rule-engine-components/src/main/java/org/thingsboard/rule/engine`
- `application/src/main/java/org/thingsboard/server/actors`

如果想理解规则节点怎么写，直接看这些包：

- `rule-engine-components/.../telemetry`
- `rule-engine-components/.../filter`
- `rule-engine-components/.../transform`
- `rule-engine-components/.../rpc`
- `rule-engine-components/.../rest`
- `rule-engine-components/.../mqtt`
- `rule-engine-components/.../kafka`

规则节点单测很多，通常是学习节点行为最快的入口。

## 10. Actor 机制

ThingsBoard 用 actor 模型处理高并发和隔离。粗略理解即可：

- 不同设备、租户、规则链会有对应 actor。
- 外部请求不会全部同步落到一个 service 方法里跑完，而是变成消息投递。
- actor 保证局部串行处理，减少锁竞争。
- 队列负责跨线程、跨进程、跨服务传递消息。

主要入口可从这些包找：

- `application/src/main/java/org/thingsboard/server/actors`
- `common/actor`
- `common/message`

读 actor 代码时重点关注消息类型，而不是一开始陷进所有 actor 基类。

## 11. Queue 机制

队列抽象在：

- `common/queue`

关键接口：

- `TbQueueProducer`
- `TbQueueConsumer`
- `TbCoreQueueFactory`
- `TbRuleEngineQueueFactory`
- `TbTransportQueueFactory`
- `TbVersionControlQueueFactory`

常见实现：

| `queue.type` | 说明 |
|---|---|
| `in-memory` | 单体默认，进程内队列，开发和单机部署简单。 |
| `kafka` | 微服务/集群常用，core、rule-engine、transport 通过 topic 解耦。 |

读队列链路时，先找 factory，再找 producer/consumer 创建点。微服务下很多“调用”其实是发 protobuf 消息到队列。

## 12. Transport 机制

Transport 分两层：

1. 独立协议服务入口，位于 `transport/*`。
2. 公共协议处理能力，位于 `common/transport/*`。

协议模块：

- MQTT：`transport/mqtt` 和 `common/transport/mqtt`
- HTTP：`transport/http` 和 `common/transport/http`
- CoAP：`transport/coap` 和 `common/coap-server`、`common/transport/coap`
- LwM2M：`transport/lwm2m` 和 `common/transport/lwm2m`
- SNMP：`transport/snmp` 和 `common/transport/snmp`

单体模式中，`application` 依赖这些 transport 能力，并通过条件注解启用本地协议端口。

## 13. DAO 和存储机制

DAO 不是单一数据库模型。ThingsBoard 把数据大致分成：

| 数据类型 | 常见存储 |
|---|---|
| 租户、客户、设备、资产、规则链、仪表盘等实体 | SQL/PostgreSQL |
| 属性 attributes | SQL |
| 时序 timeseries | SQL、TimescaleDB 或 Cassandra |
| latest timeseries | SQL、TimescaleDB 或 Cassandra，可配缓存 |
| 缓存 | Caffeine 或 Redis/Valkey |
| EDQS 本地状态 | RocksDB |

关键位置：

- `dao/src/main/java/org/thingsboard/server/dao`
- `dao/src/main/java/org/thingsboard/server/dao/sqlts`
- `dao/src/main/java/org/thingsboard/server/dao/timeseries`
- `common/dao-api`

选择具体实现依赖条件注解，例如 `database.ts.type=sql/timescale/cassandra`。

## 14. 安装和初始化机制

安装入口：

- `ThingsboardInstallApplication`

脚本和数据：

- `application/src/main/data`
- `packaging/java/scripts/install`
- `msa/tb/docker/install-tb.sh`

安装做的事情通常包括：

- 创建/升级数据库 schema。
- 导入系统默认数据。
- 创建默认系统管理员、租户、客户等演示账号。
- 处理版本升级脚本。

## 15. 打包机制

常见构建命令：

```bash
mvn clean install -DskipTests -pl application -am
```

生成单体 application fat jar。默认启动是 `monolith`。

只要 fat jar，不要 deb/rpm/zip：

```bash
mvn clean install -DskipTests -pl application -am -Dpkg.skip.deb=true -Dpkg.skip.rpm=true -Dpkg.skip.zip=true
```

快速验证全仓库编译：

```bash
mvn clean install -DskipTests -Dpkg.skip=true
```

注意：`-Dpkg.skip=true` 会跳过 boot fat jar 和系统安装包，更适合开发验证，不适合直接拿部署产物。

## 16. 建议阅读路线

第一天只建议看这些：

1. `pom.xml`：知道模块关系。
2. `application/src/main/resources/thingsboard.yml`：知道运行时开关。
3. `ThingsboardServerApplication`：知道 Spring 扫描范围。
4. `common/queue/src/main/java/org/thingsboard/server/queue/util`：理解 `@TbCoreComponent` 等条件组件。
5. `application/src/main/java/org/thingsboard/server/controller/TelemetryController.java`：从 REST 看数据查询。
6. `common/transport/http/.../DeviceApiController.java`：从设备 HTTP 上报看 transport。
7. `rule-engine-components/.../telemetry/TbMsgTimeseriesNode.java`：看规则节点如何保存遥测。
8. `dao/.../timeseries` 和 `dao/.../sqlts`：看时序落库。

第二阶段再看：

- `application/src/main/java/org/thingsboard/server/actors`
- `common/queue/src/main/java/org/thingsboard/server/queue/provider`
- `docker/docker-compose.yml`
- `msa/*/docker/start-*.sh`

## 17. 调试和搜索技巧

常用搜索方式：

```bash
rg "TB_SERVICE_TYPE|service.type|@TbCoreComponent|@TbRuleEngineComponent|@TbTransportComponent"
rg "queue.type|TbQueueProducer|TbQueueConsumer|TbCoreQueueFactory"
rg "database.ts.type|TimeseriesDao|TimeseriesLatestDao"
rg "TbMsg|TbContext|RuleNode"
rg "@RestController|@RequestMapping" application/src/main/java/org/thingsboard/server/controller
```

遇到“为什么这个 Bean 没生效”：

1. 看是否有 `@ConditionalOnExpression` 或 `@ConditionalOnProperty`。
2. 看当前 `service.type`。
3. 看当前 `queue.type`、`cache.type`、`database.ts.type`。
4. 看是否在对应 Spring `@ComponentScan` 范围内。

遇到“数据怎么从设备进来”：

1. 先确定协议：HTTP/MQTT/CoAP/LwM2M/SNMP。
2. 找 `transport` 或 `common/transport` 对应模块。
3. 找 `TransportService` 调用。
4. 跟到 queue producer。
5. 跟到 core/rule-engine consumer。
6. 跟到规则节点或 DAO。

## 18. 最小心智模型

如果只记一句话：

> ThingsBoard 是一个用 Spring Boot 装配起来的 IoT 消息处理平台；设备数据先进入 Transport，再通过 Queue/Actor 分发到 Core 和 Rule Engine，最后由 DAO 落到实体库、时序库和缓存中；单体和微服务主要靠 `service.type` 与条件 Bean 切换。

