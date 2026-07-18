# Model Registry 领域规则实施计划

> **面向执行代理：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，按任务逐项实施本计划。步骤使用复选框（`- [ ]`）跟踪。

**目标：** 建立 Model Registry 独立 Maven 服务，并实现端点设置与模型能力组合的可测试领域约束。

**架构：** 新增独立 Spring Boot 服务和 `model_registry` 包根；本批只包含无外部 I/O 的领域对象。后续批次通过独立应用服务接入 SecretStore、PostgreSQL 和管理 HTTP，Inference Gateway 负责服务商访问。

**技术栈：** Java 21、Spring Boot 4.1.0、Maven、JUnit 5；遵循现有服务的中文注释与测试风格。

## 全局约束

- 项目文档和人工代码注释统一使用简体中文。
- 不提交明文 API Key、密码、Token 或真实凭据。
- 不修改已发布 Flyway V1/V2；本批不增加迁移。
- 领域规则必须先写失败测试，再写最小实现。
- 每个独立步骤通过定向验证后单独提交或与紧密相关步骤合并提交。

---

### 任务 1：新增 Model Registry Maven 模块骨架

**文件：**
- 修改：`pom.xml`，加入 `services/model-registry-service` reactor 模块。
- 新增：`services/model-registry-service/pom.xml`。
- 新增：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/ModelRegistryServiceApplication.java`。
- 新增：`services/model-registry-service/src/main/resources/application.yaml`。

**接口：**
- 产出：可被根 Maven reactor 编译的独立 Spring Boot 服务模块。

- [x] **步骤 1：写模块构建冒烟测试**

运行根构建并确认新模块当前不存在，记录预期失败原因后再建立模块文件。

- [x] **步骤 2：创建最小模块文件**

使用现有 Notification Service 的 Spring Boot 依赖边界，加入 WebMVC、Validation、Data JPA、Flyway、PostgreSQL、Actuator 和测试依赖；应用入口只声明 `@SpringBootApplication`。

- [x] **步骤 3：运行模块编译验证**

运行 `mvn "-Dmaven.repo.local=<项目绝对路径>\\.m2\\repository" -pl services/model-registry-service test`，预期 `BUILD SUCCESS`。

- [x] **步骤 4：合并到本批提交**

提交信息：`build: 建立Model Registry服务骨架`。

### 任务 2：端点设置领域对象

**文件：**
- 新增：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/domain/endpoint/EndpointProtocol.java`。
- 新增：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/domain/endpoint/EndpointSettings.java`。
- 新增：`services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/domain/endpoint/EndpointSettingsTest.java`。

**接口：**
- 产出：`EndpointSettings(String displayName, URI baseUrl, EndpointProtocol protocol, Duration requestTimeout, boolean enabled)`。

- [x] **步骤 1：写失败测试**

覆盖名称规范化、非 HTTPS URL、凭据/查询/片段 URL、超时范围和协议非空约束。

- [x] **步骤 2：运行测试确认红灯**

运行 `mvn "-Dmaven.repo.local=<项目绝对路径>\\.m2\\repository" -pl services/model-registry-service "-Dtest=EndpointSettingsTest" test`，预期因领域类型不存在而失败。

- [x] **步骤 3：实现最小领域规则**

使用 `URI` 结构化校验 URL，不通过字符串拼接判断协议；规范化名称并拒绝控制字符、空主机、用户信息、查询和片段。

- [x] **步骤 4：运行绿色测试**

同一命令预期全部通过。

### 任务 3：模型能力领域对象

**文件：**
- 新增：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/domain/model/ModelInterfaceType.java`。
- 新增：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/domain/model/InputModality.java`。
- 新增：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/domain/model/OutputModality.java`。
- 新增：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/domain/model/ModelCapabilities.java`。
- 新增：`services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/domain/model/ModelCapabilitiesTest.java`。

**接口：**
- 产出：`ModelCapabilities(ModelInterfaceType interfaceType, Set<InputModality> inputModalities, OutputModality outputModality, boolean streaming, boolean systemPrompt, boolean reasoning, int contextLength, int maxOutputLength)`。

- [x] **步骤 1：写失败测试**

覆盖空输入模态、正数边界、最大输出超过上下文、聊天输出图片、图片生成输出文本、图片生成声明流式/System Prompt/推理等冲突。

- [x] **步骤 2：运行测试确认红灯**

运行 `mvn "-Dmaven.repo.local=<项目绝对路径>\\.m2\\repository" -pl services/model-registry-service "-Dtest=ModelCapabilitiesTest" test`，预期因类型不存在而失败。

- [x] **步骤 3：实现最小组合校验**

复制输入集合防止外部修改；所有错误使用简体中文 `IllegalArgumentException`；仅实现设计文档中的 V1 规则。

- [x] **步骤 4：运行模块和全量测试**

运行模块全量测试，再运行 `./scripts/verify-all.ps1`，确认新增模块不会破坏既有服务。

- [x] **步骤 5：更新进度并纳入本批提交**

更新 `progress.md` 和 `task_plan.md` 的阶段 4 记录；提交信息：`feat: 建立Model Registry领域规则`，随后推送 `origin/main`。
