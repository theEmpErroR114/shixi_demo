# Java 环境诊断工具

## 项目说明

`test_windows` 和 `test_mac` 是两个独立的 Maven 项目，用于一键检测电脑上的 Java 开发环境是否正常。每个文件夹内都有一个基于 Spring Boot 的诊断程序，启动后会自动检查 JDK 版本、Maven 安装、Spring Boot 运行时、嵌入式 Tomcat、HTTP Web 端点以及 MySQL 数据库连接共 6 项核心组件，并在控制台输出检测报告，同时提供 `GET /diagnostic` Web 接口返回 JSON 格式的检测结果。两个版本仅 Maven 检测命令有差异（Windows 使用 `cmd /c`，Mac 直接调用 `mvn`），其余检测逻辑完全一致。

## 使用方式
**打开终端**
Windows
```bash
cd test_windows
mvnw.cmd spring-boot:run
```
Mac
```bash
cd test_mac
./mvnw spring-boot:run
```

浏览器访问 `http://localhost:8080/diagnostic` 查看 JSON 结果。


