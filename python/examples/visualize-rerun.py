# Example usage - this file requires the optional rerun dependency
# Install with: uv sync --extra rerun

# In ARCore world coordinates:
# - Y is aligned with gravity and points up. This remains fixed.
# - X and Z are chosen at session start, typically based on the phone's initial facing
#   so that Z roughly matches the initial forward direction and X the initial right.
# - After initialization, the world X/Y/Z axes are fixed in space; they do not rotate
#   with the phone. The phone's camera pose moves/rotates within this fixed frame.

import math

import numpy as np
import rerun as rr
import transforms3d as t3d
from rerun import blueprint as rrb
from teleop_android import Teleop

#: Constants

XYZ_AXIS_NAMES = ["x", "y", "z"]
RPY_AXIS_NAMES = ["roll", "pitch", "yaw"]
XYZ_AXIS_COLORS = [[(231, 76, 60), (39, 174, 96), (52, 120, 219)]]

TF_RUB2FLU = np.array([[0, 0, -1, 0], [-1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 0, 1]])
TF_XYZW_TO_WXYZ = np.array([[0, 0, 0, 1], [1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0]])
TF_WXYZ_TO_XYZW = np.array([[0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1], [1, 0, 0, 0]])

#: Init Rerun

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
        time_ranges=rrb.VisibleTimeRanges(
            timeline="log_time",
            start=rrb.TimeRangeBoundary.cursor_relative(seconds=-60),
            end=rrb.TimeRangeBoundary.cursor_relative(seconds=0),
        ),
    ),
    column_shares=[0.45, 0.55],
)

rr.init("test_teleop", spawn=True, default_blueprint=blueprint)

#: Initial pose

position_initial = [0, 0, 0]
orientation_euler_initial = [0, math.radians(-45), 0]

pose_initial = t3d.affines.compose(
    position_initial,
    t3d.euler.euler2mat(*orientation_euler_initial, axes="sxyz"),
    [1, 1, 1],
)

orientation_quaternion_wxyz_initial = t3d.quaternions.mat2quat(pose_initial[:3, :3])
orientation_quaternion_xyzw_initial = (
    TF_WXYZ_TO_XYZW @ orientation_quaternion_wxyz_initial
)

rr.log(
    "/world",
    rr.Transform3D(
        translation=position_initial,
        quaternion=rr.Quaternion(xyzw=orientation_quaternion_xyzw_initial),
    ),
    static=True,
)

# Create a pinhole camera with no images to aid visualizations
rr.log(
    "/world/phone",
    rr.Pinhole(
        focal_length=(500.0, 500.0),
        resolution=(640, 480),
        image_plane_distance=0.5,
    ),
    static=True,
)


#: Pose Updates


def callback(message: dict) -> None:
    """
    Callback function triggered when pose updates are received.

    Arguments:
        - dict: A dictionary containing position, orientation, and fps information.
    """
    position_rub = message["position"]
    orientation_rub = message["orientation"]
    position_rub = np.array([position_rub["x"], position_rub["y"], position_rub["z"]])
    orientation_rub_quaternion_xyzw = np.array(
        [
            orientation_rub["x"],
            orientation_rub["y"],
            orientation_rub["z"],
            orientation_rub["w"],
        ]
    )
    orientation_rub_quaternion_wxyz = TF_XYZW_TO_WXYZ @ orientation_rub_quaternion_xyzw

    pose_rub = t3d.affines.compose(
        position_rub,
        t3d.quaternions.quat2mat(orientation_rub_quaternion_wxyz),
        [1, 1, 1],
    )

    # Change of basis for the rotation matrix in pose R' = C R C^{-1}
    pose = TF_RUB2FLU @ pose_rub
    pose[:3, :3] = pose[:3, :3] @ np.linalg.inv(TF_RUB2FLU[:3, :3])

    # Correct for initial phone orientation
    pose = pose @ pose_initial

    # forward, left, up -> roll, pitch, yaw
    orientation_euler = np.degrees(
        np.array(t3d.euler.mat2euler(pose[:3, :3], axes="sxyz"))
    )
    orientation_quaternion_wxyz = t3d.quaternions.mat2quat(pose[:3, :3])
    orientation_quaternion_xyzw = TF_WXYZ_TO_XYZW @ orientation_quaternion_wxyz

    rr.log("/position", rr.Scalars(pose[:3, 3]))
    rr.log("/orientation", rr.Scalars(orientation_euler))
    rr.log(
        "/world/trajectory-phone",
        rr.Points3D([pose[:3, 3]]),
    )
    rr.log(
        "/world/phone",
        rr.Transform3D(
            translation=pose[:3, 3],
            rotation=rr.Quaternion(xyzw=orientation_quaternion_xyzw),
        ),
    )


teleop = Teleop()
teleop.subscribe(callback)
teleop.run()
