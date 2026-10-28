# 身体记录模块来源

从用户自有 TinyYumi 仓库迁入，原修订 `3304fad34e3e9ff81834f937717a5d18068affc2`，原路径 `app/src/main/java/io/tinyyumi/scale`。核心协议、估算公式、SQLite 和趋势图保留；包路径改为 `io.stridehub.app.scale`，入口改为内部 WeighingActivity。原仓库未删除。

统一安装包沿用 `io.stridehub.app`，不发布第二个采集 App。旧 `io.tinyyumi.scale` 数据仍在旧应用沙箱，不能自动跨包访问；历史迁移完成前请保留旧应用。当前此模块为本机称重，NAS 同步尚待接入。
