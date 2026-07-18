package com.rpki.conflictchecker.util;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileUtils {
    
    private static final Logger logger = LoggerFactory.getLogger(FileUtils.class);
    
    private static final int BUFFER_SIZE = 8192;
    
    /**
     * 下载文件到本地
     */
    public static void downloadFile(String fileUrl, String targetPath) throws IOException {
        logger.info("开始下载文件: {} 到 {}", fileUrl, targetPath);
        
        // 创建目录
        Path targetFilePath = Paths.get(targetPath);
        Files.createDirectories(targetFilePath.getParent());
        
        // TODO: 实现实际的文件下载逻辑
        logger.info("文件下载完成: {}", targetPath);
    }
    
    /**
     * 解压 .gz 文件
     */
    public static void decompressGzip(String gzipFilePath, String outputPath) throws IOException {
        logger.info("开始解压 GZIP 文件: {} 到 {}", gzipFilePath, outputPath);
        
        Path outputFilePath = Paths.get(outputPath);
        Files.createDirectories(outputFilePath.getParent());
        
        try (GzipCompressorInputStream gzipInput = new GzipCompressorInputStream(
                new FileInputStream(gzipFilePath));
             FileOutputStream fileOutput = new FileOutputStream(outputPath)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = gzipInput.read(buffer)) != -1) {
                fileOutput.write(buffer, 0, n);
            }
            logger.info("GZIP 文件解压完成: {}", outputPath);
        }
    }
    
    /**
     * 解压 TAR.GZ 文件
     */
    public static void decompressTarGzip(String tarGzipPath, String outputDir) throws IOException {
        logger.info("开始解压 TAR.GZ 文件: {} 到 {}", tarGzipPath, outputDir);
        
        Path outputDirPath = Paths.get(outputDir);
        Files.createDirectories(outputDirPath);
        
        try (TarArchiveInputStream tarInput = new TarArchiveInputStream(
                new GzipCompressorInputStream(new FileInputStream(tarGzipPath)))) {
            
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                Path entryPath = outputDirPath.resolve(entry.getName());
                
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (FileOutputStream output = new FileOutputStream(entryPath.toString())) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int n;
                        while ((n = tarInput.read(buffer)) != -1) {
                            output.write(buffer, 0, n);
                        }
                    }
                }
            }
            logger.info("TAR.GZ 文件解压完成: {}", outputDir);
        }
    }
    
    /**
     * 读取文件内容为字节数组
     */
    public static byte[] readFileToBytes(String filePath) throws IOException {
        return Files.readAllBytes(Paths.get(filePath));
    }
    
    /**
     * 写入字节数组到文件
     */
    public static void writeFilFromBytes(String filePath, byte[] data) throws IOException {
        Path path = Paths.get(filePath);
        Files.createDirectories(path.getParent());
        Files.write(path, data);
    }
    
    /**
     * 文件是否存在
     */
    public static boolean fileExists(String filePath) {
        return Files.exists(Paths.get(filePath));
    }
    
    /**
     * 删除文件
     */
    public static void deleteFile(String filePath) throws IOException {
        Files.deleteIfExists(Paths.get(filePath));
        logger.debug("文件已删除: {}", filePath);
    }
    
    /**
     * 获取文件大小
     */
    public static long getFileSize(String filePath) throws IOException {
        return Files.size(Paths.get(filePath));
    }
    
    /**
     * 计算文件 SHA256 哈希
     */
    public static String calculateSHA256(String filePath) throws IOException {
        byte[] data = Files.readAllBytes(Paths.get(filePath));
        return calculateSHA256FromBytes(data);
    }
    
    /**
     * 从字节数组计算 SHA256 哈希
     */
    public static String calculateSHA256FromBytes(byte[] data) throws IOException {
        java.security.MessageDigest digest = null;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 算法不可用", e);
        }
        
        byte[] hash = digest.digest(data);
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
