<p align="center">
  <h1 align="center">🎬 PlayHUB</h1>
  <p align="center">
    <strong>Box 配置的 Web 化播放器</strong> —— 基于 <a href="https://github.com/takagen99/TVBoxOSC">takagen99-TVBoxOSC</a> 的复刻
  </p>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17+-orange?logo=java" alt="Java 17+">
  <img src="https://img.shields.io/badge/Spring_Boot-3.x-green?logo=springboot" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Vue-3.x-4FC08D?logo=vue.js" alt="Vue 3">
  <img src="https://img.shields.io/badge/Node-20+-339933?logo=node.js" alt="Node.js 20+">
  <img src="https://img.shields.io/badge/Python-3.9+-blue?logo=python" alt="Python 3.9+">
</p>

<p align="center">
  <a href="#-特性">特性</a> •
  <a href="#-快速开始">快速开始</a> •
  <a href="#-使用指南">使用指南</a> •
  <a href="#-技术映射">技术映射</a> •
  <a href="#-注意事项">注意事项</a> •
  <a href="#-致谢">致谢</a>
</p>

---

## ✨ 特性

- **🌐 前后端分离**  
  Java (Spring Boot) 后端负责配置拉取、站点接口聚合、JAR 蜘蛛下载与反射调用；Vue3 前端提供流畅的交互体验。

- **📺 完整的影视浏览功能**  
  支持数据源切换、首页分类展示、关键词搜索、详情弹层、剧集选择与在线播放。

- **📦 JAR Spider 兼容**  
  通过 `URLClassLoader` 在服务端动态加载并执行 TVBox 的 JAR 蜘蛛，无需 Android 环境即可使用大部分蜘蛛。

- **💾 本地持久化**  
  收藏与观看历史自动保存到浏览器 `localStorage`，无需登录即可下次继续追剧。

- **⚡ 一键部署**  
  提供预置的运行脚本，Java & Node 环境配置完成即可快速启动。

---

## 🚀 快速开始

### 环境要求

| 工具 / 运行时 | 最低版本 | 说明 |
|---------------|----------|------|
| **JDK**       | 17+      | 运行 Spring Boot 后端 |
| **Node.js**   | 20+      | 构建 & 运行 Vue3 前端 |
| **Python**    | 3.9+     | 部分 Spider Bridge 依赖 |
| **Maven**     | 3.9+     | 调试时使用（可选） |

### 本地运行

#### 1. 克隆仓库

```bash
git clone https://github.com/your-username/PlayHUB.git
cd PlayHUB
```

#### 2. 启动后端

**方式一：一键脚本（推荐）**
```bash
# 运行 bin 目录下的对应系统脚本
./bin/start.sh      # Linux / macOS
# 或
bin/start.bat       # Windows
```

**方式二：Maven 调试模式**
```bash
mvn spring-boot:run
```

#### 3. 访问应用

启动成功后，访问以下地址：

| 服务 | 地址 |
|------|------|
| 🖥️ 前端页面 | [http://localhost:18080/](http://localhost:18080/) |
| 💚 健康检查 | [http://localhost:18080/api/health](http://localhost:18080/api/health) |

---

## 📘 使用指南

1. **🔗 加载配置**  
   打开页面，在输入框中粘贴配置链接（如 `https://example.com/tvbox.json`），点击 **“加载配置”**。

2. **📡 选择站点**  
   从加载成功的站点列表中，点选你需要使用的数据源。

3. **🏠 浏览首页**  
   点击 **“加载首页”**，查看分类推荐内容，或使用顶部搜索栏查找影片。

4. **🎥 在线播放**  
   进入影片详情页，选择合适的播放源与剧集，开始播放。

5. **❤️ 收藏 & 历史**  
   喜欢的影片可一键收藏，观看记录自动生成。数据默认保存在浏览器 `localStorage`，清除缓存会丢失，请注意备份。

6. **🎥 直播 & EPG**  
   想要的直播一键直达，同类合并+自动换源，EPG支持随心所欲的观看想要的节目。

---

## ⚠️ 注意事项

- 当前后端 **优先支持 `type=3`（JAR Spider）** 以及提供标准 JSON 接口的站点。  
- 部分 Spider 强依赖 Android Context（如 `Toast`、`SharedPreferences`），在纯 JVM 环境下可能无法正常运行。  
- 后缀为 `.js` 的 JS 蜘蛛，以及 `dex-only` 的 JAR 蜘蛛，会自动降级到 **Spider Bridge** 执行。  
- 浏览器 `localStorage` 容量有限（通常 5–10 MB），大量收藏或历史记录时建议及时清理。

---

## 🙏 致谢

- [takagen99/TVBoxOSC](https://github.com/takagen99/TVBoxOSC) —— 本项目灵感与核心逻辑来源  

> 如果 PlayHUB 对你有帮助，欢迎给个 ⭐ Star 鼓励一下！

---

<p align="center">
  <sub>Made with ❤️ by the community</sub>
</p>