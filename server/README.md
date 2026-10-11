# 光点扫描后端与管理后台

App 的账号、用户分组与 AI 额度、AI 网关，以及网页版管理后台。Kotlin + Ktor（CIO）+ SQLite，打包成一个 jar 跑在 Docker 里。

- 管理后台：<https://134.185.119.124/admin/>（自签名证书，浏览器会提示不安全，确认是自己的服务器后继续访问）
- 公开协议页（应用商店填写用）：`/privacy`、`/terms`
- 健康检查：`/healthz`

## 功能

**App 接口（`/api/v1`）**

| 接口 | 说明 |
|---|---|
| `GET /config` | 是否开放注册、是否需要邀请码、公告、最低/最新版本号与下载地址 |
| `GET /policy/{privacy\|terms}` | 隐私政策 / 用户协议（后台可编辑） |
| `POST /auth/register`、`/auth/login`、`/auth/logout` | 账号 + 密码；返回 Bearer token（30 天，使用中自动续期） |
| `GET /me`、`POST/PATCH /me` | 资料、分组、额度与今日/本月用量；修改昵称和邮箱 |
| `POST /me/password` | 修改密码，其他设备自动退出 |
| `POST /me/delete` | 注销账号（需密码），删除账号、登录记录、用量和 AI 记录 |
| `POST /ai/chat` | AI 对话：`summary` 总结、`qa` 问答、`polish` 纠错润色、`translate` 翻译、`solve` 拍题（图片） |
| `POST /vision/ocr` | AI 文字识别：`task=text` 纯文字；`task=layout` 返回标题/段落/表格块（含坐标、单元格与合并信息），用于转 Word/Excel |
| `POST /vision/detect` | 定位手写笔迹（`handwriting`）或水印（`watermark`），返回 0–1 的框；去除在手机上按原图像素完成 |
| `POST /vision/count` | 拍照计数：返回每个物体的中心点（0–1）和识别出的物体名称；`hint` 可选，用户写明要数什么（只取前 20 个字，去掉换行和引号） |
| `POST /vision/edit` | AI 图像：`id_photo` 证件照抠图/换装、`erase` 智能消除、`restore` 老照片修复、`upscale` 变清晰；返回结果图片地址（24 小时有效） |
| `POST /feedback` | 意见反馈 |

**AI 网关**：提示词写在服务端，App 只能调用上面这些固定功能，不能当成免费的通用聊天接口。API Key 只保存在服务器上。对话类功能支持任何兼容 OpenAI Chat Completions 的服务商（阿里云百炼、火山方舟、DeepSeek、智谱等）；识别和图像功能使用阿里云百炼（DashScope），默认沿用对话的百炼 Key，也可单独填写。每次调用前先扣次数，调用失败自动退回；只记录调用元数据（功能、字数、Tokens、耗时、结果），不保存用户的文字和图片。

**识别与图像模型**（后台「系统设置 → 图像与文字识别」可改，带「测试文字识别」按钮）

| 用途 | 默认模型 | 参考价格（百炼公开价，以控制台为准） |
|---|---|---|
| 文字识别、转 Word/Excel | `qwen3.5-ocr` | 输入 0.5 元 / 输出 2 元每百万 Tokens，约 0.2 分钱一页；新用户有免费额度 |
| 定位手写、水印，拍照计数 | `qwen3-vl-plus` | 定位每次约 1 分钱以内；计数按物体多少约 0.5–4 分钱（每个点约 10 个输出 Tokens） |
| 证件照（抠图、换装） | `qwen-image-3.0-pro` | 实际账单约 0.19 元 / 张（App 把图片控制在 1K 档） |
| 智能消除、老照片修复 | `qwen-image-3.0` | 约 0.2 元 / 张 |
| 图片变清晰 | `wanx2.1-imageedit`（超分辨率） | 0.14 元 / 张，新用户有 500 张免费额度 |
| 拍题解答（视觉对话） | `qwen3-vl-plus` | 按 Tokens 计费 |

**费用控制**：图像类每次调用约 0.14–0.2 元，是主要成本（文字识别每页约 0.002 元，对话按 Tokens 计费，都很便宜）。所以证件照、老照片修复、图片变清晰默认先显示免费的本地结果，用户点「AI」才调用模型（「系统设置」可改为打开即用 AI）；免费用户默认每天 2 次 AI 图像。概览页按调用记录 × 「系统设置 → 模型单价」估算今日 / 本月费用和各模型明细，单价可自行修改，最终以百炼账单为准。

**模型下线与限流**：百炼会不定期下线旧模型（2026-10-10 下线了 qwen-image-edit 系列）。识别和图像接口遇到"模型不存在"时会依次尝试备用模型（识别 qwen3.5-ocr → qwen-vl-ocr-latest，定位 qwen3-vl-plus → qwen3-vl-flash，图像 qwen-image-3.0 ↔ qwen-image-3.0-pro），失效的模型一小时内不再重试，调用记录里记的是实际使用的模型。后台概览会列出已下线的模型（每 30 分钟核对一次百炼的模型列表，不产生费用），「系统设置」里的「检查模型是否可用」可以立即核对。上游返回 429 限流时自动等待重试两次。

**分组与额度**（默认三个分组，可在后台增删改）

| 分组 | 每日 AI 对话 | 每月 Tokens | 每日 AI 图像 | 每日识别页数 | 单次字数 | 同时登录设备 |
|---|---|---|---|---|---|---|
| 免费用户（默认） | 20 | 20 万 | 2 | 50 | 6,000 | 2 |
| 会员 | 300 | 500 万 | 100 | 1,000 | 30,000 | 5 |
| 内部测试 | 不限 | 不限 | 不限 | 不限 | 100,000 | 10 |

- 三种额度分开计数：AI 对话（文档助手、拍题、翻译）、AI 图像（证件照、去手写、去水印、消除、老照片、变清晰，每次处理算 1 次）、识别页数（每识别一页算 1 页，AI 拍照计数每次算 1 页）。
- 每个分组可以单独开通或关闭某个 AI 功能。
- 单个用户可以设置自定义的每日对话次数和每月 Tokens（覆盖分组设置）。
- 每个用户同时最多 2 个 AI 请求在处理，避免一个人占满并发。
- 分组可以设有效期（如会员一个月），到期后自动回到默认分组，并清除自定义额度。
- 超出设备数时，最早登录的设备会自动退出。
- 次数按北京时间每天 0 点重置，Tokens 每月 1 日重置。

**管理后台**

- 概览：用户数、今日/近 7 日活跃、今日 AI 调用与 Tokens、今日 AI 图像与识别页数、失败与超额拦截次数、14 天趋势、分组分布、今日用量排行
- 用户管理：搜索、按分组和状态筛选、排序；查看详情（额度、登录设备、近 30 天用量、最近 AI 调用）；编辑资料、分组与有效期、自定义额度、备注；封禁/解封（附原因）、重置密码、强制下线（单台或全部）、删除；批量调整分组、封禁、解封、删除；手动创建用户
- 分组与配额、AI 调用记录、意见反馈（待处理/已处理、备注）
- 系统设置：AI 服务商（预设百炼/方舟/DeepSeek/智谱，可一键测试连接）、图像与文字识别模型（可一键测试识别）、开放注册与邀请码、App 公告与强制/可选更新、隐私政策与用户协议
- 管理员（超级管理员 / 运营两种角色）、操作日志（所有后台改动都有记录）

**安全**

- 密码 PBKDF2-SHA256（21 万次迭代）；token 只存 SHA-256 摘要
- 登录、注册、后台登录按 IP 和账号限流，连续输错 5 次锁定 15 分钟
- 后台用 HttpOnly + SameSite=Strict Cookie，写操作还需要自定义请求头（防 CSRF），页面有严格的 CSP
- 初始管理员首次登录必须修改密码，修改前不能进行其他操作
- 容器以非 root 用户运行，端口只绑定 127.0.0.1（Docker 端口映射会绕过 ufw）

**运维**

- 每天自动把数据库快照到 `data/backups/`，保留 14 天
- 每小时清理过期会话；AI 记录保留 180 天，操作日志保留 1 年

## 部署结构（VPS 134.185.119.124）

```
/opt/lumiscan/
  lumiscan-server.jar  Dockerfile  docker-compose.yml
  tls/server.crt  tls/server.key       自签名证书（IP 证书）
  data/lumiscan.db                     SQLite 数据库
  data/backups/                        每日备份
  data/initial-admin.txt               初始管理员密码（管理员改密后自动删除）
/www/server/panel/vhost/nginx/lumiscan.conf   宝塔 nginx 反向代理（443 上直接访问 IP 的默认站点）
```

- 容器 `lumiscan` 监听 `127.0.0.1:8090`，内存上限 384 MB（实际约 90 MB）。
- 宝塔面板里看不到这个站点（不是宝塔创建的），不影响 `vps.555544.xyz` 等已有站点（按 SNI 匹配）。

常用命令（在 VPS 上执行）：

```bash
docker logs -f --tail 100 lumiscan
```

```bash
cd /opt/lumiscan && docker compose restart
```

## 更新部署

在本机项目根目录执行（需要 JDK 17 和到 VPS 的 SSH 密钥登录）：

```bash
server/deploy/deploy.sh
```

脚本会编译 jar、上传、重建容器、测试 nginx 配置后再重载（测试失败会撤掉自己的配置，不影响其他站点）。数据库保留不动。

## 换成域名（推荐）

现在用的是 IP + 自签名证书，App 里内置了这张证书（`app/src/main/res/raw/server_cert.pem`）。浏览器访问后台会有安全提示，部分网络环境下也可能被拦截。正式上线建议：

1. 在 Cloudflare 给 `555544.xyz` 添加一条 A 记录，比如 `scan` → `134.185.119.124`（仅 DNS，不开代理）。
2. 在宝塔里为 `scan.555544.xyz` 建站并申请 Let's Encrypt 证书，反向代理到 `http://127.0.0.1:8090`（参考 `deploy/nginx-lumiscan.conf` 里的请求头和超时设置）。
3. 把根目录 `gradle.properties` 里的 `lumiscan.apiBase` 改成 `https://scan.555544.xyz`，重新打包 App。App 同时信任系统证书，不需要再改代码。

## 本地开发

```bash
./gradlew -p server fatJar
```

```bash
LUMI_PORT=18080 LUMI_DATA=./server/data LUMI_COOKIE_SECURE=false java -jar server/build/libs/lumiscan-server.jar
```

环境变量：`LUMI_HOST`（默认 0.0.0.0）、`LUMI_PORT`（8080）、`LUMI_DATA`（./data）、`LUMI_TZ`（Asia/Shanghai）、`LUMI_COOKIE_SECURE`（true）、`LUMI_ADMIN_USER`（admin）、`LUMI_ADMIN_PASSWORD`（首次启动时的初始密码，不填则随机生成）。

重新生成 IP 证书（会同时更新 App 内置的证书，之后需要重新打包 App 并部署）：

```bash
server/deploy/gen-cert.sh 134.185.119.124
```

端到端接口测试（启动一个模拟大模型（含百炼识别、图像接口）和全新的本地服务，跑完注册、登录、额度、封禁、AI 对话、识别与图像、后台管理等 89 项检查，不消耗真实额度）：

```bash
server/test-e2e/run.sh
```
