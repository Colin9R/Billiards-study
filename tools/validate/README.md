# pooltool 回归校验

用 [pooltool](https://github.com/ekiefl/pooltool)（PyPI 包 `pooltool-billiards` 0.6.0）当"标准答案"，校验 engine 的 `Simulator`（实施方案 §3.5）。

## 用法

```bash
cd tools/validate
uv venv --python 3.12 .venv            # pooltool 要求 Python 3.10–3.13
uv pip install --python .venv/bin/python -r requirements.txt
.venv/bin/python generate_baseline.py  # 生成 engine/src/test/resources/pooltool_baseline.json
cd ../.. && ./gradlew :engine:test --tests '*PooltoolBaselineTest*'
```

完整误差表在 `engine/build/reports/pooltool-comparison.txt`（每次跑测试重写）。

## 球形（共 540 个）

| 类别 | 内容 | 数量 |
|---|---|---|
| `cut` 切球 | 切角 10°–70°（步长 10°）× 高杆 0.45R / 中杆 0 / 低杆 −0.5R × 力度 1–8 档 | 168 |
| `cutSide` 加塞切球 | 切角 10°、30°、50°、70° × 高 / 中 / 低杆 × 左塞或右塞 0.3R × 力度 1–8 档 | 192 |
| `bank` 一库 | 白球从 (300, 400) 直接打上边库，入射角 15°–75°（步长 15°）× 高 / 中 / 低杆 × 左 / 无 / 右塞 × 力度 3、4、6、8 档 | 180 |

- 切球：目标球在 (1600, 635) mm，目标球线沿 +x 指向右短库中点，白球离假想球 300 mm。一库球形的碰库点都避开了袋口。
- 白球的初始速度和旋转直接写进 pooltool 的状态，和 `Shot` 的定义一致：`v = speed·d`，`ω = 5·v·b/(2R)·(ẑ×d)`，`ωz = −5·v·side/(2R)`。这样不经过 pooltool 的杆-球模型。
- 物理参数与 `PhysicsParams.DEFAULT` 一致：μs 0.2，μr 0.01，球-球恢复系数 0.95，库边恢复系数 0.85，库边摩擦 0.2。
- 坐标：pooltool 的长边沿 y 轴，脚本交换了 x、y 两轴，JSON 里存的都是 engine 坐标。交换两轴是一次镜像，所以角速度的 z 分量（左右塞）要取反，脚本里已经处理。
- 目标球在 pooltool 里的 id 是 `"ob"`，不能用 `"1"`：pooltool 的库边段 id 也是 `"1"`、`"2"`……，按 id 找事件时会混在一起。

每个球形导出三组 pooltool 结果：

| 组 | 球-球碰撞 | 库边 | 引擎用的参数 | 测试里 |
|---|---|---|---|---|
| `realistic` | 有摩擦（Alciatore 曲线） | Stronge 柔性模型（pooltool 默认） | `PhysicsParams.DEFAULT` | 断言不吃库球形，吃库的只报告 |
| `matched` | 无摩擦 | Stronge | `ballFriction = NONE` | 断言不吃库球形，吃库的只报告 |
| `han2005` | 无摩擦 | Han 2005 解析模型（与引擎相同） | `ballFriction = NONE` | 断言，吃库球形也断言（碰袋角、进袋的除外） |

导出的字段包括：
- 每颗球的停点，第一次、第二次碰库时的球心位置，以及第一次出库后进入纯滚动时的方向（出库方向）；
- 目标球的出球方向；
- 白球碰后第一次进入纯滚动时的方向（分离方向）。

## 结果（2026-10-16，按《pooltool对齐实施方案.md》做完阶段 0–3）

断言阈值都是 1 cm / 1°。

| 对比组 | 引擎参数 | 断言范围 | 结果 |
|---|---|---|---|
| `realistic` | 默认（Alciatore 球-球摩擦 + Han 2005 库边） | 不吃库球形 125 个 | 全部达标，最大 0.03 mm / 0.09° |
| `matched` | 球-球无摩擦 | 不吃库球形 122 个 | 全部达标，最大 0.03 mm / 0.00° |
| `han2005` | 球-球无摩擦 | 不吃库 122 个 + 吃库但没碰袋角、没进袋的 385 个 | 全部达标 |

**阶段 2（球-球摩擦）**：`Simulator.collide` 的算法和 pooltool 的 `FrictionalInelastic` 相同。改之前，和 `realistic` 对比，目标球最多偏 5.9°，加塞切球最多偏 8.6°；改之后全部在 0.1° 以内。剩下的 0.09° 来自测量方法：引擎这边，目标球方向取的是轨迹折线的第一段，有摩擦以后目标球带着旋转，出球后的轨迹略微弯曲。

**阶段 3（库边 Han 2005）**：`Simulator.han2005` 和 pooltool 的 Han 2005 实现相同，库边高度取 `TableSpec.cushionHeight` = 0.64 × 球径。

- 改之前，库边模型把与法向运动对应的旋转直接反向，所以球离开库边时刚好在自然滚动。真实情况是：接触点高于球心，上旋大部分留了下来，球出库后先打滑、走弧线，再转为滚动。所以一库出库方向和 pooltool 差中位 10°、最大 31°。
- 改之后，和 `han2005` 组对比，所有没碰袋角的吃库球形都在 1 cm / 1° 以内，误差中位数约 0。
- 和 pooltool 默认的 Stronge 库边模型对比（`realistic` 组，只报告）：

  | 一库球形（中位 / P90） | 改之前 | 改之后 |
  |---|---|---|
  | 白球出库方向 | 10.1° / 17.1° | 0.23° / 1.7° |
  | 白球停点 | 582 / 1469 mm | 23 / 373 mm |

  一库球形里，同时满足"出库方向 ≤ 1°、停点 ≤ 3 cm"的有 94 / 156 个（60%），还没达到实施方案的目标。剩下的差距来自 pooltool 自己的两种库边模型：Han 2005 用固定恢复系数，Stronge 用柔性模型，二者本来就不一致。要继续缩小，只能把 Stronge 移植过来，它要数值求解压缩、恢复两个阶段，代码量和计算量都大得多。考虑到真实球台的库边本来就要靠校准，建议先停在 Han 2005。

**碰到袋角的球形**（G5，未处理）：pooltool 的袋角是圆弧（角袋半径 21 mm、中袋 8 mm），引擎把袋角当成一个点。所以碰到袋角后，两边的轨迹会分开，停点最多差 1.6 m。这类球形在测试里只报告，不断言，报告里标为 `[袋角/进袋]`。

**侧旋衰减**（阶段 1）：`spinFriction` 从 0.044 改成 0.0127，和 pooltool 一致。加塞的吃库球形现在和 `han2005` 组全部一致，说明侧旋衰减和库边对侧旋的处理都对上了。

## 性能

1620 次模拟（540 个球形 × 3 组）在 JVM 上共 0.3 s，平均每条路线约 0.2 ms。加球-球摩擦、换 Han 2005 库边前后没有明显差别。

## 备注

- 脚本里的 pooltool 物理模型都是显式指定的，不读本机 `~/.config/pooltool` 下的配置。
- pooltool 0.6.0 的 Stronge 库边模型在个别情况下会报断言错误。脚本遇到时会改用 Mathavan 2010 库边模型，并把 `cushionModel` 字段记下来；当前这组球形没有触发。
- 30° 法则、90° 法则那类理论测试（`SimulatorTest`）是按无摩擦推导的，所以测试里显式传了 `ballFriction = BallFriction.NONE`。
