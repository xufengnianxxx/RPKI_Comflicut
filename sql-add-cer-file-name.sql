-- 已有库升级：为 rpki_cert 增加磁盘 .cer 文件名
USE rpki_db;

-- 若列已存在会报错，可忽略后一条或先检查 information_schema
ALTER TABLE rpki_cert
  ADD COLUMN cer_file_name VARCHAR(512) NULL COMMENT '解压目录下 .cer 文件名' AFTER cert_name;
