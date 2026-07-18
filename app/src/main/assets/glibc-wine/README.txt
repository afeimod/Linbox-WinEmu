# glibc-wine 镜像资源目录
# 只需将以下文件放入此目录:
# - imagefs.tzst: glibc wine 镜像 (zstd 压缩的 tar, 包含完整的 wine/box64/glibc 库等)
#
# imagefs.tzst 解压后安装到: /data/data/<pkg>/files/imagefs/
# 包含完整的 glibc wine 运行时, 无需其他资源文件。
#
# 获取 imagefs.tzst:
# 从 winlator-glibc APK 的 assets/imagefs.tzst 提取, 放入此目录即可。
