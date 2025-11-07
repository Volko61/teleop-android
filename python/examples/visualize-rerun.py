import numpy as np
import rerun as rr
from rerun import blueprint as rrb
from scipy.spatial.transform import Rotation
from teleop_android import Teleop

# Example usage - this file requires the optional rerun dependency
# Install with: uv sync --extra rerun

XYZ_AXIS_NAMES = ["x", "y", "z"]
RPY_AXIS_NAMES = ["roll", "pitch", "yaw"]
XYZ_AXIS_COLORS = [[(231, 76, 60), (39, 174, 96), (52, 120, 219)]]

blueprint = rrb.Horizontal(
    rrb.Vertical(
        rrb.TimeSeriesView(
            origin="position",
            name="Position",
            overrides={
                "/position": rr.SeriesLines.from_fields(
                    names=XYZ_AXIS_NAMES, colors=XYZ_AXIS_COLORS
                ),  # type: ignore[arg-type]
            },
        ),
        rrb.TimeSeriesView(
            origin="orientation",
            name="Orientation",
            overrides={
                "/orientation": rr.SeriesLines.from_fields(
                    names=RPY_AXIS_NAMES, colors=XYZ_AXIS_COLORS
                ),  # type: ignore[arg-type]
            },
        ),
    ),
    # rrb.Spatial3DView(origin="/", name="World position"),
    column_shares=[0.45, 0.55],
)

rr.init("test_teleop", spawn=True, default_blueprint=blueprint)


def callback(message: dict) -> None:
    """
    Callback function triggered when pose updates are received.

    Arguments:
        - dict: A dictionary containing position, orientation, and fps information.
    """
    position = message["position"]
    orientation = message["orientation"]

    position = np.array([position["x"], position["y"], position["z"]])
    quat = np.array(
        [orientation["x"], orientation["y"], orientation["z"], orientation["w"]]
    )
    rotation = Rotation.from_quat(quat)
    euler = rotation.as_euler("xyz", degrees=True)  # Roll, Pitch, Yaw in degrees

    rr.log("/position", rr.Scalars(position))
    rr.log("/orientation", rr.Scalars(euler))


teleop = Teleop()
teleop.subscribe(callback)
teleop.run()
