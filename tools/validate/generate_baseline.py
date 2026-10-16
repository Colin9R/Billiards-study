"""用 pooltool 生成中八台面的"标准答案"球形数据，供 engine 的 Kotlin 回归测试读取。

用法（在 tools/validate 下）：
    uv venv --python 3.12 .venv && uv pip install --python .venv/bin/python -r requirements.txt
    .venv/bin/python generate_baseline.py

输出：engine/src/test/resources/pooltool_baseline.json

约定：
- JSON 里的坐标一律用 engine 的坐标系（毫米，原点在台面左上角，x 沿长边，y 向下）。
  pooltool 的长边沿 y 轴（x ∈ [0, w]，y ∈ [0, l]），这里交换 x、y 两轴互转。交换两轴是一次
  镜像，没有左右塞时物理过程镜像对称，结果可以直接对比。
- 白球初始状态直接写入 rvw（速度 + 高低杆旋转），与 engine 的 Shot 定义完全一致：
  v = speed·d，ω = 5·v·b/(2R)·(ẑ×d)。这样绕开 pooltool 的杆-球碰撞模型（击球质量比、
  squirt 等），只比较台面上的运动和碰撞。
- 两组数据：
  * realistic：pooltool 默认物理（球-球有摩擦 Alciatore 模型 → 有 throw，库边 Stronge 柔性模型）。
  * matched：球-球摩擦设为 0，其余同上。用来区分"球-球摩擦"与"其他差异"各自贡献多少误差。
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
import pooltool as pt
from pooltool.events import EventType
from pooltool.objects.ball.datatypes import BallState
from pooltool.physics.resolve.ball_ball.friction import AlciatoreBallBallFriction
from pooltool.physics.resolve.ball_ball.frictional_inelastic import FrictionalInelastic
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
BALL = dict(R=R / 1000, u_s=0.2, u_r=0.01, e_b=0.95, e_c=0.85, g=9.81)
POWER_SPEEDS = [400.0, 600.0, 850.0, 1100.0, 1500.0, 2000.0, 2800.0, 4000.0]  # PowerLevels
STROKES = {"FOLLOW": 0.45, "CENTER": 0.0, "DRAW": -0.5}  # Stroke 枚举中的高/中/低杆
CUTS = [10, 20, 30, 40, 50, 60, 70]

# 球形：目标球在 (1600, 635)，目标球线沿 +x（指向右短库中点，不对袋口）；
# 白球离假想球 300 mm，按切角从左下方来。目标球位置避开中袋（x=1270），白球侧飞时不会直接进中袋。
OB_POS = (1600.0, 635.0)
CUE_DISTANCE = 300.0


class ZeroBallBallFriction:
    """球-球无摩擦。BallParams 不允许 u_b = 0，所以直接替换摩擦策略。"""

    def calculate_friction(self, ball1, ball2) -> float:
        return 0.0


def resolver(ball_ball_friction: bool, cushion: str = "stronge") -> Resolver:
    """显式写出模型，避免受本机 ~/.config/pooltool 下 resolver 配置影响。"""
    friction = (
        AlciatoreBallBallFriction(a=0.009951, b=0.108, c=1.088)
        if ball_ball_friction
        else ZeroBallBallFriction()
    )
    return Resolver(
        ball_ball=FrictionalInelastic(friction=friction),
        ball_linear_cushion=StrongeCompliantLinear(omega_ratio=1.8)
        if cushion == "stronge"
        else Mathavan2010Linear(),
        ball_circular_cushion=StrongeCompliantCircular(omega_ratio=1.8)
        if cushion == "stronge"
        else Mathavan2010Circular(),
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


def shot_geometry(cut_deg: float):
    ob = OB_POS
    ghost = (ob[0] - 2 * R, ob[1])
    a = math.radians(cut_deg)
    aim = (math.cos(a), math.sin(a))  # engine 坐标：从左上方往右下打
    cue = (ghost[0] - aim[0] * CUE_DISTANCE, ghost[1] - aim[1] * CUE_DISTANCE)
    return cue, ob, aim


def run(cut: float, tip: float, speed: float, friction: bool) -> dict:
    cue_xy, ob_xy, aim = shot_geometry(cut)
    cue = pt.Ball.create("cue", xy=to_pt(cue_xy), **BALL)
    ob = pt.Ball.create("1", xy=to_pt(ob_xy), **BALL)

    d = np.array([aim[1], aim[0], 0.0])  # pooltool 坐标系中的出杆方向
    v = speed / 1000
    w = 5 * v * tip / (2 * R / 1000) * np.cross([0.0, 0.0, 1.0], d)
    rvw = np.array([cue.state.rvw[0], v * d, w])
    cue.state = BallState(rvw, pt.constants.sliding, 0.0)

    # pooltool 0.6.0 的 Stronge 库边模型在"大力低杆回库"时会断言失败（接触点法向速度
    # 被旋转抵消为正），这种球形退回 Mathavan 2010 库边模型，并在数据里注明。
    for cushion in ("stronge", "mathavan2010"):
        system = pt.System(cue=pt.Cue(cue_ball_id="cue"), table=table(), balls={"cue": cue.copy(), "1": ob.copy()})
        try:
            pt.simulate(system, engine=pt.physics.PhysicsEngine(resolver=resolver(friction, cushion)), inplace=True)
        except AssertionError:
            continue
        return {"cushionModel": cushion, **summarize(system)}
    raise RuntimeError(f"pooltool failed: cut={cut} tip={tip} speed={speed}")


def summarize(system: pt.System) -> dict:
    events = system.events
    hit = next((e for e in events if e.event_type == EventType.BALL_BALL), None)
    cushion_types = {EventType.BALL_LINEAR_CUSHION, EventType.BALL_CIRCULAR_CUSHION}

    def ball_summary(bid: str) -> dict:
        ball = system.balls[bid]
        mine = [e for e in events if bid in e.ids]
        first_cushion = next((e for e in mine if e.event_type in cushion_types), None)
        pocketed = any(e.event_type == EventType.BALL_POCKET for e in mine)
        return {
            "final": to_engine(ball.state.rvw[0]),
            "pocketed": pocketed,
            "firstCushion": None
            if first_cushion is None
            else to_engine(first_cushion.get_ball(bid, initial=True).state.rvw[0]),
            "_cushionTime": None if first_cushion is None else first_cushion.time,
        }

    cue, ob = ball_summary("cue"), ball_summary("1")
    out = {"hit": hit is not None, "cue": cue, "object": ob}
    if hit is not None:
        ob_after = hit.get_ball("1", initial=False).state.rvw
        out["contact"] = to_engine(hit.get_ball("cue", initial=True).state.rvw[0])
        out["objectDirDeg"] = angle_engine(ob_after[1])
        # 碰后白球第一次转为纯滚动时的方向（若在碰库之前）。这是走位最关心的"分离角"。
        roll = next(
            (
                e
                for e in events
                if e.time > hit.time and e.event_type == EventType.SLIDING_ROLLING and "cue" in e.ids
            ),
            None,
        )
        before_cushion = cue["_cushionTime"] is None or (roll is not None and roll.time < cue["_cushionTime"])
        out["cueRollDirDeg"] = (
            angle_engine(roll.get_ball("cue", initial=False).state.rvw[1])
            if roll is not None and before_cushion
            else None
        )
    for b in (cue, ob):
        del b["_cushionTime"]
    out["noCushion"] = cue["firstCushion"] is None and ob["firstCushion"] is None
    out["noPocket"] = not cue["pocketed"] and not ob["pocketed"]
    return out


def main() -> None:
    shots = []
    for cut in CUTS:
        for stroke, tip in STROKES.items():
            for level, speed in enumerate(POWER_SPEEDS, start=1):
                cue_xy, ob_xy, aim = shot_geometry(cut)
                shots.append(
                    {
                        "id": f"cut{cut}_{stroke}_p{level}",
                        "cutDeg": cut,
                        "stroke": stroke,
                        "tipOffset": tip,
                        "powerLevel": level,
                        "speed": speed,
                        "cue": [round(c, 6) for c in cue_xy],
                        "object": list(ob_xy),
                        "aim": [round(c, 12) for c in aim],
                        "pooltool": {
                            "realistic": run(cut, tip, speed, friction=True),
                            "matched": run(cut, tip, speed, friction=False),
                        },
                    }
                )
    data = {
        "generator": "tools/validate/generate_baseline.py",
        "pooltoolVersion": pt.__version__,
        "units": "mm, deg; engine 坐标系（原点左上角，y 向下）；角度为 atan2(y, x)",
        "table": {"length": L, "width": W, "ballRadius": R, "cornerMouth": CORNER_MOUTH, "sideMouth": SIDE_MOUTH},
        "physics": {k: BALL[k] for k in ("u_s", "u_r", "e_b", "e_c")},
        "variants": {
            "realistic": "pooltool 默认：FrictionalInelastic + Alciatore 球-球摩擦，StrongeCompliant 库边",
            "matched": "同上但球-球摩擦为 0（与 engine 的无摩擦碰撞假设一致）",
        },
        "shots": shots,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n")
    n_clean = sum(s["pooltool"]["realistic"]["noCushion"] for s in shots)
    print(f"wrote {OUT.relative_to(ROOT)}: {len(shots)} shots, {n_clean} without cushion contact")


if __name__ == "__main__":
    main()
