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
    rrb.Spatial3DView(
        origin="/world",
        name="World position",
        overrides={
            "trajectory_phone": rrb.VisibleTimeRanges(
                timeline="stable_time",
                start=rrb.TimeRangeBoundary.cursor_relative(seconds=-10),
                end=rrb.TimeRangeBoundary.cursor_relative(seconds=0),
            ),
        },
    ),
    column_shares=[0.45, 0.55],
)

rr.init("test_teleop", spawn=True, default_blueprint=blueprint)

rr.log(
    "/world",
    rr.Transform3D(
        rotation_axis_angle=rr.RotationAxisAngle(axis=(1, 0, 0), angle=-np.pi / 2)
    ),
    static=True,
)

# Fake pinhole camera to aid visualizations
rr.log(
    "/world/phone",
    rr.Pinhole(
        focal_length=(500.0, 500.0),
        resolution=(640, 480),
        image_plane_distance=0.5,
    ),
    static=True,
)


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
    # Use ZYX intrinsic rotations (yaw-pitch-roll) for device orientation
    # This avoids coupling between axes that occurs with XYZ extrinsic rotations
    euler_zyx = rotation.as_euler("ZYX", degrees=True)  # Yaw, Pitch, Roll
    # Reorder to Roll, Pitch, Yaw for display
    euler = np.array([euler_zyx[2], euler_zyx[1], euler_zyx[0]])

    rr.log("/position", rr.Scalars(position))
    rr.log("/orientation", rr.Scalars(euler))

    rr.log(
        "/world/trajectory-phone",
        rr.Points3D([position]),
    )

    rr.log(
        "/world/phone",
        rr.Transform3D(
            translation=position,
            rotation=rr.Quaternion(xyzw=quat),
        ),
    )


teleop = Teleop()
teleop.subscribe(callback)
teleop.run()
