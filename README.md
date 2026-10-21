# 错题本首版开发说明

项目按需求文档中的 P0 学习闭环实现：收录错题、补充答案与错因、安排复习、练习并记录结果、查看学习进度。结构采用 DDD 风格模块化单体，后端使用 JDK 17、Spring Boot 和本机 MySQL；前端使用微信原生小程序。

## 目录

- 错题本-后端：Spring Boot API、模块代码、MySQL 初始化脚本
- 错题本-微信小程序前端：微信开发者工具工程
- 错题本-后端/database/schema.sql：建库、建表和默认学科数据

后端按身份档案、分类、错题、复习、练习、学习看板、附件、图片识别和反馈划分业务模块。每个模块通过接口层接收请求、应用层编排用例；错题与复习规则放在领域层，数据库和本地附件访问放在基础设施层。

## 1. 初始化 MySQL

首次建库使用 MySQL 8.0.13 或更高版本，运行 `database/schema.sql`。脚本会创建 wrongbook 数据库、业务表和默认学科。

已经运行过旧版 schema 的数据库，运行 `database/upgrade-wechat-avatar.sql` 增加头像字段。该脚本可以重复执行，已存在的头像字段会自动跳过。数据库脚本由项目成员手动运行，后端不会自动创建或改动数据库结构。

## 2. 在 IDEA 启动后端

1. 在 IDEA 中打开 错题本-后端 文件夹，作为 Maven 项目导入。
2. 打开 src/main/resources/application.yml，将用户名和密码改成电脑本机 MySQL 的配置；也可以在 IDEA 的运行配置中设置环境变量 DB_USERNAME 和 DB_PASSWORD。
3. 运行 com.cuotiben.WrongbookApplication。
4. 后端监听 0.0.0.0:8080，浏览器打开 http://127.0.0.1:8080/api/v1/health 应看到 {"status":"UP","service":"wrongbook-api"}。

本地默认启用 `local` 配置。图片识题使用智谱 GLM-4.6V-Flash 多模态模型；API Key 填写到 `src/main/resources/application-local.yml` 的 `app.glm-vision.api-key`。该文件已加入 `.gitignore`，不会提交到 Git。若本机没有该文件，可复制 `application-local.example.yml` 并命名为 `application-local.yml`。密钥不需要配置到小程序。

在 IDEA 的“运行/调试配置 → 环境变量”中设置：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| DB_URL | jdbc:mysql://127.0.0.1:3306/wrongbook?... | 本机 MySQL 连接地址 |
| DB_USERNAME | epiphany | MySQL 用户名 |
| DB_PASSWORD | 123456 | MySQL 密码 |
| DEV_AUTH_ENABLED | true | 开启本地开发登录 |
| APP_STORAGE_LOCATION | 项目约定的本机 uploads 目录 | 错题照片和头像保存目录 |

当前使用本地开发登录，不需要配置微信 AppSecret。

## 3. 配置手机热点联调

电脑和测试手机连接同一个手机热点后：

1. 在电脑终端运行 ipconfig getifaddr en0，查看电脑在热点下的 IPv4 地址。若命令没有输出，可用 networksetup -listallhardwareports 找到 Wi-Fi 对应接口，再运行 ipconfig getifaddr <接口名>。
2. 将该地址填入 错题本-微信小程序前端/utils/config.js，例如 http://172.20.10.2:8080/api/v1。不要写 localhost 或 127.0.0.1。
3. `project.config.json` 使用项目当前的小程序 AppID。在 `utils/config.js` 中填入电脑当前热点 IP。
4. 在开发者工具“详情 → 本地设置”打开“不校验合法域名、web-view、TLS 版本以及 HTTPS 证书”，编译后真机预览。
5. 确认 IDEA 后端仍在运行。如果 macOS 防火墙询问是否允许 Java 接受传入连接，请允许本地网络访问。

扫码后小程序会自动使用本地开发身份进入，不需要微信授权弹窗或 AppSecret。头像通过“我的 → 更换头像”由用户主动选择，昵称可在档案页编辑。

如果手机无法访问电脑地址，先确认两台设备仍在同一热点下，并在电脑终端用 curl http://127.0.0.1:8080/api/v1/health 检查后端，再检查 macOS 防火墙的 8080 端口访问。

## 4. 首版功能

- 本地开发登录、学习档案、头像上传和测试账号切换
- 错题手动录入、拍照/相册上传、编辑、搜索、分类、个人标签、归档和回收站接口
- 用户可新增个人章节和知识点；错题可按学科、章节、错因、日期、状态和到期情况筛选
- 复习结果、信心/耗时字段、版本化的 1/3/7/14 天复习间隔
- 按学科与到期状态生成练习；提交前只返回题干，提交后显示答案和订正
- 待复习、逾期、近 7 天复习正确率、重复错误和学科趋势
- 用户数据隔离、个人数据 JSON 导出、账号删除、问题反馈
- 题目草稿保存在小程序本机；创建与复习请求使用幂等键，编辑使用版本号避免静默覆盖
- 图片私有存放在运行后端的电脑本地，通过登录态接口读取
- 拍题后调用智谱 GLM-4.6V-Flash 多模态模型理解图片并整理题面，结果填入可编辑草稿；密钥只保存在后端本地配置

## 5. 本地联调 API

API 前缀为 /api/v1。主要路由：

- POST /auth/dev-login：本地开发身份
- GET|PUT|DELETE /me、GET /me/export：档案、删除账号和导出
- GET /taxonomy/subjects|chapters|knowledge-points、POST /taxonomy/chapters|knowledge-points：分类数据
- GET|POST /questions、GET|PUT|DELETE /questions/{id}：错题管理
- POST /questions/{id}/reviews：记录一次复习
- POST /practice-sessions、POST /practice-sessions/{id}/answers：练习
- GET /dashboard：学习进度
- POST /questions/{id}/attachments、GET /attachments/{id}：图片
- POST /recognition/question-image：图片识题，`multipart/form-data` 字段名为 `file`；返回识别状态、题面文本和提示
- POST /feedback：意见反馈

图片识题测试版使用智谱 `glm-4.6v-flash`：从相机拍摄或相册选择 JPG/PNG 图片后，小程序上传至后端；单张图片上限 10MB。后端将图片和指令一起发送给多模态模型，要求按题面结构整理图片中实际可见的内容，不解题、不猜答案、不补写缺失内容；学生仍需校对后保存。无清晰题目时提示重拍/重选；服务错误会显示明确原因，图片和本机草稿保留。当前 DEV_AUTH_ENABLED=true 只用于本地联调，不要把开发后端暴露到公网。
