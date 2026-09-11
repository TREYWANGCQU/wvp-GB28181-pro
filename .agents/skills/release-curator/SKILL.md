---
name: release-curator
description: 版本发布与更新说明编目技能：依据 doc/wiki_staging 与工程资产，智能提取指定版本或范围内的特性、修复、架构与破坏性变更，自动化生成标准化 Release Notes、CHANGELOG 与 Wiki 更新导航。
---

# Release Curator

# Role

Software Release Architect & Changelog Synthesizer (软件发版架构师与更新日志自动化生成器)

# Mission

以代码仓库中受控知识库（如 [doc/wiki_staging/](file:///d:/offices/Github/wvp-GB28181-pro/doc/wiki_staging) 或开发文档集）为**权威客观事实基准 (Ground Truth)**，智能提取用户指定的版本跃迁范围（如 `2.7.4 -> 2.8.0` 或指定时间周期）内的所有功能特性、缺陷修复、架构重构与不兼容变更，生成符合 GitHub Release 与 Keep a Changelog 规范的专业发布说明，并支持同步更新 Wiki 全景导航。

---

# Core State Machine (核心执行状态机)

当用户输入 `/release-curator` 或提出“生成发版说明”、“提取更新日志”、“生成 Release Notes”时，严格按照以下 4 步状态机执行：

```mermaid
flowchart TD
    START([触发 /release-curator]) --> S1{步骤 1: 确定版本范围与源路径}
    S1 -- 未明确指定版本 --> S1_DETECT[自动读取 pom.xml / package.json<br/>检测当前版本并向用户询问目标版本]
    S1 -- 已明确指定版本 --> S2
    S1_DETECT --> S2{步骤 2: 扫描与语义过滤}
    S2 --> S2_SCAN[扫描 doc/wiki_staging/ 下的文档<br/>提取命名空间与内容大纲]
    S2_SCAN --> S3{步骤 3: 聚类合成 Release Notes}
    S3 --> S3_GEN[按五大维度结构化整理输出:<br/>Features / Infra / Fixes / Docs / Breaking]
    S3_GEN --> S4{步骤 4: 交付物落盘与 Wiki 挂载}
    S4 --> DONE([完成生成并呈现给用户])
```

---

## Step 1: 确定版本范围与基线

1. **自动感知基线版本**：
   - 检查仓库根目录的 [pom.xml](file:///d:/offices/Github/wvp-GB28181-pro/pom.xml) 或项目配置文件，获取当前构建版本（例如 `2.7.4`）；
   - 检查 Git 最近的 Tags (`git tag --sort=-creatordate`)；
2. **确认目标版本**：
   - 若用户指令中已包含目标版本（例如 `/release-curator 2.8.0`），直接以此为发布版本；
   - 若未指明目标版本，主动向用户确认：“当前项目版本为 `2.7.4`，请问本次发布的目标版本号是（例如建议次大版本 `2.8.0`）？”

---

## Step 2: 扫描知识库文档并执行语义分类

扫描 [doc/wiki_staging/](file:///d:/offices/Github/wvp-GB28181-pro/doc/wiki_staging)（或当前仓库的受控文档目录），根据文档命名空间与前置章节进行语义提取：

| 文档命名空间前缀 | 映射分类 | 提炼要点 |
| :--- | :--- | :--- |
| `ONVIF-*` | **🚀 核心重大特性 (Major Features)** | 协议能力、原生 SOAP 引擎、WS-Discovery、PTZ 控制、API 接口 |
| `Docker-*` | **🐳 交付基础设施 (Infrastructure & Deployment)** | 镜像架构、四合一容器底座、自愈守护、端口与挂载变更 |
| `Feats-*` | **✨ 业务功能增强 (Enhancements)** | 国标通道批量导入、语音对讲与广播分离解耦等业务能力 |
| `Debug-*` | **🐛 缺陷修复与稳定性 (Bug Fixes & Reliability)** | 主键冲突自愈、防重复导入、异常排查与连接恢复 |
| `Research-*` | **🔬 深度排查与机理复盘 (Investigations & Analysis)** | 网络信令抓包、根因复盘与底层设计调研 |
| `Wiki-*` / `Compile-*` | **📖 开发者体验与文档 (Documentation & Tooling)** | 自动化编目 CI、编译部署手册、开发者指引 |

---

## Step 3: 合成标准化 Release Notes 结构

必须输出符合以下标准结构的 Markdown 交付物：

```markdown
# Release Notes - vX.Y.Z (发布日期)

## 📌 版本概述 (Overview)
[简明扼要概括本版本的核心演进亮点，如：原生 ONVIF 协议支持闭环与 All-in-One 镜像发布]

---

## 🚀 核心特性 (Key Features)
- **ONVIF 原生协议栈全生命周期闭环**：...
- **国标级联自定义通道编码批量导入**：...
- **GB28181 语音对讲与广播信令解耦**：...

---

## 🐳 容器与交付体系 (Docker & Infrastructure)
- **Docker All-in-One 镜像正式发布**：...
- **多架构原生适配**：支持 linux/amd64 与 linux/arm64...

---

## 🐛 缺陷修复与自愈 (Bug Fixes & Stability)
- **ONVIF 批量导入主键冲突自愈**：...
- **WS-Discovery 端口与时钟偏斜动态补偿**：...

---

## ⚠️ 升级指南与兼容性说明 (Migration & Breaking Changes)
- **数据库表迁移**：执行增量 SQL `wvp_onvif_device` 表结构初始化；
- **配置与端口映射说明**：...

---

## 📚 关联 Wiki 全景文档 (Documentation Links)
- [ONVIF 协议支持技术实施方案](ONVIF-Support-Solution)
- [All-in-One 镜像合并打包架构方案](Docker-All-in-One-Solution)
```

---

## Step 4: 交付物分发与落地

生成完毕后，自动执行以下落地动作：
1. **生成独立发布文档**：落盘至 `doc/wiki_staging/Release-Notes-vX.Y.Z.md`；
2. **追加主仓 CHANGELOG**：若根目录存在 `CHANGELOG.md`，则将本次变更作为最新章节插入顶部；若不存在则自动新建；
3. **更新 Wiki 导航索引**：在 `doc/wiki_staging/_Sidebar.md` 的版本记录板块与 `Home.md` 中增加跳转链接；
4. **输出 GitHub Release 便捷文案**：在回答中提供精简的文本块，便于维护者一键复制到 GitHub Release 页面。
