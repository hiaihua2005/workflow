@echo off
cd /d d:\aicode\workflow_zai\workflow\workflow_lowcode\backend
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.datasource.url=jdbc:mysql://localhost:3306/workflow?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true -Dspring.datasource.username=root -Dspring.datasource.password=740130 -Dspring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver" > D:\aicode\workflow_zai\workflow\workflow_lowcode\e2e\backend.log 2>&1
