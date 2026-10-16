# 错题本首版开发说明

项目按需求文档中的 P0 学习闭环实现：收录错题、补充答案与错因、安排复习、练习并记录结果、查看学习进度。结构采用 DDD 风格模块化单体，后端使用 JDK 17、Spring Boot 和本机 MySQL；前端使用微信原生小程序。

## 目录

- 错题本-后端：Spring Boot API、模块代码、MySQL 初始化脚本
- 错题本-微信小程序前端：微信开发者工具工程
- 错题本-后端/database/schema.sql：建库、建表和默认学科数据

后端按身份档案、分类、错题、复习、练习、学习看板、附件和反馈划分业务模块。每个模块通过接口层接收请求、应用层编排用例；错题与复习规则放在领域层，数据库和本地附件访问放在基础设施层。

## 1. 初始化 MySQL

使用 MySQL 8.0.13 或更高版本，运行 错题本-后端/database/schema.sql。脚本会创建 wrongbook 数据库、业务表和语文、数学、英语等默认学科。

数据库脚本由项目成员手动运行。后端不会自动创建或改动数据库结构。

## 2. 在 IDEA 启动后端

1. 在 IDEA 中打开 错题本-后端 文件夹，作为 Maven 项目导入。
2. 打开 src/main/resources/application.yml，将用户名和密码改成电脑本机 MySQL 的配置；也可以在 IDEA 的运行配置中设置环境变量 DB_USERNAME 和 DB_PASSWORD。
3. 运行 com.xuotiben.WrongbookApplication。
4. 后端监听 0.0.0.0:8080，浏览器打开 http://127.0.0.1:8080/api/v1/health 应看到 {"status":"UP","service":"wrongbook-api"}。

IDEA 运行配置可使用以下环境变量：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| DB_URL | jdbc:mysql://127.0.0.1:3306/wrongbook?... | 本机 MySQL 连接地址 |
| DB_USERNAME | root | MySQL 用户名 |
| DB_PASSWORD | root | MySQL 密码 |
| DEV_AUTH_ENABLED | true | 开启本地开发登录 |
| APP_STORAGE_LOCATION | ./data/uploads | 错题照片保存目录 |

## 3. 配置手机热点联调

电脑和测试手机连接同一个手机热点后：

1. 在电脑终端运行 ipconfig getifaddr en0，查看电脑在热点下的 IPv4 地址。若命令没有输出，可用 networksetup -listallhardwareports 找到 Wi-Fi 对应接口，再运行 ipconfig getifaddr <接口名>。
2. 将该地址填入 错题本-微信小程序前端/utils/config.js，例如 http://172.20.10.2:8080/api/v1。不要写 localhost 或 127.0.0.1。
3. 在微信开发者工具中导入 错题本-微信小程序前端。project.config.json 使用 touristappid 便于本地编译；需要真机预览时，替换成团队的小程序 AppID。
4. 在开发者工具“详情 → 本地设置”打开“不校验合法域名、web-view、TLS 版本以及 HTTPS 证书”，编译后真机预览。
5. 确认 IDEA 后端仍在运行。如果 macOS 防火墙询问是否允许 Java 接受传入连接，请允许本地网络访问。

如果手机无法访问电脑地址，先确认两台设备仍在同一热点下，并在电脑终端用 curl http://127.0.0.1:8080/api/v1/health 检查后端，再检查 macOS 防火墙的 8080 端口访问。

## 4. 首版功能

- 本地开发登录、学习档案和测试账号切换
- 错题手动录入、拍照/相册上传、编辑、搜索、分类、个人标签、归档和回收站接口
- 用户可新增个人章节和知识点；错题可按学科、章节、错因、日期、状态和到期情况筛选
- 复习结果、信心/耗时字段、版本化的 1/3/7/14 天复习间隔
- 按学科与到期状态生成练习；提交前只返回题干，提交后显示答案和订正
- 待复习、逾期、近 7 天复习正确率、重复错误和学科趋势
- 用户数据隔离、个人数据 JSON 导出、账号删除、问题反馈
- 题目草稿保存在小程序本机；创建与复习请求使用幂等键，编辑使用版本号避免静默覆盖
- 图片私有存放在运行后端的电脑本地，通过登录态接口读取

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
- POST /feedback：意见反馈

正式微信登录、OCR、订阅提醒、家庭和班级关系、PDF 导出属于后续阶段。当前 DEV_AUTH_ENABLED=true 只用于本地联调，不要把开发后端暴露到公网。
