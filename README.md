# Billiards-study · 中八台球走位学习

一款俯视 2D 台面的中八台球"击球分析器"（Android，Kotlin + Jetpack Compose）。
摆好白球和目标球，App 实时算出瞄准线、假想球、切角/厚薄、定杆分离方向，
并用物理模拟画出不同击点（高低杆、左右塞）和力度下白球的完整走位路线。

A top-down 2D cue-sports shot analyzer for Chinese eight-ball, built to help players
*see* cue-ball position play: aim line, ghost ball, cut angle, stun line, and simulated
cue-ball paths for any tip position and power.

## 功能 Features

- 拖动任意球摆球形；点彩球换目标球，点袋口换袋
- 瞄准分析：切角、厚薄、袋口容错、是否被挡
- 球体原型击点选择（高低杆 + 左右塞，含极限击点）与竖直无极力度条
- 事件驱动物理模拟：滑动/滚动摩擦、侧旋、吃库，画出白球与被碰球的路线和停点
- 台面线条带文字标签，点任意线条查看详细说明
- 走位热区：标出白球停在哪里下一颗最好打，并扫描杆法 × 力度组合

## 项目结构 Structure

- `engine/` — 纯 Kotlin 物理与分析引擎（`Simulator`、`AimAnalyzer`、`PositionPlanner`），带单元测试
- `app/` — Android UI（Compose）
- `tools/validate/` — 用 [pooltool](https://github.com/ekiefl/pooltool) 生成基准数据校验引擎的 Python 脚本
- `实施方案.md` — 调研与实施方案

## 构建 Build

需要 Android SDK（compileSdk 37）和 JDK 17 以上。

```bash
./gradlew :engine:test
./gradlew :app:assembleDebug
```

## License

[MIT](LICENSE)
