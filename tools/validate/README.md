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

## 球形

- 目标球在 (1600, 635) mm，目标球线沿 +x 指向右短库中点；白球离假想球 300 mm。
- 切角 10°–70°（步长 10°）× 杆法 高杆 0.45R / 中杆 0 / 低杆 −0.5R × 力度 1–8 档（`PowerLevels`）= 168 个球形。
- 白球初始速度、旋转直接写进 pooltool 的状态（`v = speed·d`，`ω = 5·v·b/(2R)·(ẑ×d)`），和 `Shot` 的定义一致，不经过 pooltool 的杆-球模型。
- 物理参数与 `PhysicsParams.DEFAULT` 一致：μs 0.2、μr 0.01、球-球恢复系数 0.95、库边恢复系数 0.85。
- 坐标：pooltool 的长边沿 y 轴，脚本交换 x、y 两轴，JSON 里存的都是 engine 坐标。交换两轴等于做一次镜像，没有左右塞时不影响物理结果。

每个球形导出两组 pooltool 结果：

| 组 | 球-球碰撞 | 库边 |
|---|---|---|
| `realistic` | pooltool 默认：有摩擦（Alciatore 摩擦曲线，会产生 throw、传旋转） | Stronge 柔性模型（默认） |
| `matched` | 无摩擦，和 engine 的假设一样 | 同上 |

导出的字段：白球、目标球的停点，各自第一次碰库时的球心位置，目标球出球方向，白球碰后第一次进入纯滚动时的方向（分离方向）。

## 结果（2026-10-16）

**不吃库、不进袋的球形**（断言阈值 1 cm / 1°）：

| 对比组 | 球形数 | 超出阈值 | 最大位置误差 | 最大角度误差 |
|---|---|---|---|---|
| `matched` | 53 | 0 | 0.03 mm | 0.00° |
| `realistic` | 54 | **47** | 94.6 mm（目标球停点） | 5.86°（目标球方向） |

- **和 `matched` 对比时误差基本为零**：滑动/滚动的解析解、球-球碰撞的法向冲量、碰撞时刻都和 pooltool 一致，说明代码实现没有错。测试断言的是这一组。
- **和 `realistic` 对比不达标，原因是 engine 的球-球碰撞没有摩擦**（`Simulator.resolve` 里写明"旋转不传递"）。真实的球之间有摩擦，会带来两个后果：
  1. **throw（目标球被带偏）**：目标球方向的误差中位数 2.9°，最大 5.9°。切角越大、速度越慢，偏差越大（Alciatore 摩擦系数在相对速度低时更大），比如 cut70 低杆 2 档偏 5.86°。
  2. **白球的切向速度和旋转被摩擦冲量改变**：分离方向误差中位数 2.35°，最大 5.3°（小切角的低杆最明显）；白球停点误差中位数 20 mm，最大 68 mm。
- 一个球形（`cut70_CENTER_p2`）只在 engine 里碰了库，是在库边附近停下的临界情况，被记为"结果分叉"。

**吃库的球形**（只出报告，不断言）：

- 碰库前的轨迹和 `matched` 一致：第一次碰库位置误差 ≤ 2.5 mm。
- 碰库后差别很大。2026-10-16 engine 的库边加了切向摩擦冲量（`cushionFriction` 0.2，最大不超过库仑摩擦）和侧旋，之后重跑了一遍（这批球形都不加塞）：

  | 停点误差（中位 / P90） | 改库边之前 | 改库边之后 |
  |---|---|---|
  | `matched` 白球 | 498 / 1231 mm | 441 / 1171 mm |
  | `matched` 目标球 | 474 / 1479 mm | 432 / 1434 mm |
  | `realistic` 白球 | 525 / 1253 mm | 484 / 1250 mm |
  | `realistic` 目标球 | 507 / 1490 mm | 480 / 1464 mm |

  加了库边摩擦以后，误差小了约 10%，但还是在半米量级。剩下的差距主要来自两点：一是 engine 没有考虑库边高度（pooltool 的接触点在球心上方，高低杆会改变出库速度和出库角度）；二是 pooltool 的 Stronge 柔性模型里，库边的法向、切向冲量是耦合的，engine 用的是固定恢复系数 e=0.85，再加一个按库仑摩擦封顶的切向冲量。另外，和 `realistic` 对比时，目标球第一次碰库位置的误差中位数是 13 mm，这来自 throw，和库边模型无关。

## 改进建议（本次没有改物理模型）

1. 球-球碰撞加摩擦（参考 pooltool `FrictionalInelastic` + Alciatore 摩擦曲线），就能对上 `realistic` 组的不吃库球形。这是走位精度最直接的提升。
2. 库边模型再补上库边高度（参考 Han 2005 / Mathavan 2010），吃库球形的停点才有参考价值。另外可以给基准数据加一批左右塞球形，用来校验新加的侧旋。
3. 改完以后，把 `PooltoolBaselineTest` 的断言对象从 `matched` 换成 `realistic`。

## 备注

- 脚本里的 pooltool 物理模型都是显式指定的，不读本机 `~/.config/pooltool` 下的配置。
- pooltool 0.6.0 的 Stronge 库边模型在个别情况下会报断言错误。脚本遇到时会改用 Mathavan 2010 库边模型，并把 `cushionModel` 字段记下来；当前这组球形没有触发。
