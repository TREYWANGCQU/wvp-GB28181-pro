# WVP-PRO GitHub Wiki 自动化改造与持续发布系统分析方案

本文档针对现行 [generate-wiki.ps1](https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master/scripts/generate-wiki.ps1) 存在的单体硬编码、缺少智能编目、本地网络强耦合及缺乏云端自动化同步等痛点，制定**「本地 AI Skill 智能编目 + 代码仓受控暂存 + GitHub Actions 确定性发布」**的双阶段自动化改造方案。

---

## 1. Objectives (目标与愿景)

### 1.1 核心痛点诊断
1. **硬编码维护成本高**：当前 [generate-wiki.ps1](https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master/scripts/generate-wiki.ps1) 中 `$DocMappings`、`_Sidebar.md` 及 `Home.md` 均为强硬编码。当 [doc/reaticle_docs/](https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master/doc/reaticle_docs) 下新增 `feats/`、`research/`、`debug/` 等模块文档时，无法自动感知与纳管，必须人工修改数百行 PowerShell 脚本。
2. **职责过度耦合**：单体脚本同时承担了“语义提取与文章摘要”、“内联相对链接重写”、“门户模板拼装”、“本地 Git 操作与远程 Push”四项异构职责，缺乏分层与容错能力。
3. **本地网络与凭据阻隔**：中国大陆直连 GitHub SSH (`git@github.com:...`) 存在频繁的网络抖动与握手超时风险，且依赖开发机私有 SSH 密钥，团队协作时无法实现可审计的统一发布。
4. **AI 与 CI 能力错配**：
   - AI 大模型擅长文档提炼、语义归类、大纲提炼与关联推荐，但在 CI Runner 中运行 AI 存在 Token 消耗、API 密钥托管泄露、超时与不确定性等问题；
   - GitHub Actions 擅长确定性校验、无状态构建、权限管控与极速部署，但不具备天然的文档语义理解能力。

### 1.2 改造目标
1. **职责严格解耦（双阶段闭环）**：
   - **阶段一（本地智能编目）**：开发者通过执行本地 AI Skill，智能解析 [doc/reaticle_docs/](https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master/doc/reaticle_docs) 的全量变动，提炼摘要与元数据，自动生成结构化导航（`Home.md`、`_Sidebar.md`、`_Footer.md`）并输出至代码仓受控暂存区 `doc/wiki_staging/`；
   - **阶段二（云端持续发布）**：提交代码触发 GitHub Actions，CI 在 Ubuntu 容器内执行确定性的纯净同步流水线，零 AI 依赖、毫秒级将暂存区内容幂等推送到远端 GitHub Wiki。
2. **动态可扩展**：支持任意深度的文档层级（如 `doc/reaticle_docs/feats/xxx.md`），自动建立 Wiki 扁平化映射，杜绝脚本硬编码。
3. **零侵入与透明审查**：Wiki 的最终渲染结果以明文形式纳入主仓版本控制（`doc/wiki_staging/`），改动在 PR 或 Git Diff 中完全透明可审。

---

## 2. Constraints & Boundary Contract Matrix (约束与边界契约矩阵)

本方案涉及本地开发环境、Git 代码仓、GitHub Actions Runner 以及 GitHub Wiki 特异性存储系统之间的异构交互，其刚性约束与边界契约如下表所示：

### 2.1 边界契约矩阵 (Boundary Contract Matrix)

| 边界维度 | 涉及系统/实体 | 刚性约束 (Rigid Constraint) | 契约实现机制 (Contract Implementation) |
| :--- | :--- | :--- | :--- |
| **Wiki 存储结构约束** | GitHub Wiki 远端存储库 | **GitHub Wiki 仓库不支持任何子目录**，所有页面文件必须完全平铺在根目录。同名文件在不同子目录下会发生碰撞。 | 采用**前缀命名空间映射契约**：源相对路径 `feats/gb-voice.md` 转换为扁平文件名 `Feats-GB-Voice.md`，保证全局唯一性。 |
| **Wiki 链接规范约束** | Markdown 解析器 (Gollum) | 内部页面跳转必须遵循 `[Title](Page-Name)` 或 `[[Page-Name\|Title]]` 规范，不得包含 `.md` 后缀，锚点必须符合 GitHub 标题 Slugify 算法。 | 正则重写引擎过滤 `.md` 扩展名，剔除跨目录相对路径符号（如 `../../`），保留合法锚点。 |
| **代码引用约束** | GitHub Blob 源码系统 | 文档中若引用项目源码（如 `src/main/...` 或 `pom.xml`），不能直接平移为 Wiki 内联链接，否则在 Wiki 页面中点击会报 404。 | 识别相对跳出链接与 `file:///` 协议，统一重写为带主分支上下文的权威 URL：`https://github.com/<owner>/<repo>/blob/master/<path>`。 |
| **CI 权限边界契约** | GitHub Actions & Secrets | **默认的 `secrets.GITHUB_TOKEN` 对同名 Wiki 仓库 (`<repo>.wiki.git`) 无写权限**，直接 push 会触发 `403 Forbidden`。 | 必须配置具备 `repo` 作用域的 Personal Access Token (如 `WIKI_SYNC_TOKEN`)，或专用的 Deploy Key（SSH 私钥注入 Secrets，公钥写入仓库 Deploy Keys 且勾选 Write Access）。 |
| **跨平台环境约束** | Windows 11 (本地) vs Ubuntu-latest (CI) | 本地主要使用 PowerShell (pwsh) 与 Windows 文件路径分隔符 (`\`)；CI 运行于 Linux 容器，路径分隔符为 `/`，且默认无 Windows 特有环境。 | 所有文件操作采用 POSIX 相对路径表示；CI 同步脚本采用跨平台脚本（Shell / Python 3 / pwsh core），确保无依赖跨平台一致性。 |
| **字符编码与换行契约** | Git 跨平台传输 | 中文文件名、中文超链接以及换行符 (`CRLF` vs `LF`) 在 Git 同步时易发生脏变动。 | 全流程强制采用 **UTF-8 without BOM** 编码；中文路径参数与 URL 片段进行 RFC 3986 编码；配置 `.gitattributes` 对 staging 目录固化 LF 换行。 |

---

## 3. Architecture (架构设计)

### 3.1 总体架构拓扑

```mermaid
flowchart TB
    subgraph LOCAL["本地开发者工作区 (Windows 11 / Antigravity IDE)"]
        DOCS["工程文档源<br/>(doc/reaticle_docs/**/*.md)"]
        SKILL["AI Wiki Curator Skill<br/>(本地触发 / 智能编目)"]
        STAGING["受控 Wiki 暂存区<br/>(doc/wiki_staging/*.md)"]
        GIT_LOCAL["本地 Git 暂存与提交"]
    end

    subgraph REMOTE_MAIN["GitHub 主代码仓 (wvp-GB28181-pro)"]
        MASTER["master 分支"]
        ACTIONS["GitHub Actions 引擎<br/>(wiki-sync.yml)"]
    end

    subgraph REMOTE_WIKI["GitHub Wiki 仓库 (wvp-GB28181-pro.wiki)"]
        WIKI_REPO["Wiki 独立 Git 仓库<br/>(平铺 Markdown 页面)"]
        WIKI_WEB["GitHub Wiki 门户展示<br/>(Home / _Sidebar / _Footer)"]
    end

    DOCS -->|1. 语义扫描与解析| SKILL
    SKILL -->|2. 链接重写/大纲与元数据提炼| STAGING
    STAGING -->|3. git commit / git push| GIT_LOCAL
    GIT_LOCAL -->|4. Push 到 master 分支| MASTER

    MASTER -->|5. 触发 Webhook (paths: doc/wiki_staging/**)| ACTIONS
    ACTIONS -->|6. 认证克隆/对比差异/幂等推送| WIKI_REPO
    WIKI_REPO -->|7. 页面渲染| WIKI_WEB
```

### 3.2 双阶段详细数据流模型

#### 阶段一：本地 AI Skill 智能编目 (Local AI Curation Flow)
1. **输入源扫描**：AI Skill 遍历 [doc/reaticle_docs/](https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master/doc/reaticle_docs) 及其所有子目录，提取新增、修改的 Markdown 文件。
2. **元数据提取与拓扑构建**：
   - 提取每篇文档的 `# Title`、业务分类（Category）、文档定位（Description）、优先级权重（Weight）；
   - 构建全量文档图谱，识别前置方案（Solution）与实施细则（Implementation）的关联依赖。
3. **语法清洗与链接重构**：
   - 剔除首行元数据注释（如 `<!-- doc/reaticle_docs/... -->`）；
   - 将内部相对路径（如 `../feats/gb28181-voice-talk-and-broadcast-upgrade-solution.md`）转换为 Wiki 内部页面名称（如 `Feats-GB28181-Voice-Talk-Upgrade-Solution`）；
   - 将源代码文件相对引用（如 `../../src/main/java/...`）转换为指向 GitHub master 分支的绝对链接；
   - 校验图片与图表（Mermaid），确保在 GitHub 渲染器中正常展示。
4. **导航系统全自动生成**：
   - 自动生成全局侧边栏 `_Sidebar.md`，按业务模块分组（编译与部署、Docker 全栈、ONVIF 协议栈、国标扩展能力、问题排查等）；
   - 自动生成门户主页 `Home.md`，包含全景架构图、动态模块卡片、近期更新日志；
   - 自动生成全局页脚 `_Footer.md`。
5. **受控写入**：所有生成物写入项目代码仓内的 `doc/wiki_staging/` 目录。开发者可直接本地预览、检查 Git Diff 并提交。

#### 阶段二：云端 GitHub CI 确定性发布 (Cloud CI Automated Sync Flow)
1. **触发拦截**：
   - 当代码合入 `master` 且变动路径匹配 `doc/wiki_staging/**` 或 `doc/reaticle_docs/**` 时自动运行；
   - 提供 `workflow_dispatch` 支持手动点击触发。
2. **容器沙箱执行**：
   - Runner 环境：`ubuntu-latest`；
   - 检出主仓源码及 `doc/wiki_staging/` 目录；
   - 通过配置的认证凭据（`WIKI_SYNC_TOKEN` 或 `DEPLOY_KEY`）检出 `https://github.com/TREYWANGCQU/wvp-GB28181-pro.wiki.git`；
   - 执行同步清洗与文件覆盖（rsync / 跨平台拷贝）；
   - 检查 `git status --porcelain`，若无实际变更则安全退出，若有变更则以标准 commit message（关联主仓 commit SHA）提交并 push。

---

## 4. Work Breakdown Structure (工作分解结构 - WBS)

根据系统分析，整个改造方案拆解为以下五个工程模块：

```mermaid
mindmap
  root((Wiki 自动化改造))
    WBS 1.0 架构与目录重构
      1.1 建立 doc/wiki_staging 规范目录
      1.2 配置 .gitattributes 与 .gitignore 策略
      1.3 改造 generate-wiki.ps1 成为本地离线构建器
    WBS 2.0 本地 AI Skill 设计
      2.1 编写 wiki-curator Skill 规范
      2.2 实现智能目录分类与元数据提取提示词工程
      2.3 定义 Home/_Sidebar 模板组装引擎
    WBS 3.0 确定性链接转换规则固化
      3.1 路径扁平化命名算法实现 (Path-to-WikiPage)
      3.2 源代码 GitHub Blob 权威链接重写
      3.3 相对引用死链扫描与验证逻辑
    WBS 4.0 GitHub Actions 流水线建设
      4.1 编写 .github/workflows/wiki-sync.yml
      4.2 配置并发锁与权限约束
      4.3 实现变更检测与幂等提交
    WBS 5.0 凭据安全与门禁验证
      5.1 配置 GitHub Secrets (WIKI_SYNC_TOKEN)
      5.2 实施端到端演练与故障自愈测试
```

### WBS 详细条目清单

#### WBS 1.0: 架构与目录重构
- **1.1 建立受控暂存目录**：在代码仓根目录下新建 `doc/wiki_staging/` 目录，纳入 Git 版本控制。
- **1.2 跨平台属性固化**：在 `.gitattributes` 中声明 `doc/wiki_staging/*.md text eol=lf`，防止 Windows/Linux 换行符不一致产生虚假变更。
- **1.3 改造原单体脚本**：将 [generate-wiki.ps1](https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master/scripts/generate-wiki.ps1) 剥离出本地推送逻辑，转变为由本地 AI 或开发者独立调用的“规则式本地构建验证器”（重构为 `scripts/build-wiki-staging.ps1`），仅负责将文档编译输出到 `doc/wiki_staging/`，不再直接操作远程 Wiki 仓库。

#### WBS 2.0: 本地 AI Skill 规范与能力设计
- **2.1 Skill 规范构建 (`wiki-curator`)**：
  - 存放位置：`C:\Users\Reaticle\.gemini\config\skills\wiki-curator\SKILL.md`；
  - 核心职责：读取 `doc/reaticle_docs/` 下全量文件列表，按业务语义自动分类（如“ONVIF 协议”、“国标扩展”、“Docker 交付”、“编译开发”、“运维排障”等）；
  - 自动为每篇文档生成中文简述，更新全局大纲。
- **2.2 模板拼装器**：
  - 定义 `Home.md` 与 `_Sidebar.md` 的动态占位结构；
  - 自动为新增文档在侧边栏对应类别下追加条目；
  - 自动为过时或调试方案标记归档状态。

#### WBS 3.0: 确定性链接转换规则固化
- **3.1 路径到 Wiki Page 的确定性映射算法**：
  - 规则：以子目录作为前缀，连字符分隔，大写首字母，去除扩展名。
  - 示例：
    - `doc/reaticle_docs/compile.md` $\rightarrow$ `Compile-and-Dev-Guide.md`
    - `doc/reaticle_docs/feats/gb-cascade-custom-channel-batch-excel-solution.md` $\rightarrow$ `Feats-GB-Cascade-Custom-Channel-Batch-Excel-Solution.md`
    - `doc/reaticle_docs/feats/gb28181-voice-talk-and-broadcast-upgrade-solution.md` $\rightarrow$ `Feats-GB28181-Voice-Talk-and-Broadcast-Upgrade-Solution.md`
    - `doc/reaticle_docs/research/gb28181-voice-talk-and-broadcast-failure-analysis.md` $\rightarrow$ `Research-GB28181-Voice-Talk-and-Broadcast-Failure-Analysis.md`
    - `doc/reaticle_docs/debug/2026-09-11-onvif-batch-export-overwrite-and-id-reuse-plan.md` $\rightarrow$ `Debug-2026-09-11-Onvif-Batch-Export-Overwrite-and-Id-Reuse-Plan.md`
- **3.2 源码重写引擎**：
  - 将所有指向工程内代码的相对链接统一解析为绝对定位：`https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master/<rel_path>`。

#### WBS 4.0: GitHub Actions CI 流水线建设
- **4.1 工作流定义**：`.github/workflows/wiki-sync.yml`
  - 监听分支：`master`
  - 监听路径：`doc/wiki_staging/**` 与 `doc/reaticle_docs/**`
  - 并发保护：`concurrency: { group: wiki-sync, cancel-in-progress: false }`
- **4.2 部署步骤编排**：
  1. `actions/checkout@v4`（检出当前主仓代码）；
  2. `actions/checkout@v4`（检出 Wiki 仓库到子目录 `.wiki`，指定 token 凭证）；
  3. 目录镜像同步：执行跨平台复制或 rsync 同步；
  4. 提交校验：若 `git diff --exit-code` 检测到无变化，则记录日志并跳过；若有变化，以 `docs(wiki): sync from commit ${{ github.sha }}` 进行签名提交并 push。

#### WBS 5.0: 凭据安全与门禁验证
- **5.1 凭据安全协议**：
  - 创建 GitHub Personal Access Token (Classic 或 Fine-grained)，授予目标仓库的 Wiki / Content 写权限；
  - 在 GitHub 仓库设置中新增 Secret：`WIKI_SYNC_TOKEN`；
  - CI 脚本严禁在日志中明文打印任何包含 Token 的 Remote URL。
- **5.2 门禁与灾备测试**：
  - 校验当开发者在没有 AI 介入时，直接通过规则脚本是否依然能够生成基础 Staging；
  - 校验当网络异常或 Token 失效时，CI 是否能精准报错并告警。

---

## 5. Acceptance Criteria (验收标准与门禁规范)

| 序号 | 验收模块 | 验证方式 / 指标项 | 预期合规标准 (Pass Criteria) |
| :---: | :--- | :--- | :--- |
| **AC-1** | **本地 AI Skill 执行** | 本地运行 `wiki-curator` Skill | 1. 完整识别 `doc/reaticle_docs/` 下所有现存文档（包括全部新增的 `feats/` 与 `research/` 专项文档）；<br/>2. 自动在 `doc/wiki_staging/` 下生成全部扁平化 `.md` 文件及 `Home.md`、`_Sidebar.md`、`_Footer.md`；<br/>3. 生成文件无空引用、无死链。 |
| **AC-2** | **受控暂存审查** | 执行 `git status` 与 `git diff` | 1. 所有 Wiki 变更均在 `doc/wiki_staging/` 中以人类可读的明文 Diff 呈现；<br/>2. 编码格式全量为 UTF-8 无 BOM，换行符统一为 LF；<br/>3. 主仓源码目录未受任何非预期污染。 |
| **AC-3** | **GitHub Actions 触发** | Push 包含 `doc/wiki_staging/` 的提交至 `master` | 1. GitHub Actions 自动触发 `wiki-sync.yml`；<br/>2. 无凭证泄露警告，Job 运行耗时 $\le$ 45 秒；<br/>3. 幂等性测试：连续触发两次相同构建，第二次自动识别并跳过 Commit。 |
| **AC-4** | **Wiki 线上可用性** | 浏览器访问 GitHub Wiki 页面 | 1. Wiki 首页架构图渲染无异常，各分类树状链接全部可正常点开；<br/>2. 各级文档内跳转至代码仓的源码链接全部指向正确分支；<br/>3. 无 404 悬空死链。 |
| **AC-5** | **本地脚本降级能力** | 不启动 AI，直接执行 `scripts/build-wiki-staging.ps1` | 能够基于确定性规则构建出全量 Staging 页面，保证在离线或无 AI 场景下的兜底可用性。 |

---

## 6. 实施路线图 (Implementation Roadmap)

1. **第一阶段：受控目录与基线脚本重构（1人天）**
   - 建立 `doc/wiki_staging/` 目录；
   - 改造 [generate-wiki.ps1](https://github.com/TREYWANGCQU/wvp-GB28181-pro/blob/master/scripts/generate-wiki.ps1) 为纯本地构建脚本 `scripts/build-wiki-staging.ps1`，支持自动扫描子目录。
2. **第二阶段：开发本地 AI 编目 Skill（1人天）**
   - 在用户全局 Skill 库中创建 `wiki-curator` Skill，固化分析提示词与模板语法；
   - 本地跑通一次全量文档编目，生成包含 `feats` 与 `research` 最新内容的 `doc/wiki_staging/`。
3. **第三阶段：配置 GitHub Actions 与凭据（0.5人天）**
   - 创建 `.github/workflows/wiki-sync.yml`；
   - 配置 `WIKI_SYNC_TOKEN` Secret，完成第一次端到端自动化 CI 推送。
4. **第四阶段：在线验收与长效归档（0.5人天）**
   - 检查在线 GitHub Wiki 展示效果，修正可能遗漏的边缘锚点，正式归档交付。

---

## 7. 运维与配置实战操作手册 (Setup & Operation Runbook)

### 7.1 为什么必须配置 `WIKI_SYNC_TOKEN`？（必要性原理解析）
在标准的 GitHub Actions 中，系统默认提供了一个动态生成的临时凭证 `secrets.GITHUB_TOKEN`。然而在涉及 Wiki 自动化时，存在以下**平台级安全刚性约束**：
1. **Wiki 仓库的独立性**：GitHub Wiki 并非主仓库的一个子目录或普通分支，而是物理上完全隔离的独立 Git 存储库（`https://github.com/<owner>/<repo>.wiki.git`）；
2. **权限越界阻隔（Scope Boundary）**：GitHub 出于防止越权写入的安全策略，`GITHUB_TOKEN` 的默认权限被严格限制在主仓库自身内，**无权对同名 `.wiki.git` 执行推送**。若直接使用 `GITHUB_TOKEN`，在 `git push` 步骤必然返回 `403 The requested URL returned error: 403 Forbidden`；
3. **解决方案**：必须在 GitHub 个人设置中生成一个具备 `repo` 完整作用域的 Personal Access Token (PAT)，并将其注入主仓库的 Secrets（命名为 `WIKI_SYNC_TOKEN`）。CI 通过该 Token 进行身份认证，即可合法向 Wiki 仓库推送。

---

### 7.2 `WIKI_SYNC_TOKEN` 申请与配置四步法

#### 第一步：进入个人 Developer Settings
1. 登录 GitHub，点击右上角个人头像，选择 **Settings**（用户全局设置，非仓库设置）；
2. 页面拉至左下角，点击 **Developer settings**；
3. 选择 **Personal access tokens** -> **Tokens (classic)**（推荐 Classic Token，兼容性最广）。

#### 第二步：生成专属 Token
1. 点击右上角 **Generate new token** -> **Generate new token (classic)**；
2. **Note**（备注名）：填入可明确识别的名称，例如：`WVP-PRO Wiki Sync Deploy Token`；
3. **Expiration**（有效期）：建议根据安全合规策略设置为 `90 days`、`1 year` 或 `No expiration`（团队公开开源项目可按需选定）；
4. **Select scopes**（勾选作用域）：
   - 勾选顶级权限 **`repo`**（Full control of private repositories / public repositories）；
   - *说明*：勾选 `repo` 会自动包含其下的 `repo:status`、`repo_deployment`、`public_repo` 等全部子项，这是获得 Wiki 读写权限的必要条件。
5. 点击页面底部绿色的 **Generate token** 按钮。

#### 第三步：安全复制 Token
- 页面将显示生成的 Token 字符串（形如 `ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`）；
- **注意**：该字符串离开此页面后将无法再次查看，请立即点击复制图标复制到剪贴板。

#### 第四步：在代码仓库中配置 Secret
1. 打开当前代码仓库页面（如 `TREYWANGCQU/wvp-GB28181-pro`）；
2. 依次点击顶部导航 **Settings** -> 左侧边栏 **Secrets and variables** -> **Actions**；
3. 点击绿色按钮 **New repository secret**；
4. **Name** 填入：`WIKI_SYNC_TOKEN`（必须完全一致，与 `.github/workflows/wiki-sync.yml` 中的引用名称严格匹配）；
5. **Secret** 粘贴刚才复制的 Token 字符串；
6. 点击 **Add secret** 保存。

---

### 7.3 首次启用 GitHub Wiki 的避坑指南（关键步骤）

> [!CAUTION]
> **避坑警告：GitHub 默认不会物理初始化 `.wiki.git` 仓库！**
> 
> 若一个 GitHub 仓库从未使用过 Wiki，即使已配置了 Token 和 CI 工作流，CI 在执行 `actions/checkout` 克隆 `.wiki` 仓库时仍会报错：
> `fatal: repository 'https://github.com/.../....wiki.git/' not found` (404)。

#### 首次激活操作步骤：
1. 打开代码仓库主页，点击顶部导航栏中的 **Wiki** 标签页；
2. 页面会显示欢迎界面，点击中间的绿色按钮 **Create the first page**；
3. 无需输入复杂内容，输入标题 `Home`，内容随意输入一个字母（如 `init`）；
4. 点击右下角 **Save Page**；
5. **生效标志**：页面成功保存后，GitHub 底层才会真正建立 `https://github.com/<owner>/<repo>.wiki.git` 裸仓库。此后 CI 便可正常检出与推送。后续 CI 的第一次同步会自动将这个临时页面覆盖为标准主页。

---

### 7.4 日常开发维护操作心智模型 (Developer Routine)

日常研发过程中，文档与代码同步发布的极简流转如下：

```mermaid
sequenceDiagram
    autonumber
    actor Dev as 开发者
    participant Local as 本地 Antigravity / IDE
    participant Staging as 受控暂存区 (doc/wiki_staging/)
    participant Master as 主代码仓 (master 分支)
    participant CI as GitHub Actions (wiki-sync.yml)
    participant Wiki as GitHub Wiki 远端门户

    Dev->>Local: 撰写/更新 doc/reaticle_docs/ 下的技术文档
    Dev->>Local: 输入 /wiki-curator 或运行 scripts/build-wiki-staging.ps1
    Local->>Staging: 自动解析元数据、扁平化重命名、生成 _Sidebar 与 Home.md
    Dev->>Local: 执行 git status 检查明文 Diff
    Dev->>Master: git add doc/wiki_staging/ && git commit && git push
    Master->>CI: Webhook 自动触发构建
    CI->>Wiki: 检出 Wiki 仓库，镜像比对并自动 Push
    Wiki-->>Dev: 浏览器访问 Wiki 即可查阅最新发布的知识库
```

---

## 8. 全局通用 AI 编目技能 (wiki-curator) 使用指南

为了将 Wiki 编目能力固化为可复用的工程资产，本项目已在用户全局配置中装配了专用的知识库编目技能：[wiki-curator/SKILL.md](file:///c:/Users/Reaticle/.gemini/config/skills/wiki-curator/SKILL.md)。无论是维护本项目，还是迁移到任何全新的 Git 代码仓库，均可直接唤醒此技能。

### 8.1 技能定位与核心特性
1. **全局可用，跨仓零迁移成本**：安装在全局技能库（`~/.gemini/config/skills/wiki-curator/`），在任何工作区、任何代码仓库均可随时调用；
2. **四阶段自适应状态机 (Adaptive State Machine)**：技能能够自动识别当前仓库的成熟度（是全新仓库还是已配置仓库），智能流转在“源路径确认”、“CI 脚手架自动注入”、“首次凭据引导”与“常规秒级增量更新”之间；
3. **安全受控（零越权 Push）**：技能始终恪守只在本地工作区生成受控暂存区 `doc/wiki_staging/` 的安全约束，不进行静默的远程 Git 推送，所有变更对开发者完全可见、可审、可追溯。

---

### 8.2 唤醒方式与交互协议

在 Antigravity IDE 或任意支持 Agent 技能的对话界面中，可通过以下任一方式唤醒：

- **斜杠命令唤醒（推荐，最快捷）**：
  ```text
  /wiki-curator
  ```
- **自然语言直接唤醒**：
  ```text
  "帮我整理一下当前项目的 Wiki 并更新暂存区"
  "把 docs 目录下的最新文档编目并同步至 Wiki Staging"
  "初始化当前仓库的 GitHub Wiki 自动化体系"
  ```

---

### 8.3 典型场景一：全新仓库从零接入实战 (Onboarding a New Repo)

当在一个**从未配置过 Wiki 自动化体系**的全新代码仓库中唤醒 `/wiki-curator` 时，技能将依次触发以下自动化向导：

1. **Step 1: 文档源智能探测**
   - 技能自动递归检查代码根目录，若发现 `docs/` 或 `doc/` 等目录，会主动提问确认：“检测到文档源为 `docs/`，是否以此为基准进行编目？”；
   - 若项目文档存放在非标准路径（如 `src/site/markdown/`），用户只需在对话中回复路径即可。
2. **Step 2: 自动部署 CI 脚手架**
   - 技能自动检测工程必要文件，并一键无感知创建：
     - `.github/workflows/wiki-sync.yml`（GitHub Actions 自动化流水线）；
     - `.gitattributes`（注入 `doc/wiki_staging/*.md text eol=lf` 换行防护）；
     - `scripts/build-wiki-staging.ps1`（本地规则式转换与大纲提取引擎）；
     - `doc/wiki_staging/`（受控暂存发布目录）；
     - 更新 `.gitignore` 排除本地 `.wiki/` 临时克隆目录。
3. **Step 3: 首次手动操作引导**
   - 技能输出格式化的交互提示卡片，引导用户完成两大必要操作：
     - ① 在 GitHub 网页端点击 **Wiki -> Create the first page -> Save Page**（激活底层的 `.wiki.git` 存储库，防止 CI 克隆报 404）；
     - ② 在 GitHub 申请带 `repo` 权限的 Classic Token，并在仓库 Settings 配置为 Secret `WIKI_SYNC_TOKEN`（解决 403 权限问题）。

---

### 8.4 典型场景二：成熟仓库的常规增量编目 (Daily Maintenance)

对于已经完成前置配置的成熟仓库（如本项目 `wvp-GB28181-pro`），唤醒 `/wiki-curator` 时，技能自动跳过 Step 1~3，**直接秒级进入 Step 4 常规更新闭环**：

1. **AI 智能解析与重构**：
   - 扫描 `doc/reaticle_docs/` 及其所有子目录（新增的 `feats/`、`research/`、`debug/` 等）；
   - 智能提炼每篇文档的标题大纲与业务分类；
   - 自动生成符合模块分类的全局侧边栏 `_Sidebar.md` 和全景主页 `Home.md`；
   - 自动重写内部相对路径超链接与 GitHub Blob 源码链接，输出至 `doc/wiki_staging/`。
2. **生成审查摘要与提交指引**：
   - 技能输出变更清单（如：“已更新 16 篇 Wiki 页面，新增 3 个模块分类”）；
   - 给出标准 Git 提交命令提示：
     ```bash
     git add doc/wiki_staging/
     git commit -m "docs(wiki): update wiki staging for latest architecture docs"
     git push origin master
     ```
   - 提交推送到 GitHub 后，云端 Actions 在 30 秒内自动完成 Wiki 门户的线上更新。

---

### 8.5 异常排查与降级机制 (Troubleshooting & Fallback)

| 异常现象 | 根本诱因 | 解决方案 |
| :--- | :--- | :--- |
| **CI 报错：`Repository not found (404)`** | GitHub 远端尚未物理创建 `.wiki.git` 存储库。 | 打开 GitHub 仓库页面，点击 **Wiki** 标签页，点击 **Create the first page**，任意输入内容并点击 **Save Page**。 |
| **CI 报错：`The requested URL returned error: 403 Forbidden`** | 未配置 `WIKI_SYNC_TOKEN`，或 Token 缺少 `repo` 写入权限。 | 参考本方案 7.2 节，重新生成勾选了顶级 `repo` 作用域的 Classic Token，更新仓库 Actions Secret。 |
| **离线或无 AI 运行环境** | 纯脚本环境或离线断网，无法调用大模型。 | **规则引擎完全降级可用**：直接在终端执行 `pwsh scripts/build-wiki-staging.ps1`，脚本内嵌了确定性命名映射与模板生成引擎，无需 AI 也能 100% 正确输出暂存文件。 |
| **Wiki 页面间跳转出现 404** | 引用路径包含了 `.md` 后缀或使用了多级相对路径。 | 运行 `build-wiki-staging.ps1` 重新清洗，所有内部链接将自动转换为 Wiki 规范的扁平锚点（如 `[Title](Compile-and-Dev-Guide)`）。 |
