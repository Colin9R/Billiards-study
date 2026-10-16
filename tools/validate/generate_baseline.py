"""用 pooltool 生成中八台面的"标准答案"球形数据，供 engine 的 Kotlin 回归测试读取。

用法（在 tools/validate 下）：
    uv venv --python 3.12 .venv && uv pip install --python .venv/bin/python -r requirements.txt
    .venv/bin/python generate_baseline.py

输出：engine/src/test/resources/pooltool_baseline.json

约定：
- JSON 里的坐标一律用 engine 的坐标系（毫米，原点在台面左上角，x 沿长边，y 向下）。
  pooltool 的长边沿 y 轴（x ∈ [0, w]，y ∈ [0, l]），这里交换 x、y 两轴互转。交换两轴是一次
  镜像：位置、速度直接交换分量，角速度（伪矢量）的 z 分量要取反。
- 白球初始状态直接写入 rvw（速度 + 高低杆旋转），与 engine 的 Shot 定义完全一致：
  v = speed·d，ω = 5·v·b/(2R)·(ẑ×d)。这样绕开 pooltool 的杆-球碰撞模型（击球质量比、
  squirt 等），只比较台面上的运动和碰撞。
- 三组数据（见 VARIANTS）：
  * realistic：pooltool 默认物理（球-球有摩擦 Alciatore 模型 → 有 throw，库边 Stronge 柔性模型）。
  * matched：球-球摩擦设为 0，其余同上。用来区分"球-球摩擦"与"其他差异"各自贡献多少误差。
  * han2005：球-球摩擦为 0，库边换成 Han 2005 解析模型，用来验证库边模型的移植。
- 三类球形：cut（切球）、cutSide（加塞切球）、bank（白球直接打一库）。
"""

from __future__ import annotations

import json
import math
from collections import Counter
from pathlib import Path

import numpy as np
import pooltool as pt
from pooltool.events import EventType
from pooltool.objects.ball.datatypes import BallState
from pooltool.physics.resolve.ball_ball.friction import AlciatoreBallBallFriction
from pooltool.physics.resolve.ball_ball.frictional_inelastic import FrictionalInelastic
from pooltool.physics.resolve.ball_cushion.han_2005.model import Han2005Circular, Han2005Linear
from pooltool.physics.resolve.ball_cushion.mathavan_2010.model import (
    Mathavan2010Circular,
    Mathavan2010Linear,
)
from pooltool.physics.resolve.ball_cushion.stronge_compliant.model import (
    StrongeCompliantCircular,
    StrongeCompliantLinear,
)
from pooltool.physics.resolve.ball_pocket import CanonicalBallPocket
from pooltool.physics.resolve.resolver import VERSION, Resolver
from pooltool.physics.resolve.stick_ball.instantaneous_point import InstantaneousPoint
from pooltool.physics.resolve.transition import CanonicalTransition

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "engine/src/test/resources/pooltool_baseline.json"

# ---- 与 engine 保持一致的参数（TableSpec.kt / PhysicsParams.kt）----
L, W = 2540.0, 1270.0
R = 57.15 / 2
CORNER_MOUTH, SIDE_MOUTH = 86.0, 92.0
BALL = dict(R=R / 1000, u_s=0.2, u_r=0.01, e_b=0.95, e_c=0.85, f_c=0.2, g=9.81)
POWER_SPEEDS = [400.0, 600.0, 850.0, 1100.0, 1500.0, 2000.0, 2800.0, 4000.0]  # PowerLevels
STROKES = {"FOLLOW": 0.45, "CENTER": 0.0, "DRAW": -0.5}  # Stroke 枚举中的高/中/低杆
CUTS = [10, 20, 30, 40, 50, 60, 70]

# 球形：目标球在 (1600, 635)，目标球线沿 +x（指向右短库中点，不对袋口）；
# 白球离假想球 300 mm，按切角从左下方来。目标球位置避开中袋（x=1270），白球侧飞时不会直接进中袋。
OB_POS = (1600.0, 635.0)
CUE_DISTANCE = 300.0

# 加塞切球
SIDES = {"LEFT": -0.3, "NONE": 0.0, "RIGHT": 0.3}
SIDE_CUTS = [10, 30, 50, 70]

# 一库：白球从 (300, 400) 打上边库，碰库点 x ≈ 399–1686，避开角袋和中袋（1270 ± 46）
BANK_START = (300.0, 400.0)
BANK_INCIDENCES = [15, 30, 45, 60, 75]
BANK_LEVELS = [3, 4, 6, 8]


class ZeroBallBallFriction:
    """球-球无摩擦。BallParams 不允许 u_b = 0，所以直接替换摩擦策略。"""

    def calculate_friction(self, ball1, ball2) -> float:
        return 0.0


# 对比组：(球-球是否有摩擦, 依次尝试的库边模型)
VARIANTS = {
    "realistic": (True, ("stronge", "mathavan2010")),
    "matched": (False, ("stronge", "mathavan2010")),
    "han2005": (False, ("han2005",)),
}


def cushion_models(name: str):
    if name == "stronge":
        return StrongeCompliantLinear(omega_ratio=1.8), StrongeCompliantCircular(omega_ratio=1.8)
    if name == "mathavan2010":
        return Mathavan2010Linear(), Mathavan2010Circular()
    return Han2005Linear(), Han2005Circular()


def resolver(ball_ball_friction: bool, cushion: str = "stronge") -> Resolver:
    """显式写出模型，避免受本机 ~/.config/pooltool 下 resolver 配置影响。"""
    friction = (
        AlciatoreBallBallFriction(a=0.009951, b=0.108, c=1.088)
        if ball_ball_friction
        else ZeroBallBallFriction()
    )
    linear, circular = cushion_models(cushion)
    return Resolver(
        ball_ball=FrictionalInelastic(friction=friction),
        ball_linear_cushion=linear,
        ball_circular_cushion=circular,
        ball_pocket=CanonicalBallPocket(),
        stick_ball=InstantaneousPoint(english_throttle=1.0, squirt_throttle=1.0),
        transition=CanonicalTransition(),
        version=VERSION,
    )


def table() -> pt.Table:
    specs = pt.objects.PocketTableSpecs(
        l=L / 1000,
        w=W / 1000,
        corner_pocket_width=CORNER_MOUTH / 1000,
        side_pocket_width=SIDE_MOUTH / 1000,
    )
    return pt.Table.from_table_specs(specs)


def to_pt(p):  # engine 毫米 → pooltool 米（交换 x、y）
    return (p[1] / 1000, p[0] / 1000)


def to_engine(r) -> list[float]:  # pooltool 米 → engine 毫米
    return [round(float(r[1]) * 1000, 4), round(float(r[0]) * 1000, 4)]


def angle_engine(v) -> float:
    """pooltool 速度向量在 engine 坐标系里的方向角（度，atan2(y, x)，y 向下）。"""
    return round(math.degrees(math.atan2(float(v[0]), float(v[1]))), 4)


def cut_shot(cut_deg: float):
    """切球：返回 (白球, 目标球, 出杆方向)。"""
    ob = OB_POS
    ghost = (ob[0] - 2 * R, ob[1])
    a = math.radians(cut_deg)
    aim = (math.cos(a), math.sin(a))  # engine 坐标：从左上方往右下打
    cue = (ghost[0] - aim[0] * CUE_DISTANCE, ghost[1] - aim[1] * CUE_DISTANCE)
    return cue, ob, aim


def bank_shot(incidence_deg: float):
    """一库：白球直接打上边库（y = 0），入射角相对库边法线。碰库点都避开了袋口。"""
    a = math.radians(incidence_deg)
    return BANK_START, None, (math.sin(a), -math.cos(a))


def run(cue_xy, ob_xy, aim, tip: float, side: float, speed: float, variant: str) -> dict:
    cue = pt.Ball.create("cue", xy=to_pt(cue_xy), **BALL)
    balls = {"cue": cue}
    if ob_xy is not None:
        balls[OB_ID] = pt.Ball.create(OB_ID, xy=to_pt(ob_xy), **BALL)

    d = np.array([aim[1], aim[0], 0.0])  # pooltool 坐标系中的出杆方向
    v = speed / 1000
    w = 5 * v * tip / (2 * R / 1000) * np.cross([0.0, 0.0, 1.0], d)
    # engine 里 ωz = −5·V·side/(2R)。交换 x、y 两轴是镜像，角速度（伪矢量）的 z 分量要取反。
    # 物理上也对得上：engine 的右塞在屏幕上是逆时针（俯视），交换两轴后屏幕和 pooltool 是同一个世界。
    w[2] = 5 * v * side / (2 * R / 1000)
    rvw = np.array([cue.state.rvw[0], v * d, w])
    cue.state = BallState(rvw, pt.constants.sliding, 0.0)

    friction, cushions = VARIANTS[variant]
    # pooltool 0.6.0 的 Stronge 库边模型在个别情况下会断言失败（接触点法向速度被旋转抵消
    # 为正），这种球形退回 Mathavan 2010 库边模型，并在数据里注明。
    for cushion in cushions:
        system = pt.System(cue=pt.Cue(cue_ball_id="cue"), table=table(), balls={k: b.copy() for k, b in balls.items()})
        try:
            pt.simulate(system, engine=pt.physics.PhysicsEngine(resolver=resolver(friction, cushion)), inplace=True)
        except AssertionError:
            continue
        return {"cushionModel": cushion, **summarize(system)}
    raise RuntimeError(f"pooltool failed: {variant} cue={cue_xy} aim={aim} tip={tip} side={side} speed={speed}")


# 不能叫 "1"：pooltool 的库边段 id 也是 "1"、"2"……，按 id 找事件会混在一起
OB_ID = "ob"
CUSHION_TYPES = {EventType.BALL_LINEAR_CUSHION, EventType.BALL_CIRCULAR_CUSHION}


def summarize(system: pt.System) -> dict:
    events = system.events
    hit = next((e for e in events if e.event_type == EventType.BALL_BALL), None)

    def first_roll_after(bid: str, t0: float, t1: float):
        """(t0, t1) 内第一次转为纯滚动时的方向。"""
        e = next(
            (e for e in events if t0 < e.time < t1 and e.event_type == EventType.SLIDING_ROLLING and bid in e.ids),
            None,
        )
        return None if e is None else angle_engine(e.get_ball(bid, initial=False).state.rvw[1])

    def ball_summary(bid: str) -> dict:
        ball = system.balls[bid]
        mine = [e for e in events if bid in e.ids]
        cushions = [e for e in mine if e.event_type in CUSHION_TYPES]
        out = {
            "final": to_engine(ball.state.rvw[0]),
            "pocketed": any(e.event_type == EventType.BALL_POCKET for e in mine),
            # 碰到袋角（pooltool 里是圆弧，engine 里是一个点，几何不同，见实施方案 G5）
            "jaw": any(e.event_type == EventType.BALL_CIRCULAR_CUSHION for e in mine),
            "firstCushion": to_engine(cushions[0].get_ball(bid, initial=True).state.rvw[0]) if cushions else None,
            "secondCushion": to_engine(cushions[1].get_ball(bid, initial=True).state.rvw[0])
            if len(cushions) > 1
            else None,
            # 第一次碰库之后、下一次碰撞之前，第一次进入纯滚动时的方向（出库后的走向）
            "postCushionRollDirDeg": None,
        }
        if cushions:
            t0 = cushions[0].time
            nxt = next((e.time for e in mine if e.time > t0 and (e.event_type in CUSHION_TYPES or e.event_type == EventType.BALL_BALL)), math.inf)
            out["postCushionRollDirDeg"] = first_roll_after(bid, t0, nxt)
        out["_cushionTime"] = cushions[0].time if cushions else math.inf
        return out

    cue = ball_summary("cue")
    ob = ball_summary(OB_ID) if OB_ID in system.balls else None
    out = {"hit": hit is not None, "cue": cue, "object": ob}
    if hit is not None:
        out["contact"] = to_engine(hit.get_ball("cue", initial=True).state.rvw[0])
        out["objectDirDeg"] = angle_engine(hit.get_ball(OB_ID, initial=False).state.rvw[1])
        # 碰后白球第一次转为纯滚动时的方向（要求在碰库之前）。这是走位最关心的"分离角"。
        out["cueRollDirDeg"] = first_roll_after("cue", hit.time, cue["_cushionTime"])
    balls = [b for b in (cue, ob) if b is not None]
    for b in balls:
        del b["_cushionTime"]
    out["noCushion"] = all(b["firstCushion"] is None for b in balls)
    out["noPocket"] = not any(b["pocketed"] for b in balls)
    return out


def shot_record(sid: str, category: str, geometry, tip: float, side: float, level: int, **extra) -> dict:
    cue_xy, ob_xy, aim = geometry
    speed = POWER_SPEEDS[level - 1]
    return {
        "id": sid,
        "category": category,
        **extra,
        "tipOffset": tip,
        "side": side,
        "powerLevel": level,
        "speed": speed,
        "cue": [round(c, 6) for c in cue_xy],
        "object": None if ob_xy is None else list(ob_xy),
        "aim": [round(c, 12) for c in aim],
        "pooltool": {name: run(cue_xy, ob_xy, aim, tip, side, speed, name) for name in VARIANTS},
    }


def main() -> None:
    shots = []
    # 1) 切球：切角 × 高中低杆 × 力度
    for cut in CUTS:
        for stroke, tip in STROKES.items():
            for level in range(1, 9):
                shots.append(shot_record(f"cut{cut}_{stroke}_p{level}", "cut", cut_shot(cut), tip, 0.0, level,
                                         cutDeg=cut, stroke=stroke))
    # 2) 加塞切球：检验球-球摩擦带来的"加塞 throw"
    for cut in SIDE_CUTS:
        for stroke, tip in STROKES.items():
            for side_name, side in SIDES.items():
                if side == 0.0:
                    continue
                for level in range(1, 9):
                    shots.append(shot_record(f"cut{cut}_{stroke}_{side_name}_p{level}", "cutSide", cut_shot(cut), tip,
                                             side, level, cutDeg=cut, stroke=stroke))
    # 3) 一库：白球直接打库
    for inc in BANK_INCIDENCES:
        for stroke, tip in STROKES.items():
            for side_name, side in SIDES.items():
                for level in BANK_LEVELS:
                    shots.append(shot_record(f"bank{inc}_{stroke}_{side_name}_p{level}", "bank", bank_shot(inc), tip,
                                             side, level, incidenceDeg=inc, stroke=stroke))
    data = {
        "generator": "tools/validate/generate_baseline.py",
        "pooltoolVersion": pt.__version__,
        "units": "mm, deg; engine 坐标系（原点左上角，y 向下）；角度为 atan2(y, x)",
        "table": {"length": L, "width": W, "ballRadius": R, "cornerMouth": CORNER_MOUTH, "sideMouth": SIDE_MOUTH},
        "physics": {k: BALL[k] for k in ("u_s", "u_r", "e_b", "e_c", "f_c")},
        "variants": {
            "realistic": "pooltool 默认：FrictionalInelastic + Alciatore 球-球摩擦，StrongeCompliant 库边",
            "matched": "球-球无摩擦（与 engine 原来的假设一致），StrongeCompliant 库边",
            "han2005": "球-球无摩擦，Han 2005 库边（解析模型，考虑库边高度），用来验证库边模型的移植",
        },
        "shots": shots,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(shots)} shots", dict(Counter(s["category"] for s in shots)))
    fallbacks = [(s["id"], v) for s in shots for v, r in s["pooltool"].items() if r["cushionModel"] == "mathavan2010"]
    print("Stronge 断言失败、改用 Mathavan 2010 的球形：", fallbacks or "无")


if __name__ == "__main__":
    main()
